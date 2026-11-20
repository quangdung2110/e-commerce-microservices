# Evidence: legacy một thread và parallel tám worker

Xem [báo cáo so sánh](../order-dispatcher-parallel-benchmark-2026-10-16.md).

| Thư mục | Lượt đo, Asia/Bangkok | Thao tác | Kịch bản |
|---|---|---:|---:|
| `parallel/` | `20261016-161000` | 23.100 | 12 |
| `legacy/` | `20261016-161424` | 12.050 | 10 |

`comparison.json` do `loadtest/order-dispatch-bench/compare.py` tính từ hai summary, bao gồm
kiểm tra 18 field môi trường giống nhau, median start/s, transaction/start và tỷ lệ cải thiện.

Mỗi thư mục variant có:

- `summary.json`, `environment.json`: cửa sổ đo, counts, throughput, percentile, ACK batch size và runtime.
- `<scenario>.csv.gz`: timestamp từng row, result, `ack_batch`; đơn vị nanosecond theo origin riêng của ca.
- `<scenario>-transactions.csv.gz`: `kind,duration_ns,end_ns,rows`; `rows` cho phép đối chiếu ACK nhiều row trong một transaction.
- `verification.txt`: kết quả verifier độc lập từ dữ liệu thô. Có kiểm tra số batch, tổng row và timestamp commit dùng chung trong cùng batch.
- `source-hashes.json`, `git-head.txt`, `git-status.txt`: nhận diện source; hash được đối chiếu lại sau benchmark.
- `images.json`, `docker-resources.txt`, `docker-stats.jsonl`: image và mẫu tài nguyên trong cả warmup/window/drain.
- `maven-output.txt`, `shared-containers-before.txt`, `shared-containers-after.txt`: PASS và inventory trước/sau.

`validation/` lưu Order suite log, log package và `production-change.diff` cho bốn file production
được thay đổi trong task. Raw container log/JUnit XML bổ sung còn trong
`loadtest/order-dispatch-bench/results/<timestamp>/` (git-ignored) trên máy đo.

`legacy` là fixture test-only của dispatcher và ACK SQL cũ. Cả hai variant dùng cùng gateway,
claim/message SQL, producer và instrumentation; production runtime chỉ có implementation mới.

Không cần dịch vụ đang chạy để kiểm tra lại evidence:

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-parallel-evidence-2026-10-16/legacy
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-parallel-evidence-2026-10-16/parallel
```
