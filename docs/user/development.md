---
doc_id: user-development
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
verified_against_commit: null
---

# User Service — Hướng dẫn phát triển

> Không suy diễn lệnh, runtime, port hoặc source path từ thiết kế. Các mục này là
> checklist cần hoàn thiện khi implementation được đưa vào phạm vi xác minh.

## 1. Điều kiện tiên quyết và phụ thuộc local

Cần công bố runtime/language, package manager, container runtime và tool lint/
format chính thức. Local dependency dự kiến là PostgreSQL, Redis và Kafka; port,
image, seed data và service nào có thể mock phải lấy từ compose/configuration đã
xác minh. Không dùng PII production trong fixture.

## 2. Chạy local và cấu hình

Tài liệu implementation phải cung cấp command copy-paste để cài dependency,
chạy migration, start API/consumer/worker, check health và cleanup an toàn.
Configuration cần có bảng variable/purpose/required/default/secret/restart. Secret
Kafka/DB/TLS không commit; identity test phải được Gateway/Auth mock hoặc contract
fixture cấp đúng cách.

## 3. Biên dịch, chất lượng và kiểm thử

CI cần có command chuẩn cho build, format, lint, unit/integration/contract test.
Test tối thiểu phải bao phủ ownership address, partial unique default, từ chối xóa
default address, profile ID từ Auth event, cache hit/miss/invalidation/fallback,
duplicate/out-of-order Auth event, deletion retention và worker resume. Consumer
test phải xác minh ack sau commit, retry/DLQ và idempotency; contract test chỉ có
thể hoàn thiện với OpenAPI/AsyncAPI User v1 và Auth AsyncAPI v1. Worker test phải
bao phủ purge trước/sau deadline, `SKIP LOCKED`/crash-resume, profile/preferences
đã bị deletion loại khỏi reconciliation, corrective event ID mới và `updated_at`
không bị thay đổi.

## 4. Phát triển schema và contract

Mọi thay đổi persistent data phải đi kèm migration có thể chạy local, kiểm tra
backward compatibility/backfill và fixture test. [OpenAPI User v1](../../contracts/http/user/v1/openapi.yaml)
và [AsyncAPI User v1](../../contracts/events/user/v1/asyncapi.yaml) là nguồn
contract hiện tại; generated code (nếu có) cần command/version/output path và
drift check trong CI. Không coi bảng/JSON copy trong Markdown là schema nguồn.

## 5. Gỡ lỗi và tiêu chí hoàn thành

Debug local phải dùng log redacted, trace/correlation ID và truy vấn read-only
đến DB/Redis/Kafka khi có thể. Không in phone, address hoặc full event payload
vào log. Một thay đổi hoàn thành khi build/lint/test pass, migration/contract
được cập nhật, backward compatibility được kiểm tra, workflow/domain/data thay
đổi được phản ánh, ADR được thêm nếu có quyết định dài hạn và evidence/status
tài liệu được cập nhật.
