---
doc_id: search-development
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence: []
verified_against_commit: null
---

# Search Service — Development Guide

> File này phục vụ phát triển và kiểm thử. Các phiên bản dưới đây đọc từ
> [`pom.xml`](../../java/services/search-service/pom.xml) và
> [`docker-compose.yml`](../../java/services/search-service/docker-compose.yml);
> khi hai file đó đổi, file này phải đổi theo.

## 1. Prerequisites

| Thành phần | Phiên bản | Nguồn |
|---|---|---|
| JDK | 21 | `java.version` trong `pom.xml` |
| Spring Boot | 3.3.x | parent POM |
| Maven | 3.9+ | Chưa có Maven wrapper trong repo; dùng `mvn` cài sẵn |
| Docker và Docker Compose | Bản hiện hành | Chạy dependency local |
| Elasticsearch | 8.11.0 | Image trong compose |
| Kafka | Confluent 7.5.0 kèm ZooKeeper | Image trong compose |

## 2. Chạy local

```bash
docker compose -f java/services/search-service/docker-compose.yml up -d elasticsearch kafka zookeeper
```

```bash
curl -f http://localhost:9200/_cluster/health
```

Ở local, profile `dev` bật `search.bootstrap-indices=true`: service tự tạo
`products_v1`, `categories_v1`, `search_suggestions_v1`, `search_jobs_v1` từ
[`schema/`](./schema) rồi gắn alias khi khởi động. Ở mọi môi trường dùng chung, cờ này
phải để `false` và index được tạo có chủ đích từ chính các file đó — index rỗng với
`dynamic: strict` sẽ khiến mọi lệnh ghi thất bại rõ ràng thay vì sinh mapping sai.
Index nội bộ `search_jobs` luôn được tạo vì các job cần nó trước khi làm bất cứ việc gì
với vòng đời index.

```bash
mvn -f java/services/search-service/pom.xml spring-boot:run
```

```bash
curl -i http://localhost:8080/actuator/health/readiness
```

Dọn dẹp:

```bash
docker compose -f java/services/search-service/docker-compose.yml down -v
```

`down -v` xóa cả volume dữ liệu Elasticsearch; bỏ `-v` khi muốn giữ index giữa các
lần chạy.

## 3. Local dependencies

| Dependency | Port | Bắt buộc | Ghi chú |
|---|---|---|---|
| Elasticsearch | 9200 | Có | Single node, tắt xác thực ở local |
| Kafka | 9092 | Có cho luồng indexing | Có thể bỏ nếu chỉ phát triển đường đọc |
| ZooKeeper | 2181 | Có, do image Kafka yêu cầu | — |

Không có PostgreSQL và không có Redis. Product, Inventory và Review không cần chạy
local: dữ liệu vào Search bằng cách publish event thủ công lên Kafka, trừ khi bạn
đang phát triển luồng full reindex vốn cần stub export API của Product, Inventory
và Review.

## 4. Local configuration

Cấu hình local nằm trong profile Spring của service. Bắt buộc phải đặt: endpoint
Elasticsearch, bootstrap server Kafka, `group.id` của consumer và tên các alias.
Ở local, credential có thể để trống vì cả hai dependency chạy không xác thực; ở
mọi môi trường khác, credential phải đến từ biến môi trường hoặc secret manager.

Tuyệt đối không commit: credential Elasticsearch/Kafka, endpoint môi trường thật,
và bất kỳ dump dữ liệu nào lấy từ production.

## 5. Build, lint và format

```bash
mvn -f java/services/search-service/pom.xml clean verify
```

Lệnh này phải trùng với lệnh CI chạy. Build thành công nghĩa là biên dịch, unit
test và integration test đều pass. Khi thêm bước lint hoặc format, phải thêm vào
cùng lệnh này thay vì tạo quy trình riêng cho máy cá nhân.

## 6. Tests

| Loại | Phạm vi | Dependency |
|---|---|---|
| Unit | Mã hóa/giải mã cursor, transform Product CloudEvent v1, chuẩn hóa attribute token, dispatch theo `type` | Không |
| Integration | Consumer đến index đến API, guard revision, tombstone, category control reindex | Testcontainers Elasticsearch và Kafka |
| | Image Elasticsearch phải được build kèm plugin `analysis-icu`; cả ba mapping dùng `icu_tokenizer`/`icu_folding` nên thiếu plugin là lỗi ngay ở bước tạo index | `ImageFromDockerfile` trong `AbstractSearchIntegrationTest` |
| Job | Reindex ba pha, rebuild suggestion, recompute count, purge | Testcontainers |
| Contract | Product CloudEvents v1 (effective thumbnail) và Inventory stock snapshot/export v1 khớp kỳ vọng Search | Fixture chung với producer |

Test tích hợp không được mock Elasticsearch client: phần lớn rủi ro của service nằm
ở mapping, script và ngữ nghĩa truy vấn, mà mock không phát hiện được.

**Chạy test tích hợp từ trong container.** Nếu bạn chạy Maven trong container thay vì
trên host, Testcontainers cần ba thứ: mount `/var/run/docker.sock`, đặt
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` (nếu không, port map trỏ về
`localhost` của chính container Maven), và ghim `-Dapi.version=1.44` — docker-java mặc
định thương lượng API v1.32, thấp hơn `MinAPIVersion` 1.40 của Docker Engine 29 nên bị
trả `400` và Testcontainers báo "Could not find a valid Docker environment".

Danh sách kịch bản bắt buộc theo từng workflow nằm ở [Workflows](./workflows.md).

## 7. Contract validation và code generation

HTTP canonical nằm tại
[`contracts/http/search/v2/openapi.yaml`](../../contracts/http/search/v2/openapi.yaml).
Mọi thay đổi phải chạy:

```powershell
pwsh ./contracts/tools/validate-contracts.ps1
```

Search chưa generate server/client code từ OpenAPI. Trước khi provider migrate,
phải chốt generator, thư mục output, phiên bản tool và CI check generated output
không lệch contract; generated output không phải source of truth. Contract test
bắt buộc cover cursor fingerprint, SKU-first response, Problem Details, public
anonymous route và ADMIN reindex route.

## 8. Thay đổi index và mapping

Search không có database migration; tương đương của nó là vòng đời index:

1. Sửa file trong [`schema/`](./schema) và review như review migration.
2. Thêm field mới có thể tương thích ngược; đổi kiểu hoặc analyzer thì không.
3. Chạy [`WF-SEARCH-009`](./workflows.md) để build index version mới và cutover
   alias. Không dùng `PUT mapping` trên index đang phục vụ.
4. Rollback bằng alias swap ngược; giữ index cũ tối thiểu 24 giờ.

Ở local, cách nhanh nhất để đổi mapping là xóa index rồi tạo lại từ template và
replay event; không dùng cách này ở bất kỳ môi trường dùng chung nào.

## 9. Test data và fixtures

Fixture là event mẫu của Product, Inventory và Review, đặt cùng test và version
hóa trong repo. Bắt buộc có fixture thumbnail SKU/Product/null, Inventory snapshot
seed + event revision cũ/trùng + replay offset, và Review rating aggregate snapshot
seed + `review_revision` cũ/trùng + replay offset. Dữ liệu catalog mẫu phải là dữ liệu
bịa; không dùng export production. Index không chứa PII nên fixture cũng không được
đưa PII vào.

## 10. Debugging

- Bật log DEBUG cho package của service để thấy DSL truy vấn đã build.
- Khi kết quả sai, so sánh trực tiếp bằng cách chạy chính truy vấn đó lên
  Elasticsearch local rồi thu hẹp dần filter.
- Khi document sai, đọc `_source` của SKU đó và kiểm tra `catalog_revision`,
  `stock_revision`, `review_revision`, `*_indexed_at` và `last_*_event_id` để biết
  nguồn nào ghi sau cùng.
- Khi consumer im lặng, kiểm tra CloudEvents `type` có nằm trong allowlist không trước
  khi nghi ngờ lỗi kết nối; ignore và ACK là hành vi thiết kế.
- Đọc dữ liệu qua read alias, ghi thử qua write alias; đừng thao tác trực tiếp lên
  tên index versioned ngay cả ở local, để thói quen không rò rỉ lên môi trường
  thật.

## 11. Các lỗi local phổ biến

| Triệu chứng | Nguyên nhân thường gặp | Cách kiểm tra |
|---|---|---|
| Service không readiness | Elasticsearch chưa khỏe hoặc consumer chưa subscribe | `curl` cluster health, xem log khởi động |
| Mọi lệnh ghi lỗi mapping | Index chưa được tạo từ template, `dynamic: strict` | `GET <index>/_mapping` |
| Search luôn rỗng | Thiếu filter mặc định trong dữ liệu mẫu: sản phẩm chưa `ACTIVE` | Đọc `_source` một document |
| Event bị bỏ qua im lặng | CloudEvents `type` ngoài allowlist hoặc guard chặn | Metric ignored, đọc guard field |
| Kết quả phân trang lặp | Sort thiếu tie-break `sku_id asc` | Xem DSL đã build |
| Kết quả cũ | Chưa qua `refresh_interval` | Chờ, hoặc gọi refresh thủ công trong test |

## 12. Definition of Done

Một thay đổi hoàn thành khi:

- `mvn clean verify` pass, gồm cả test tích hợp.
- Đổi interface thì cập nhật [Interfaces](./interfaces.md) và contract
  machine-readable tương ứng khi đã tồn tại.
- Đổi mapping hoặc script thì cập nhật [`schema/`](./schema), [Data](./data.md) và
  quy trình reindex.
- Đổi hành vi thì cập nhật [Workflows](./workflows.md) và [Domain](./domain.md).
- Có quyết định kiến trúc thì thêm ADR trong [`decisions/`](./decisions/README.md).
- Kiểm tra tương thích ngược với document đang tồn tại trong index và với producer
  đang chạy.
- Cập nhật trạng thái tài liệu và `evidence` khi đã đối chiếu implementation.
