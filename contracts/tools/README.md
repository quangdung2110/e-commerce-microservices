# Contract Validation Tools

## Chạy ngay, không cần cài dependency

Từ repository root:

```powershell
pwsh ./contracts/tools/validate-contracts.ps1
```

Smoke validator kiểm tra:

- JSON có parse được không;
- OpenAPI/AsyncAPI có version marker hợp lệ;
- Protobuf có package versioned và language package options;
- service ID trong interaction catalog có tồn tại;
- đường dẫn contract/docs trong catalog có tồn tại;
- CloudEvents example có đủ field lõi;
- Markdown trong `contracts/` không có relative link bị gãy.

Các bước scan source bỏ qua `node_modules/` và `generated/`; dependency/bundle không
phải contract nguồn. JSON parser hỗ trợ empty object keys trong package lock, và bước
Markdown-link check chấp nhận file rỗng. Semantic linters vẫn cần chạy riêng hoặc
được tìm thấy trong `PATH`; smoke pass không xác nhận đầy đủ JSON Schema semantics.

## Auth → Review persistence contract test

`USER_DELETED` của Auth có fixture riêng và một test persistence cho Review. Test
kiểm tra `id` UUID (kể cả regex assertion) trong Auth common schema, rồi áp dụng
Review DDL vào database Docker tạm `review_contract_auth_user_deleted` và insert thật
receipt cùng privacy request từ cùng event ID. Database tạm luôn được drop;
`review_service` không bị động tới.

```powershell
pwsh ./contracts/tools/test-auth-user-deleted-to-review.ps1 -ValidateOnly
docker compose up -d postgres
pwsh ./contracts/tools/test-auth-user-deleted-to-review.ps1
```

Do Review chưa có runtime, test này chứng minh contract/persistence mapping chứ chưa
thay thế contract test Kafka consumer của implementation.

## Tool chuyên dụng

Smoke test không thay thế semantic linter. Khi cài các CLI sau, script sẽ tự chạy
chúng nếu command có trong `PATH`, trên **mọi** `openapi.yaml` và `asyncapi.yaml` tìm thấy:

- `buf`: lint Protobuf.
- `redocly`: lint OpenAPI (cấu hình `redocly.yaml`).
- `asyncapi`: validate AsyncAPI.

CI chạy strict để thiếu một tool là lỗi ([ADR-008](../decisions/ADR-008-contract-ci-gates.md)):

```powershell
cd contracts/tools; npm ci; $env:PATH = "$PWD/node_modules/.bin" + [IO.Path]::PathSeparator + $env:PATH
pwsh ./contracts/tools/validate-contracts.ps1 -StrictTools
```

## Cổng breaking change

```bash
contracts/tools/check-breaking.sh origin/main        # cần git và Docker
contracts/tools/tests/test_check_breaking.sh HEAD    # chứng minh cổng chặn và cho qua đúng
```

`check-breaking.sh` so sánh working tree với `BASE_REF`: `buf breaking` (quy tắc `FILE`) cho Protobuf và `oasdiff breaking --fail-on ERR` cho mọi
OpenAPI, bằng image cố định (`bufbuild/buf:1.47.2`, `tufin/oasdiff:v1.11.7`). Spec bị xoá hoặc không so sánh được là lỗi. Thay đổi breaking có chủ đích mở
major version mới; ngoại lệ duy nhất là một dòng trong `breaking-waivers.txt` ghi file và ADR phê duyệt. Event (AsyncAPI, JSON Schema) chưa được so sánh tự động.

## Kiểm tra provider thật

```bash
scripts/ci/verify-providers.sh                         # cần stack đang chạy (docker compose up) build từ code hiện tại
scripts/ci/verify-providers.sh --only product-detail-for-bff identity-auth-missing
```

`verify_providers.py` gọi provider như consumer gọi (header `X-Service-Identity` của consumer) rồi kiểm status, media type, body
và header `required` với OpenAPI của interaction. Probe nằm ở `provider-probes.yaml`: chỉ đọc hoặc hỏi thuần tuý, mỗi provider có
cặp `401`/`403` theo [ADR-004](../decisions/ADR-004-service-identity-allowlist.md). Chạy trong container gắn vào mạng compose
nên dùng được tên service. Image cũ hơn code cho kết quả sai: build lại service trước
([ADR-009](../decisions/ADR-009-provider-verification-against-running-stack.md)).

## Quy tắc ngữ nghĩa HTTP

```bash
pip install pyyaml
python contracts/tools/check_http_contracts.py     # mọi 429/503 khai báo Retry-After (ADR-005)
python -m unittest discover -s contracts/tools/tests
```

Generated output nên đặt trong `contracts/generated/` hoặc build directory của
service; thư mục đó bị ignore và không được sửa tay.
