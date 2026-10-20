# Evidence: Order dispatcher một thread

Xem [báo cáo](../order-dispatcher-single-thread-benchmark-2026-10-16.md).

| Thư mục | Lượt chạy | Phạm vi |
|---|---|---|
| `pilot/` | `20261016-153413`, Asia/Bangkok | 12.050 thao tác, 10 kịch bản; export latency từng row |
| `baseline/` | `20261016-153937`, Asia/Bangkok | 11.950 thao tác, 10 kịch bản; bổ sung biên cửa sổ đo và CSV transaction để verifier đối chiếu độc lập |

Hai lượt đều giữ nguyên implementation dispatcher/store/gateway. Khác biệt harness giữa pilot và baseline chỉ là export thêm metadata và transaction samples; baseline là dữ liệu chính của báo cáo.

Mỗi thư mục có:

- `summary.json`: throughput, counts, backlog và các thống kê latency. `window` chỉ lấy ACK trong cửa sổ; `all_including_drain` lấy toàn bộ cohort.
- `<scenario>.csv.gz`: mỗi row một dòng, timestamp nanosecond tính từ origin riêng của kịch bản; `scheduled_ns` trống với ca không có lịch đến cố định.
- `<scenario>-transactions.csv.gz` (baseline): `kind,duration_ns,end_ns`, gồm cả empty polls. Biên `window_begin_ns` / `window_end_ns` cùng hệ quy chiếu với CSV.
- `environment.json`, `images.json`, `docker-resources.txt`: runtime và cấu hình thực tế.
- `source-hashes.json`, `git-head.txt`, `git-status.txt`: nhận diện source; SHA-256 của harness được thêm từ baseline.
- `docker-stats.jsonl`: mẫu CPU/memory của ba container trong toàn lượt, kể cả warmup và drain.
- `maven-output.txt`: lệnh benchmark JUnit kết thúc PASS.
- `shared-containers-before.txt` / `shared-containers-after.txt`: inventory stack dùng chung trước/sau.
- `verification.txt` (baseline): kết quả verifier độc lập kiểm tra trực tiếp từ CSV nén.

Raw logs bổ sung của infrastructure và JUnit XML vẫn nằm trong `loadtest/order-dispatch-bench/results/<timestamp>/` (git-ignored) trên máy chạy. Các file cần kiểm tra số đo đã được lưu trong evidence này.

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-single-thread-evidence-2026-10-16/baseline
```

Verifier chỉ dùng Python standard library và không kết nối tới dịch vụ nào.
