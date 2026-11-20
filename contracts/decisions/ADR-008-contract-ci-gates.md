# ADR-008: Cổng CI cho contract và test service

- Status: Accepted
- Date: 2026-10-15

## Context

Khảo sát 2026-10-15 cho thấy CI không bảo vệ contract như tài liệu nói:

| Hạng mục | Hiện trạng |
|---|---|
| Lint ngữ nghĩa | `validate-contracts.ps1` chỉ cảnh báo khi thiếu `buf`, `redocly`, `asyncapi`; workflow không bật `-StrictTools` nên cả ba chưa bao giờ chạy trong CI. Danh sách spec của `redocly` và `asyncapi` viết tay (8 trong 21 OpenAPI, 3 trong 11 AsyncAPI). |
| Breaking change | `buf.yaml` đặt `breaking: FILE` nhưng không có lệnh nào chạy `buf breaking`. OpenAPI không có công cụ so sánh nào. [ADOPTION.md](../ADOPTION.md) lại đòi "CI đã có breaking-change check so với `main`" để lên `verified`. |
| Phạm vi | Workflow chỉ chạy khi `contracts/**` đổi. Không workflow nào chạy test của service, nên drift test (`ProtoCopyDriftTest`, `pb_drift_test.go`) và test fixture của consumer chỉ chạy khi có người nhớ chạy. |

Khi chạy thử ngay trong lần khảo sát này, các công cụ tìm ra hai lỗi OpenAPI cũ (`user/v1` thiếu `servers`, `product-detail-bff/v1`
thiếu `security`) và một file hỏng do chính một commit gần đây (`payment/v1` có 401/403 nằm nhầm chỗ dưới `parameters:`, không còn là YAML hợp lệ).
Không có cổng nào trong CI để chặn cả ba.

## Decision

1. **Lint bắt buộc.** Workflow chạy `validate-contracts.ps1 -StrictTools` với `buf`, `redocly` và `asyncapi` được cài (`npm ci` từ
   `contracts/tools/package-lock.json`, `buf` cố định 1.47.2). Script tự tìm mọi `openapi.yaml` và `asyncapi.yaml` thay vì dùng danh sách tay;
   OpenAPI dùng `contracts/tools/redocly.yaml`.
2. **Cổng breaking change** `contracts/tools/check-breaking.sh BASE_REF`, chạy bằng image cố định (`bufbuild/buf:1.47.2`,
   `tufin/oasdiff:v1.11.7`) để kết quả giống nhau ở CI và máy dev:
   - Protobuf: `buf breaking` với quy tắc `FILE` của `buf.yaml`.
   - OpenAPI: `oasdiff breaking --fail-on ERR` cho mọi spec có ở cả hai bên; spec bị xoá là breaking; spec không so sánh được
     (ví dụ file gốc hỏng) là lỗi, không bỏ qua.
   - Spec giống hệt bản gốc bị bỏ qua để cổng nhanh.
3. **Base so sánh.** Pull request: `origin/<nhánh đích>`. Push vào `main`: commit trước (`github.event.before`). Không có base (push đầu tiên) thì cổng
   bỏ qua và nói rõ.
4. **Ngoại lệ.** Thay đổi breaking có chủ đích mở major version mới ([compatibility.md](../conventions/compatibility.md)). Cách duy nhất khác là một dòng trong
   `contracts/tools/breaking-waivers.txt` ghi file và ADR phê duyệt; reviewer thấy nó trong diff.
5. **Cổng tự kiểm.** `contracts/tools/tests/test_check_breaking.sh` chứng minh cổng chặn đổi tên path, xoá contract, xoá field protobuf, đổi kiểu field, và cho qua
   comment, thêm field protobuf, contract giống hệt; CI chạy nó cùng cổng.
6. **Test service trong CI.** `.github/workflows/services.yml` chọn service bằng `scripts/ci/changed-services.sh` (thư mục service đổi, hoặc `contracts/`,
   `scripts/ci/`, workflow đổi thì chọn tất cả) rồi chạy `mvn verify` (Java, kèm integration test dùng Testcontainers) hoặc `go test ./...` (Go).
   Script chọn có test riêng (`scripts/ci/tests/test_changed_services.sh`).
7. **Chưa phủ.** Tương thích của event (AsyncAPI, JSON Schema) chưa có công cụ so sánh; nó vẫn dựa vào review và contract test của producer/consumer.
   Ghi nhận ở [ADOPTION.md](../ADOPTION.md).

## Alternatives considered

- **GitHub Action của buf và oasdiff.** Không chọn: ràng buộc vào runner, không chạy được trên máy dev. Image cố định chạy giống nhau ở cả hai nơi.
- **Chỉ bật `-StrictTools`, không có cổng breaking.** Không chọn: lint không phát hiện field bị xoá.
- **Chạy toàn bộ test mọi lần.** Không chọn: 14 service, nhiều service có integration test với Testcontainers; chỉ chạy service bị ảnh hưởng, và chạy tất cả
  khi contract đổi.
- **Một waiver theo từng thay đổi trong PR.** Không chọn: phức tạp hơn mức cần; waiver theo file cùng ADR đủ rõ.

## Consequences

- PR đổi contract chậm hơn khoảng một phút (kéo image, chạy so sánh).
- PR đổi contract luôn chạy test của cả 14 service. Đây là chi phí có chủ đích: contract là bề mặt chung.
- Lint ngữ nghĩa bắt đầu chặn merge: 21 OpenAPI, 11 AsyncAPI và proto đều đã sạch lỗi tại thời điểm này (57 cảnh báo `tag-description` của redocly vẫn là cảnh báo).
- Chuyển một contract từ `implemented` lên `verified` nay có thể trích cổng breaking làm bằng chứng cho yêu cầu CI.

## Verification

- `check-breaking.sh` và `test_check_breaking.sh` chạy với 7 trường hợp (3 cho qua, 4 bị chặn) trên máy dev bằng Docker.
- `redocly lint` toàn bộ 21 OpenAPI: 0 lỗi; `asyncapi validate` toàn bộ 11 tài liệu: 0 lỗi; `buf lint`: sạch; `validate-contracts.ps1` chạy trong PowerShell 7.
- Cổng đã phát hiện file `payment/v1/openapi.yaml` hỏng ở commit gần nhất (không so sánh được với base).
- Workflow chưa chạy trên GitHub (không có runner); `services.yml` được kiểm bằng cách chạy từng lệnh của nó trên máy dev.
