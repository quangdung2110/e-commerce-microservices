---
doc_id: <service-id>-workflows
service: <service-id>
scope: current # current | target
status: proposed # proposed | implemented | verified
owners:
  - <team-or-owner>
evidence:
  - <path-to-application-service-or-worker>
  - <path-to-workflow-tests>
verified_against_commit: <git-sha-or-null>
---

# <Service Name> — Workflows

> File này mô tả các luồng end-to-end. Mỗi workflow phải cho developer biết
> chính xác điều gì xảy ra khi một bước timeout hoặc cùng request được gửi hai lần.

## Workflow index

| ID | Workflow | Trigger | Actor | Status |
|---|---|---|---|---|
| `WF-<SERVICE>-001` | `<name>` | API/Event/Schedule | `<actor>` | proposed/implemented/verified |

---

## WF-<SERVICE>-001 — <Workflow name>

### Mục tiêu

Mô tả kết quả nghiệp vụ mà workflow phải tạo ra.

### Actor và participants

Liệt kê actor khởi tạo, service tham gia và vai trò của từng participant.

### Trigger

Nêu API, event, schedule hoặc hành động nội bộ bắt đầu workflow.

### Preconditions

Mô tả state, quyền và dữ liệu phải tồn tại trước khi workflow chạy.

### Input

Mô tả ý nghĩa đầu vào và liên kết tới contract. Không copy toàn bộ payload.

### Happy path

Liệt kê tuần tự từng bước, component thực hiện và lời gọi liên-service. Dùng
sequence diagram nếu nó giúp làm rõ từ ba participant trở lên.

### State/data changes

Nêu dữ liệu/state thay đổi ở từng transaction boundary và service sở hữu thay đổi đó.

### Events emitted

Liệt kê event phát ra, thời điểm phát và link tới `interfaces.md`/contract.

### Output

Mô tả kết quả trả về cho caller hoặc state cuối cùng nếu workflow bất đồng bộ.

### Failure paths

Liệt kê từng điểm có thể thất bại, lỗi được trả/ghi nhận và state còn lại sau lỗi.

### Timeout và retry

Mô tả deadline, retry owner, backoff, giới hạn retry và lỗi không được retry.

### Idempotency

Nêu idempotency key, nơi lưu kết quả, thời gian giữ và hành vi khi replay.

### Concurrency/race conditions

Nêu các request/event có thể chạy đồng thời và cơ chế giữ invariant.

### Compensation hoặc recovery

Mô tả compensation, reconciliation, manual recovery hoặc cách resume workflow.

### Metrics và traces

Liệt kê metric, trace/span và identifier dùng để theo dõi workflow.

### Implementation evidence

Liên kết tới application service, worker, controller, configuration và migration
chứng minh workflow đã được implement.

### Test scenarios

Liệt kê happy path, boundary case, duplicate, timeout, retry, concurrent request
và recovery scenario bắt buộc phải test.

---

Sao chép nguyên template `WF-<SERVICE>-001` cho từng workflow đồng bộ, bất đồng
bộ hoặc Saga quan trọng. Không chỉ mô tả happy path.
