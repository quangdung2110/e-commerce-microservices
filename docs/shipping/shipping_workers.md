# Shipping Service - Danh Sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Database Schema](shipping_db_design.md)
*   [State Machine & Provider Mapping](shipping_state_machine.md)
*   [Workflows (API & Data Flow)](shipping_workflow.md)
*   [Events Structure & Kafka Topics](shipping_events.md)

---

Shipping Service chạy các workers tập trung vào: webhook ingestion, rate card sync, outbox publishing, và shipment health monitoring.

---

## 1. Debezium CDC Connector (Thay thế Outbox Polling Worker)
**Loại hình:** Kafka Connect Dedicated Pod.

**Sứ mệnh:** Bắt luồng WAL (Write-Ahead-Log) trực tiếp từ Postgres, phát sự kiện lên Kakfka `t.shipping.events` không có độ trễ, thay thế hoàn toàn vòng lặp truy vấn Polling hao tổn hiệu năng.

**Cơ chế:** Debezium theo dõi Table `shipping_outbox`. Mỗi khi có Record `INSERT` mới, tự động đẩy lên Kafka đúng theo `partition_key` được chỉ định sẳn.

---

## 2. Webhook Ingest Worker (Kafka Consumer — Internal)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `shipping-webhook-processor`
**Consume Topic:** `t.shipping.webhooks.raw`

**Sứ mệnh:** Nhận raw webhooks 3PL → Parse → Chuẩn hóa → Update FSM → Write ScyllaDB event log → Emit outbox event.

**Chi tiết luồng hoạt động:**
1. **Consume** raw webhook message (đã dump bởi API endpoint).
2. **Identify Provider** từ header/URL path.
3. **Parse Provider-Specific Payload:** Adapter Pattern cho mỗi 3PL:
   - `GHNWebhookAdapter.parse(rawBody)` → `ShippingWebhookEvent`
   - `NinjaVanWebhookAdapter.parse(rawBody)` → `ShippingWebhookEvent`
4. **Verify Webhook Signature:**
   - GHN: HMAC-SHA256 verification.
   - NinjaVan: IP whitelist + token verification.
   - Nếu FAIL → Log alert + SKIP.
5. **Idempotency Check (Dual-Layer):**
   - Tạo `provider_event_fingerprint` (ưu tiên `provider_webhook_id`; fallback `hash(tracking_number + provider_status + occurred_at + location)`).
   - **Layer 1:** Redis `SETNX shipping:webhook_idemp:{tracking_number}:{provider_event_fingerprint}` (TTL 7 days). Nếu fail -> Bỏ qua.
   - **Layer 2:** Ghi DB rào cuối với `shipping_event_idempotency` khoá cứng `shipment_id` + `UPDATE_SHIPMENT_STATUS` + `action_scope_key = provider_event_fingerprint`.
   ```sql
   INSERT INTO shipping_event_idempotency (event_id, shipment_id, action_type, action_scope_key, processed_at)
   VALUES (:webhook_id, :shipment_id, 'UPDATE_SHIPMENT_STATUS', :provider_event_fingerprint, NOW())
   ON CONFLICT DO NOTHING;
   ```
   Nếu conflict DB → skip (duplicate webhook trễ).
6. **Map Provider Status → Standardized Status** — Tra [Provider Mapping Table](shipping_state_machine.md#2-provider-status-mapping-table).
7. **Validate FSM Transition** — Check transition hợp lệ theo [Shipment FSM](shipping_state_machine.md#1-shipment-fsm).
8. **Update PostgreSQL (Transaction) + CDC Outbox:**
   ```sql
   BEGIN;
   UPDATE shipments SET standardized_status = :new_status, 
       provider_status = :provider_status, version = version + 1
   WHERE id = :shipment_id AND version = :expected_version;
   -- Phát qua Debezium:
   INSERT INTO shipping_outbox (..., event_type='SHIPMENT_STATUS_UPDATED', partition_key=:order_id);
   COMMIT;
   ```
9. **Write ScyllaDB Event Log (Async Write-Behind):**
   ScyllaDB là DB thứ 2, không nên chặn ngang luồng Postgres TX. Đẩy message thông tin write vào **In-Memory Bounded Queue** hoặc ThreadPool (Async Write-Behind) để ném vào ScyllaDB nhằm tránh rủi ro Connection Timeout của NoSQL DB kéo sập khả năng xử lý Webhook.
   ```
   -- Chạy Async Thread:
   INSERT INTO shipment_events (shipment_id, event_timestamp, provider_status, 
     standardized_status, raw_payload, location) VALUES (...);
   ```
10. **Produce specialized events (nếu cần):**
    - `new_status = DELIVERED` → thêm outbox `SHIPMENT_DELIVERED`.
    - `new_status = RETURNED` → thêm outbox `SHIPMENT_RETURNED`.

---

## 3. Rate Card Sync Worker (CronJob)
**Loại hình:** CronJob chạy hàng ngày (VD: 2:00 AM).

**Sứ mệnh:** Đồng bộ bảng giá vận chuyển mới nhất từ 3PL API vào DB + invalidate cache.

**Chi tiết luồng hoạt động:**
1. Duyệt qua từng `shipping_providers` active:
   ```sql
   SELECT * FROM shipping_providers WHERE is_active = true;
   ```
2. Gọi 3PL API lấy rate cards:
   - GHN: `GET /shiip/public-api/v2/shipping-order/fee`
   - NinjaVan: `GET /v1/rates`
3. Compare với `rate_cards` hiện tại:
   - Giá thay đổi → UPDATE DB.
   - Rate card mới → INSERT.
   - Rate card bị xóa → Soft delete (deactivate).
4. Invalidate Redis cache: `DEL shipping:rates:{provider_code}:*`.
5. Log changes cho audit: `INSERT INTO rate_card_change_log (...)`.

---

## 4. Stale Shipment Detector (CronJob)
**Loại hình:** CronJob chạy mỗi 2 giờ.

**Sứ mệnh:** Phát hiện vận đơn "mất tích" — quá lâu không nhận tracking update từ 3PL.

```sql
SELECT s.id, s.tracking_number, s.courier_code, s.standardized_status, s.updated_at
FROM shipments s
WHERE s.standardized_status IN ('READY_TO_SHIP', 'IN_TRANSIT')
  AND s.updated_at < NOW() - INTERVAL '48 hours'
LIMIT 100;
```

**Xử lý:**
1. Gọi 3PL tracking API proactively:
   - GHN: `GET /shiip/public-api/v2/shipping-order/detail`
   - NinjaVan: `GET /v1/orders/tracking/{tracking_id}`
2. Nếu 3PL trả trạng thái mới → Trigger update luồng (giống webhook ingest).
3. Nếu 3PL trả "not found" hoặc stale:
   - Alert ops team (giữ nguyên logic gốc).
   - **[MỚI] Phát event `SHIPMENT_DELAYED`:**
       - Mốc 48h: kiểm tra `SETNX shipping:delayed:sent:{shipment_id}:L1` TTL 7 ngày.
       - Nếu SETNX `:L1` thành công (chưa gửi level 1) → INSERT `shipping_outbox` với `event_type = 'SHIPMENT_DELAYED'`, `escalation_level = 1`.
       - Mốc 72h: kiểm tra `SETNX shipping:delayed:sent:{shipment_id}:L2` TTL 7 ngày.
       - Nếu SETNX `:L2` thành công → gửi level 2 đúng 1 lần.
     - Debezium tự động forward thẳng lên Topic Kafka `t.shipping.events`.
   - Xem event payload tại [SHIPMENT_DELAYED](shipping_events.md#36-shipment_delayed).

---

## 5. Old Outbox Trimmer (CronJob hàng ngày)
Dọn rác CDC qua mốc Watermark an toàn:
```sql
DELETE FROM shipping_outbox 
WHERE created_at < NOW() - INTERVAL '15 days';
```

## 6. Dead Letter Hook tại Kafka Connect
Retry/DLQ cảnh báo cấu hình ở Debezium Connect Cluster (Gửi alert thông qua Dead Letter Topic), không check logic DB nữa.

## 7. ScyllaDB TTL Cleanup
ScyllaDB `shipment_events` table tự động TTL sau 2 năm (set TTL khi write). Không cần CronJob.
