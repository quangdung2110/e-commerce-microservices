# Evidence: message lanes (ADR-029) so với ADR-028

Xem [báo cáo](../order-dispatcher-message-lanes-benchmark-2026-10-20.md).

| Thư mục | Lượt đo, Asia/Bangkok | Profile | Thao tác | Kịch bản |
|---|---|---|---:|---:|
| `paired-1/` | `20261020-151333` | `paired` (ABBA adr028/parallel) | 73.700 | 22 |
| `paired-2/` | `20261020-153115` | `paired` (ABBA adr028/parallel) | 75.600 | 22 |
| `separate/1-adr028/` | `20261020-145328` | `mixed`, variant `adr028` | 26.500 | 9 |
| `separate/1-parallel/` | `20261020-145723` | `mixed`, variant `parallel` | 26.700 | 9 |
| `separate/2-parallel/` | `20261020-150252` | `mixed`, variant `parallel` | 25.300 | 9 |
| `separate/2-adr028/` | `20261020-150641` | `mixed`, variant `adr028` | 23.700 | 9 |
| `aborted-20261020-152243/` | `20261020-152243` | `paired`, dừng ở warmup | 0 | 0 |

- `paired-N/`: đủ dữ liệu thô như runner xuất: `summary.json`, `environment.json`, `<scenario>.csv.gz`
  (timestamp từng row, `result`, `ack_batch`, `target`), `<scenario>-transactions.csv.gz`
  (`kind,duration_ns,end_ns,rows,start_rows,message_rows`; `kind` ghép bằng `+` khi một transaction có nhiều câu lệnh),
  `verification.txt` (verifier độc lập), `comparison.json` (`compare.py` chế độ paired), Docker samples,
  `maven-output.txt` (log Maven, chuyển từ UTF-16 của `Tee-Object` sang UTF-8), `containers.txt` (log container),
  JUnit report, hash source và git status. Log được lưu đuôi `.txt` vì repo bỏ qua `*.log`.
  `paired-1/stall-analysis.txt` là output [`rpc_gaps.py`](./rpc_gaps.py) cho cửa sổ ADR-029 bị chậm và hai cửa sổ đối chứng.
- `separate/`: bốn lượt riêng trước khi có profile paired. Chỉ giữ summary, environment, `verification.txt`
  (đã chạy verifier trên dữ liệu thô đầy đủ trước khi lược), Docker samples, `maven-output.txt`, hash và hai `comparison-N.json`.
  CSV thô được lược để giảm dung lượng; bản đầy đủ còn trong `loadtest/order-dispatch-bench/results/<timestamp>/`
  (git-ignored) trên máy đo.
- `aborted-20261020-152243/`: Docker Desktop đứng trong warmup (docker stats ngừng ghi, Hikari báo connection bị đóng,
  warmup không drain trong 120 giây). Giữ log để minh bạch; không có số đo.
- `combined.txt`: output của [`combine.py`](./combine.py), gộp hai `paired-N/comparison.json` (median 8 cặp, tỷ lệ từng cặp,
  transaction/thao tác, tải mở).
- `validation/order-suite.txt`: Order suite 124 test, 0 failure/error, 1 skip; `validation/production-change.diff`:
  diff ba file production so với ADR-028.

Kiểm tra lại không cần dịch vụ đang chạy:

```text
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-1
python loadtest/order-dispatch-bench/analyze.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-2
python loadtest/order-dispatch-bench/compare.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-2
python docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/combine.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20
python docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/rpc_gaps.py docs/performance/order-dispatcher-message-lanes-evidence-2026-10-20/paired-1/saturated-mixed-50-50-parallel-2.csv.gz
```
