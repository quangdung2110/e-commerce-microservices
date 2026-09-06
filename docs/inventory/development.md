---
doc_id: inventory-development
service: inventory
scope: current
status: proposed
owners:
  - Inventory Service team
evidence:
  - java/services/inventory-service/pom.xml
  - java/services/inventory-service/src/main/resources/application-dev.yml
  - java/services/inventory-service/README.md
verified_against_commit: null
---

# Inventory Service — Development Guide

> File này phục vụ việc phát triển và kiểm thử service. Nó không thay thế tài liệu
> [Architecture](./architecture.md) hoặc [Operations](./operations.md).
>
> Các command và giá trị dưới đây lấy từ `pom.xml`, `application-dev.yml` và
> [README của service](../../java/services/inventory-service/README.md). Chúng **chưa
> được chạy thử** trong lần viết tài liệu này; chỗ nào chưa có nguồn thì ghi `TBD`
> thay vì suy đoán.

## 1. Prerequisites

| Công cụ | Phiên bản | Ghi chú |
|---|---|---|
| JDK | 21 | `java.version` trong `pom.xml` |
| Maven | Dùng wrapper `./mvnw` | Không cần cài Maven riêng |
| Docker | Bất kỳ bản còn hỗ trợ | Cần cho hạ tầng local và cho Testcontainers |
| PostgreSQL | 16+ | Chạy qua container |
| Redis | 7.x, chế độ cluster | Xem cảnh báo ở §3 |
| Kafka | 3.7 | Kèm Kafka Connect nếu muốn thử CDC |
| Temporal | Server 1.x | Chỉ cần khi thử Activity |

Nếu máy không có JDK, build được qua container Maven; đây là cách đang dùng trên môi
trường phát triển hiện tại.

## 2. Chạy local

```bash
cd java/services/inventory-service
./mvnw -B -ntp -DskipTests package
```

```bash
cd java/services/inventory-service
./mvnw spring-boot:run
```

Profile mặc định là `dev`. Service lắng nghe cổng **8083**.

Kiểm tra health:

```bash
curl -s http://localhost:8083/actuator/health/readiness
```

Actuator expose `health`, `info` và `metrics`; probe liveness và readiness được bật
sẵn.

Chạy bằng Docker:

```bash
cd java/services/inventory-service && docker build -t inventory-service:dev .
```

Dọn dẹp: dừng process Spring Boot, rồi `docker compose down` cho hạ tầng. Xóa volume
chỉ khi muốn mất dữ liệu local — Flyway sẽ chạy lại từ đầu ở lần khởi động sau.

## 3. Local dependencies

`application-dev.yml` trỏ tới các cổng sau:

| Dependency | Endpoint dev | Ghi chú |
|---|---|---|
| PostgreSQL | `localhost:15432`, database `inventory_db` | User `inventory` |
| Redis | `localhost:16379` | Cấu hình dạng cluster với một node |
| Kafka | `localhost:9092` | |
| Temporal | `localhost:7233` | |

**Cảnh báo quan trọng:** `docker-compose.yml` ở gốc repo **không có** service
`inventory-service`, và Postgres/Redis trong đó dùng cổng khác với `application-dev.yml`
(`5432`/`6379` thay vì `15432`/`16379`). Muốn chạy local phải hoặc dựng hạ tầng riêng
theo đúng cổng trên, hoặc chỉnh cấu hình cho khớp compose. Cách nào là chuẩn thì `TBD`
— cần thống nhất rồi bổ sung service vào compose.

**Cảnh báo về Redis:** [ADR-004](./decisions/ADR-004-per-warehouse-hash-tag-layout.md)
dựa vào việc mọi key mà Lua script chạm tới nằm cùng hash slot. Redis đơn node **không
kiểm tra** ràng buộc đó, nên một script sai hash tag vẫn chạy bình thường ở local rồi
hỏng ở production. Hãy chạy Redis ở chế độ cluster khi phát triển bất cứ thứ gì đụng
tới Lua script.

Dependency có thể mock khi phát triển: Temporal (nếu chỉ làm HTTP API), Kafka Connect
(nếu chưa thử luồng CDC).

## 4. Local configuration

| File | Vai trò |
|---|---|
| `application.yml` | Cấu hình chung: cổng, actuator, Jackson, metrics |
| `application-dev.yml` | Endpoint local, Flyway bật, gồm cả `db/dev-migration` |
| `application-test.yml` | Dùng cho test, thường trỏ tới Testcontainers |
| `application-prod.yml` | Cấu hình production |

Ở profile `dev`, Flyway chạy cả `classpath:db/migration` lẫn `classpath:db/dev-migration`,
nên seed kho dev được áp dụng tự động. Ở `prod`, **chỉ** `db/migration` được chạy —
đừng đặt dữ liệu cần thiết cho production vào `dev-migration`.

`hibernate.ddl-auto` là `validate`: entity lệch schema sẽ làm service không khởi động
được. Đây là chủ đích — Flyway là nguồn chân lý của schema, Hibernate không được tự sửa.

Mật khẩu trong `application-dev.yml` là giá trị dev, không dùng ở bất kỳ môi trường nào
khác. Không commit credential thật, endpoint production hay bất kỳ giá trị secret nào.

## 5. Build, lint và format

```bash
cd java/services/inventory-service && ./mvnw -B -ntp test
```

```bash
cd java/services/inventory-service && ./mvnw -B -ntp -DskipTests package
```

Build thành công nghĩa là biên dịch xong, mọi test pass, và ArchUnit không phát hiện vi
phạm ràng buộc kiến trúc. Cấu hình lint/format riêng: `TBD` — chưa xác định được từ
`pom.xml`.

## 6. Tests

| Loại | Cách chạy | Dependency |
|---|---|---|
| Toàn bộ | `./mvnw -B -ntp test` | Docker (Testcontainers tự khởi động) |
| Một class | `./mvnw -B -ntp test -Dtest=TênClass` | như trên |
| Unit | Nằm chung trong suite | Không cần hạ tầng |
| Integration | Nằm chung trong suite | Testcontainers: PostgreSQL, Redis Cluster, Kafka |
| Kiến trúc | ArchUnit, chạy như test thường | Không cần hạ tầng |

Testcontainers cần Docker đang chạy. Lần chạy đầu tải image nên lâu hơn đáng kể.

Contract test cho OpenAPI và AsyncAPI: **chưa có**. Đây là điều kiện bắt buộc để bất kỳ
contract nào chuyển sang `verified` — xem [Interfaces §6](./interfaces.md#6-compatibility).

Kịch bản test bắt buộc theo từng workflow nằm ở [Workflows](./workflows.md); mỗi
workflow có mục riêng liệt kê happy path, biên, replay, timeout và recovery.

## 7. Contract code generation

Hiện **không có** bước generate code từ OpenAPI, AsyncAPI hay Protobuf: DTO được viết
tay trong `dto/request` và `dto/response`.

Hệ quả cần biết: [OpenAPI v1](../../contracts/http/inventory/v1/openapi.yaml) được viết
**theo** implementation chứ không sinh ra implementation, nên hai bên có thể trôi xa
nhau mà không có gì phát hiện. Một contract test so DTO với spec là cách rẻ nhất để
đóng khoảng trống này.

## 8. Database migration

Migration nằm ở hai thư mục:

| Thư mục | Chạy ở profile | Nội dung |
|---|---|---|
| `src/main/resources/db/migration` | Mọi profile | `V1__init_schema.sql`, `V3__partial_unique_outbox_return_received.sql` |
| `src/main/resources/db/dev-migration` | Chỉ `dev` | `V2__seed_dev_warehouse.sql` |

Tạo migration mới:

1. Kiểm tra số version cao nhất ở **cả hai** thư mục — chúng dùng chung không gian
   version, và đó là lý do đánh số nhảy từ V1 sang V3.
2. Đặt tên `V<n>__mo_ta_ngan.sql` trong `db/migration` (hoặc `db/dev-migration` nếu chỉ
   dành cho dev).
3. Viết migration **additive**: thêm cột nullable, thêm index. Tránh thay đổi phá vỡ
   bản đang chạy, vì rollout không dừng service.
4. Chạy `./mvnw spring-boot:run` để Flyway áp dụng, hoặc chạy test để Testcontainers áp dụng.
5. Cập nhật [Data](./data.md) nếu constraint hoặc index thay đổi.

Flyway không hỗ trợ rollback ở bản community; muốn lùi phải viết một migration mới đảo
ngược thay đổi. `baseline-on-migrate` đang bật ở dev.

Đổi entity mà quên migration sẽ bị `ddl-auto: validate` chặn ngay lúc khởi động —
lỗi hiện ra dưới dạng schema validation, không phải lỗi runtime.

## 9. Test data và fixtures

`V2__seed_dev_warehouse.sql` tạo kho cho môi trường dev. Vì
[ADR-004](./decisions/ADR-004-per-warehouse-hash-tag-layout.md) yêu cầu mọi mutation
phải có kho, và `INV-BR-006` nói SKU chỉ tồn tại sau lần restock đầu, quy trình dựng dữ
liệu test là: có kho → gọi restock để tạo `(SKU, kho)` → mới reserve được.

Không dùng dữ liệu production trong fixture. Service không lưu PII nên rủi ro thấp,
nhưng `order_id` thật vẫn là dữ liệu nội bộ.

## 10. Debugging

**Xem tồn kho đang chạy trong Redis:**

```bash
docker exec -it <redis-container> redis-cli HGETALL "inventory:physic:{sku-001:5}:stock"
```

Dấu ngoặc nhọn là hash tag thật, không phải placeholder — phải giữ nguyên khi gõ lệnh.

**Xem stream chưa được forward:**

```bash
docker exec -it <redis-container> redis-cli XLEN "inventory:stream:{sku-001:5}:events"
```

**Kiểm tra một SKU có đang bị đóng băng không:**

```bash
docker exec -it <redis-container> redis-cli HGETALL "inventory:suspended:{sku-001:5}"
```

**Xem outbox chưa được CDC đọc:** truy vấn `inventory_outbox` sắp xếp theo `created_at`.

**Đối chiếu Redis với PostgreSQL:** so `total` trong stock hash với `actual_quantity`
trong `physical_inventory`. Lệch nhau là bình thường nếu consumer đang lag; lệch mà lag
bằng 0 là dấu hiệu drift.

**Thao tác an toàn khi debug:** đọc thoải mái. **Không** ghi trực tiếp vào stock hash
và **không** xóa entry khỏi stream, kể cả ở local nếu đang tái hiện một sự cố —
[ADR-002](./decisions/ADR-002-redis-stream-outbox.md) giải thích vì sao mỗi entry bị
xóa là một mutation vĩnh viễn không vào sổ cái.

Log level, debugger remote và cấu hình trace local: `TBD`.

## 11. Các lỗi local phổ biến

| Triệu chứng | Nguyên nhân thường gặp | Cách kiểm tra |
|---|---|---|
| Service không khởi động, lỗi schema validation | Entity lệch migration | So entity với `V1__init_schema.sql` |
| Không kết nối được database | Cổng dev là `15432`, không phải `5432` | Đối chiếu `application-dev.yml` với hạ tầng đang chạy |
| Không kết nối được Redis | Cổng dev là `16379`, không phải `6379` | như trên |
| Lỗi CROSSSLOT khi chạy Lua | Một key thiếu hash tag `{sku_id:warehouse_id}` | Kiểm tra bốn key trong script |
| Script chạy ở local nhưng hỏng ở cluster | Đang test trên Redis đơn node | Chuyển sang Redis cluster |
| Reserve trả `SKU_NOT_FOUND` | SKU chưa được restock lần nào | Gọi restock trước |
| Reserve trả `SKU_SUSPENDED` | Cờ suspension còn sót từ lần test drift trước | Kiểm tra key suspension; gọi API unlock |
| Test treo ở lần chạy đầu | Testcontainers đang tải image | Chờ, hoặc pull image trước |
| Event không tới Kafka | Relay Worker chưa chạy, hoặc Kafka chưa lên | `XLEN` trên stream để xem entry có tồn đọng không |
| Event không tới consumer dù outbox có dòng | Debezium connector chưa được đăng ký | Kiểm tra Kafka Connect và `src/main/resources/debezium` |

## 12. Definition of Done

Một thay đổi hoàn thành khi, tương ứng với phạm vi thay đổi:

- `./mvnw test` pass, gồm cả ArchUnit.
- Thay đổi schema có migration additive và [Data](./data.md) được cập nhật.
- Thay đổi interface được phản ánh trong contract machine-readable tương ứng, và
  [Interfaces](./interfaces.md) được cập nhật.
- Thay đổi hành vi được phản ánh trong [Workflows](./workflows.md) hoặc
  [Domain](./domain.md).
- Quyết định kiến trúc mới có ADR; ADR bị thay thế được đánh dấu `Superseded`.
- Backward compatibility được kiểm tra: consumer bản cũ vẫn xử lý được event bản mới.
- Thay đổi đụng tới Lua script đã được test trên Redis **cluster**, không chỉ đơn node.
- Frontmatter `status`, `evidence` và `verified_against_commit` của tài liệu liên quan
  được cập nhật.
