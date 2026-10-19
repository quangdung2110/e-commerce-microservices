---
doc_id: inventory-adr-032
service: inventory
status: accepted
date: 2026-10-19
decision_owners:
  - Inventory Service team
supersedes: null
evidence:
  - docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md
  - docs/performance/inventory-reserve-hot-sku-evidence-2026-10-19/alert-check.txt
  - infra/observability/rules.yml
  - infra/observability/rules-test.yml
---

# ADR-032 — Alert bão hoà cho Reserve, không chỉ dựa vào tỉ lệ `INVENTORY_BUSY`

## Status

`Accepted`. Bổ sung dòng `INVENTORY_BUSY` / latency của bảng alert trong [operations.md §2](../operations.md#2-service-objectives-and-alerts).
Không đổi contract, code hay hành vi của service.

## Context

[operations.md §2](../operations.md#2-service-objectives-and-alerts) có trigger "`503 INVENTORY_BUSY` > 1% of mutation
attempts for 5 min, or latency breach for 10 min". Tuy vậy, [`infra/observability/rules.yml`](../../../infra/observability/rules.yml)
không có rule nào cho trigger này. Inventory chỉ được giám sát bằng các alert chung của mọi backend. Trong đó
`HighBackendLatency` tính p95 trên **mọi route** của service, với ngưỡng 2 s.

[Benchmark baseline SKU nóng](../../performance/inventory-reserve-hot-sku-baseline-2026-10-19.md) cho thấy hai trigger
kia không bắt được dạng bão hoà dễ xảy ra nhất là nhiều đơn cùng tranh một hàng `stock_position`:

- **Tỉ lệ busy luôn bằng 0.** Ở 300–600 Reserve/s cho một SKU, p50 là 1,6–7 s, nhưng có **0** `INVENTORY_BUSY`, 0
  `INVENTORY_UNAVAILABLE`, 0 deadlock. Pool Hikari 24 connection giới hạn số transaction chờ khoá ở mức 23, nên mỗi lần
  chờ chỉ khoảng 80 ms, còn xa `lock_timeout` 2 s. Request xếp hàng ở Hikari (trung bình 129–154 request chờ, tối đa
  176), không ở database, và không bị từ chối.
- **Alert latency chung bị pha loãng.** Product Detail BFF gọi `sku-availabilities:batch-get` ở mỗi lượt xem trang sản
  phẩm, nên lượng lệnh đọc này lớn hơn Reserve nhiều lần và chạy nhanh. p95 của cả service vì vậy có thể vẫn dưới 2 s
  trong khi mọi Reserve đều chậm hơn 1 s. Unit test của rule tái hiện đúng tình huống này: 19 800 Reserve chậm và
  400 000 lệnh đọc nhanh mỗi phút.
- **SKU nóng làm chậm cả SKU khác.** Khi pool bị chiếm hết, đơn của SKU khác cũng phải chờ (p50 3,9 s ở kịch bản trộn
  50/50, 600/s). Theo suy luận, lệnh đọc availability dùng chung pool này nên cũng bị chậm theo; điều này chưa được đo.

## Decision

Thêm vào `infra/observability/rules.yml` ba recording rule và ba alert, cùng unit test promtool trong `rules-test.yml`:

| Alert | Biểu thức (rút gọn) | `for` | Severity | Bắt được gì |
|---|---|---:|---|---|
| `InventoryReserveLatencySloBreach` | p95 > 200 ms **hoặc** p99 > 500 ms trên route `POST /internal/inventory/v1/reservations` (cửa sổ 5 phút), khi có > 0,1 Reserve/s | 10m | critical | Vi phạm SLO Reserve, bất kể nguyên nhân. Đây là phần "latency breach for 10 min" của trigger cũ, nay đo theo route. |
| `InventoryDbPoolSaturated` | `avg_over_time(hikaricp_connections_pending[2m]) > 10`, tính trên từng instance | 3m | warning | Tín hiệu sớm của hàng đợi connection, khi SKU nóng hoặc primary chậm giữ connection |
| `InventoryCommandBusy` | `inventory_commands_total{result="busy"}` / mọi command > 1% (5 phút), khi có > 0,1 command/s | 5m | critical | Phần `INVENTORY_BUSY` của trigger cũ: lock timeout, deadlock, serialization sau hai lần retry nội bộ |

**Ngưỡng 10 request chờ connection** nằm giữa hai nhóm số đo của benchmark:
- Mọi bậc đạt SLO đều có trung bình ≤ 1,5. Từng mẫu lẻ có lúc lên tới 28, nên ngưỡng tính trên trung bình 2 phút chứ
  không tính trên từng mẫu.
- Mọi bậc bão hoà đều có trung bình từ 61 đến 154.

Ngưỡng latency là SLO đã chốt trong operations.md, không phải số tự chọn.

Hai alert latency và pool dùng nhãn `owner: backend`, giống các alert backend hiện có. Alertmanager local chưa định
tuyến theo owner. Khi cấu hình receiver thật thì định tuyến về "Inventory + Platform" như bảng trong operations.md.

## Alternatives considered

- **Hạ `lock_timeout` hoặc giảm pool để lỗi lộ ra thành `INVENTORY_BUSY`.** Không chọn. Cách này biến hàng đợi thành
  lỗi `503`. Temporal và vòng `untilKnown` của Order sẽ retry, làm tải lên hàng nóng tăng thêm. Đổi hành vi để alert
  có tín hiệu là sai thứ tự.
- **Chỉ hạ ngưỡng của `HighBackendLatency`.** Không chọn. Alert này vẫn tính trên mọi route nên vẫn bị lệnh đọc
  availability pha loãng, và hạ ngưỡng sẽ ảnh hưởng mọi service khác.
- **Alert theo số backend PostgreSQL đang chờ `Lock`** (qua `postgres-exporter`). Đây là tín hiệu trực tiếp nhất, nhưng
  exporter đo cả instance PostgreSQL dùng chung và không gắn được với service. Có thể bổ sung sau khi Inventory có
  primary riêng; hiện để trong runbook như một bước chẩn đoán.
- **Metric thời gian chờ khoá trong ứng dụng.** Hoãn lại. Cần sửa code đường ghi, trong khi metric hiện có đã đủ phát
  hiện bão hoà.

## Verification on a live scrape

Ngày 2026-10-19, stack `inventory-bench` chạy cùng Prometheus v3.15.0 nạp đúng `rules.yml`. Bơm 400 Reserve/s vào một
SKU trong 13 phút, tải bắt đầu khoảng 18:30:30:

- `InventoryDbPoolSaturated` chuyển pending lúc 18:31:28 và **firing lúc 18:34:28**. Số request chờ connection đạt
  176, trần của 200 thread Tomcat.
- `InventoryReserveLatencySloBreach` chuyển pending lúc 18:31:28 và **firing lúc 18:41:28**.
- Cả hai tắt sau khi ngừng tải.
- `HighBackendLatency` **không** chuyển cả sang pending, dù khi đó 100% traffic là Reserve.

Trong lượt này, 2 trên 217 461 request trả `INVENTORY_UNAVAILABLE` vì hết `connection-timeout` 3 s của Hikari. Không
có `INVENTORY_BUSY` nào.

Ngay sau đó bơm 200 Reserve/s trong 13 phút, mức vẫn đạt SLO: p99 phía server tối đa 19 ms, không request nào chờ
connection, **không alert nào pending hay firing** (alert latency của lượt trước tắt lúc 18:48:28). Chi tiết ở
[alert-check.txt](../../performance/inventory-reserve-hot-sku-evidence-2026-10-19/alert-check.txt).

Lý do `HighBackendLatency` im lặng: **histogram phía server chỉ thấy một phần hàng đợi.** k6 đo p50 7 s, nhưng
`http_server_requests_seconds` chỉ ghi p95 tối đa 1,17 s và p99 tối đa 1,88 s. Request phải chờ một thread Tomcat, mà
200 thread đều đang bận chờ Hikari, trước khi bộ đếm của `http.server.requests` bắt đầu tính giờ. Vì vậy:

- Ngưỡng latency đo phía server là **cận dưới** của độ trễ mà Order thấy. Ngưỡng 2 s của alert chung không bao giờ
  chạm được trong một đợt bão hoà kiểu này.
- Ngưỡng SLO 200/500 ms vẫn bắt được, vì phần thời gian chờ Hikari nằm trong bộ đếm.
- Alert pool không phụ thuộc vào bộ đếm thời gian, nên là tín hiệu chắc chắn hơn.

## Consequences

- Một đợt flash sale trên một SKU sẽ có cảnh báo sớm qua pool sau khoảng 5 phút, và cảnh báo SLO sau khoảng 10 phút.
  Trước đây không có alert nào.
- `InventoryCommandBusy` có thể không bao giờ kêu khi Inventory chỉ chạy một replica. Nó vẫn có ích khi nhiều replica
  cùng dồn connection vào một hàng, hoặc khi gặp deadlock thật.
- Runbook ([docs/observability/runbook.md](../../observability/runbook.md)) có mục xử lý cho từng alert, gồm truy vấn
  tìm SKU nóng. Phản ứng không được bỏ qua bước kiểm tra tồn kho. Thêm replica không giúp được khi chỉ có một hàng nóng.

## Migration impact

Không có thay đổi code, migration hay contract. Prometheus nạp lại `rules.yml` khi restart. Test:
`promtool test rules rules-test.yml` (Prometheus v3.15.0) gồm ca bão hoà phải kêu, ca đạt SLO không được kêu, và ca
pha loãng mà `HighBackendLatency` không kêu. Kiểm tra kiểu mutation đã xác nhận: làm hỏng mỗi ngưỡng thì test fail.
