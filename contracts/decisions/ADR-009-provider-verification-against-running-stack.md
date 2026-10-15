# ADR-009: Kiểm tra provider thật theo contract trên stack đang chạy

- Status: Accepted
- Date: 2026-10-15

## Context

[ADOPTION.md](../ADOPTION.md) ghi lặp lại cùng một khoảng trống cho hầu hết contract HTTP: "chưa có consumer test chạy với provider thật". Hiện trạng
(khảo sát 2026-10-15):

- Consumer (Checkout, Product Detail BFF, Search, Order...) kiểm bằng **fixture** viết tay (`ProviderFixturesContractTest`...). Fixture được validate với OpenAPI
  nên chúng đúng contract, nhưng không ai chứng minh provider thật trả đúng thứ fixture mô tả.
- Provider kiểm response của mình với OpenAPI trong test riêng, ở những nơi test đó tồn tại; nhiều provider chưa có (Cart, User, Auth, Notification, Payment không
  có test validate response với schema), và mã lỗi `401`/`403`/`404` thường không được đối chiếu.
- Lệch giữa hai phía chỉ lộ ra ở E2E, khi đã quá muộn để chỉ ra phía nào sai.

Bản thử đầu tiên của công cụ dưới đây, chạy ngay trên stack local, đã phát hiện ba sai lệch contract ↔ provider mà không test nào bắt:

| Phát hiện | Sửa |
|---|---|
| Payment trả `401`/`403` với `code` `UNAUTHENTICATED`/`FORBIDDEN`, nhưng enum `code` của contract không có hai giá trị đó (hệ quả của [ADR-004](ADR-004-service-identity-allowlist.md)) | Thêm vào enum của `payment/v1` |
| Auth internal trả `problem+json` cho mọi lỗi, contract khai báo response lỗi không có body | Khai báo schema `Problem` dùng chung cho 8 response lỗi |
| Probe Inventory thiếu `X-Causation-Id` bị `400` | Lỗi của probe; contract đã nói rõ header bắt buộc |

## Decision

1. **Công cụ** `contracts/tools/verify_providers.py`: gọi provider thật đúng như consumer gọi (`X-Service-Identity` của consumer, `X-Request-Id`,
   `X-Correlation-Id`) và kiểm mỗi câu trả lời với OpenAPI của interaction trong `catalog/interactions.yaml`:
   - status phải được khai báo cho operation;
   - media type phải được khai báo cho status;
   - body phải hợp lệ với schema (JSON Schema 2020-12, `$ref` giải quyết qua nhiều file);
   - header response `required` phải có;
   - status phải nằm trong tập `expect` của probe.
2. **Probe** khai báo trong `contracts/tools/provider-probes.yaml`: chỉ đọc hoặc hỏi thuần tuý (preview, calculation), không ghi dữ liệu, an toàn trên stack dùng chung. Probe
   có thể `capture` giá trị (product_id, sku_id) để probe sau dùng. Mỗi provider có ít nhất một probe thành công hoặc 404 đúng dạng, và một cặp
   `401` (thiếu identity) / `403` (identity lạ) theo ADR-004.
3. **Chạy** trên stack đang chạy qua `scripts/ci/verify-providers.sh` (container Python gắn vào mạng compose để dùng tên service và cổng nội bộ). Workflow thủ công
   `provider-verification.yml` dựng stack rồi chạy nó.
4. **Stack phải được build từ code hiện tại.** Probe kiểm hành vi của image đang chạy; image cũ hơn code cho kết quả sai (xem Consequences).
5. **Phạm vi.** Interaction HTTP đồng bộ giữa service. Không phủ: gRPC (Product có test in-process của chính nó và drift test proto), Kafka/event, route công khai qua gateway, lệnh có
   side effect (reserve, payment, order submission); các loại đó vẫn dựa vào test của provider và consumer.
6. **Công cụ có test** (`contracts/tools/tests/test_verify_providers.py`): bắt response lệch schema, status không khai báo, media type sai, thiếu header, body ở nơi
   contract không cho, giải `$ref` qua file, chọn đường dẫn literal trước templated, capture, và provider không tới được.
7. Interaction nào đã có probe thì ghi trong `ADOPTION.md`; điều đó là bằng chứng cho điều kiện "provider thật vượt qua contract test" của `verified`, nhưng
   không tự động đổi trạng thái: `verified` còn đòi test retry/duplicate của consumer.

## Alternatives considered

- **Pact (consumer-driven contract).** Không chọn: cần broker hoặc file pact, đổi cách viết test của mọi consumer; OpenAPI đã là hợp đồng, chỉ thiếu bước kiểm
  provider thật.
- **Schemathesis / Dredd (sinh request từ OpenAPI).** Không chọn: sinh request ghi dữ liệu và cần dữ liệu hợp lệ cho từng operation; probe viết tay đủ nhỏ, an toàn
  và có chủ đích (đúng hành vi mà consumer dùng).
- **Chỉ làm provider test trong từng service.** Không chọn: cần viết lại ở 10 stack khác nhau, và không đo được lệch giữa image build thật với test.
- **Thêm vào pipeline `services.yml`.** Không chọn: cần dựng cả stack (Kafka, PostgreSQL, Temporal...) cho mỗi PR; chạy thủ công và theo lịch là đủ.

## Consequences

- Có một lệnh trả lời "provider thật còn khớp contract không" cho 39 probe trên 10 provider.
- Image build cũ cho kết quả sai: trên stack local hiện tại `auth-service` (build 29/9, trước commit thêm allowlist 9/10) không trả `401`/`403`, và hai probe tương ứng
  fail cho đến khi build lại image. Công cụ nói đúng điều đó: nó kiểm cái đang chạy.
- Thêm interaction vào `interactions.yaml` thì nên thêm probe; chưa có cổng tự động ép việc này.
- Probe chỉ đọc nên không bao giờ làm sai dữ liệu, nhưng cũng chưa kiểm các lệnh có side effect.

## Verification

- 27 test của `contracts/tools/tests` (gồm `test_verify_providers.py`) xanh.
- Chạy trên stack local: 37 probe đạt, 2 fail (Auth, image cũ) — xem Consequences.
- `workflow provider-verification.yml` chưa chạy trên GitHub; lệnh bên trong nó là các lệnh đã chạy ở máy dev.
