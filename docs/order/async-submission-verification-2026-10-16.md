# Kiểm chứng async checkout submission — 2026-10-16

Thiết kế: [ADR-027](./decisions/ADR-027-async-submission-checkpoint-waiters.md).
Chạy trên working tree bằng Docker Desktop, Maven 3.9/Java 21, PostgreSQL 16 Testcontainers,
Temporal in-memory; Checkout dùng Redis Testcontainers và upstream contract fakes.
Không restart/deploy service hay chạy migration lên database của stack người dùng.

## Kết quả cuối cùng

| Kiểm tra | Kết quả |
|---|---|
| Toàn bộ Order `mvn -B test -DargLine=-Dapi.version=1.44` | 97 test, 0 failure, 0 error, 0 skip |
| Toàn bộ Checkout `mvn -B test` | 164 test, 0 failure, 0 error, 1 skip (`LivePromotionIdleTest`, chỉ chạy khi bật live probe) |
| `contracts/tools/validate-contracts.ps1` | Pass structural/catalog/reference/link checks; optional `buf`/`asyncapi` không có trong PATH, PowerShell JSON Schema examples có cảnh báo `$id` tuyệt đối |
| Redocly lint Order internal OpenAPI với config repository | Pass; dùng CLI local tại `contracts/tools/node_modules`, `CI=true`, `REDOCLY_TELEMETRY=off` |
| `contracts/tools/check_http_contracts.py` | 21 OpenAPI, 0 violation (PyYAML trong container Python riêng, repo mount read-only) |
| Local Markdown links ở 20 tài liệu liên quan | 0 broken links, gồm trang bằng chứng này |
| `git diff --check` trên phạm vi thay đổi | Pass |

Surefire XML/text được tạo trong `java/services/{order-service,checkout-service}/target/surefire-reports`.
Lần chạy Order cuối đã bao gồm sửa assertion V1→V3 migration, concurrency-safe Mockito setup và
cleanup khi container không dispatch được completed response.

## Những hành vi đã kiểm tra

- Initial read sau register xử lý checkpoint đã commit trước đó; không cần notification mới để replay.
- Hai request cùng token giữ `201/200` riêng; duplicate signals không dựng response hai lần.
- Trong `PREPARING`, không có vòng poll mỗi request; deadline trả `503 SUBMISSION_IN_PROGRESS`
  và `Retry-After: 2`, waiter capacity được trả lại.
- Capacity đầy trả `503 ORDER_UNAVAILABLE`; shared recovery query chia batch theo giới hạn.
- Context/deadline/MDC gốc tồn tại khi response executor gọi Payment và không leak sang request sau.
- Payment chậm không chặn response khác; caller deadline vẫn giới hạn response đã bắt đầu lấy Payment.
- Notification PostgreSQL chỉ thấy sau commit; rollback/CAS no-op không phát.
- Hai registry/listener độc lập cùng nhận checkpoint trên một PostgreSQL, không dựa periodic sweep.
- Reconnect đọc lại thay đổi commit lúc listener disconnected; chỉ terminate đúng DB session do test tạo.
- MockMvc trả initial async dispatch trước response, rồi dispatch kết quả/exception qua MVC như production.
- ASYNC filter giữ receivedAt/request ID ban đầu, chỉ đếm deadline exceeded tại final dispatch.
- Nâng schema V1→V3 giữ historical delivery và submission terminal, không tạo outbox ngoài ý muốn.
- Checkout recovery hiện có nhận `PREPARING`/`RESULT_UNKNOWN`, đọc Payment data tươi và không tạo Order mới
  khi response trước bị mất; happy path/cancel/refund và response schema trong toàn suite vẫn pass.

## Tái chạy

Từ repo root trong PowerShell, Order:

```powershell
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}:/repo" -v order-mvn-repo:/root/.m2 --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true -w /repo/java/services/order-service maven:3.9-eclipse-temurin-21 mvn -B test '-DargLine=-Dapi.version=1.44'
```

Checkout dùng cùng lệnh, đổi volume thành `checkout-mvn-repo`, working directory thành
`/repo/java/services/checkout-service`, và Maven arguments thành `mvn -B test`.

Đây là kiểm chứng chức năng trên dependencies test, chưa là benchmark throughput hoặc HA của
cluster thật. Chưa đo tăng RPS, p95/p99 trước/sau hoặc Stripe live; bounds waiter/executor vẫn
cần load test đường ghi theo [performance plan](../performance/load-test.md).
