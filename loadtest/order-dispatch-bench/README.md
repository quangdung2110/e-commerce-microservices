# Order dispatcher benchmark: legacy, ADR-028, ADR-029 and production (ADR-030)

Run from PowerShell with Docker Desktop, Java 21 and Maven 3.9:

```powershell
./scripts/ci/order-dispatch-bench.ps1 -Variant legacy
./scripts/ci/order-dispatch-bench.ps1 -Variant parallel
# ADR-029 pair: frozen ADR-028 dispatcher vs production, start-only and 50/50 mixed ceilings
./scripts/ci/order-dispatch-bench.ps1 -Variant adr028 -Profile mixed
./scripts/ci/order-dispatch-bench.ps1 -Variant parallel -Profile mixed
# ADR-030: both variants alternate in one JVM/stack, including deterministic slow RPCs
./scripts/ci/order-dispatch-bench.ps1 -Seconds 20 -Profile rolling-paired
# One variant, healthy/tail saturation and fixed arrivals
./scripts/ci/order-dispatch-bench.ps1 -Seconds 30 -Variant adr029 -Profile rolling
./scripts/ci/order-dispatch-bench.ps1 -Seconds 30 -Variant parallel -Profile rolling
# Optional: longer measurement windows (default 15 seconds per sustained scenario)
./scripts/ci/order-dispatch-bench.ps1 -Seconds 30 -JavaHome C:/path/to/jdk-21 -Maven C:/path/to/mvn.cmd
```

Run the script as its own PowerShell process (or interactively). Do not merge its stderr into the
pipeline (`*>&1`): with `$ErrorActionPreference = "Stop"`, Docker's progress output on stderr then
aborts the run. To capture logs, start it with `Start-Process -RedirectStandardOutput/-RedirectStandardError`.

Uses a uniquely named Compose project, two isolated PostgreSQL databases (Order and Temporal),
and Temporal Server 1.25.2. Only loopback ports 25432 and 27233 are published. Override with
`ORDER_BENCH_DB_PORT` / `ORDER_BENCH_TEMPORAL_PORT`. The runner removes its own project and volumes
in `finally`; it does not start/stop services in the shared project.

The opt-in JUnit harness calls the dispatcher `start()` lifecycle, real outbox SQL,
production `TemporalWorkflowGateway`, and SDK 1.25.1. Variants:

- `parallel` (default): production ADR-030, 8 shared RPC workers, rolling claim up to 50 unacknowledged
  rows per queue (100 total), half-window refill, 50/20ms fenced ACK, per-target lanes across claims,
  local backoff holds and 200ms empty/partial polling. Pool threads are `order-workflow-rpc-N`.
- `adr029`: frozen pre-ADR-030 dispatcher. Eight shared RPC workers and fenced ACK/renew, but the next
  claim waits for the slowest RPC of the current batch. The fixture has no Spring component annotation.
- `adr028`: the test-only frozen ADR-028 dispatcher in `src/test/java/com/ecommerce/order/benchmark/baseline`.
  Eight start workers with fenced batch ACK, then sequential `SignalWithStart` and per-row, unfenced
  message ACK on the coordinator. Pool threads are `order-workflow-start-N`.
- `legacy`: the frozen pre-optimization dispatcher, one thread and one ACK transaction per row.

All use the same base lease/claim semantics, 30s leases, 200ms idle polling, Hikari 24/4, workload, instrumentation and
Temporal gateway. Subclasses/wrappers record monotonic timestamps without changing RPC results.
Production rolling claims add bounded exclusion lists for locally tracked start IDs/message targets and retry lanes;
the frozen baselines use the original three-argument claim without exclusions.
Flyway applies the real service migrations. The test refuses any database not named `order_dispatch_bench`.

This isolates dispatch, not checkout completion: there are no business workers, activities, HTTP,
Inventory, Promotion, Payment, Kafka or Redis. Temporal persists starts and signals in real PostgreSQL;
the workflows stay pending. There is no in-memory Temporal server. The original profiles add no
artificial RPC latency; `rolling` and `rolling-paired` explicitly add controlled holds in their tail scenarios.
The closed-target resolver is mocked and must never be reached (only successful starts/signals).

Profiles, after 500 warmup starts:

- `full` (default):
  - Bursts of 50 and 500 committed rows, drained by one thread.
  - Three fixed-duration, continuously backlogged start-only runs; producer replenishes 100 rows
    whenever outstanding work drops below 1000. Samples cover the measurement window; tail drain is separate.
  - Fixed-interval arrivals at 40/s, 50/s, 70/s, 100/s, plus approximately 70%/130% of the median saturated
    throughput (deduplicated). No wait for dispatch before the next intended arrival. Producer lateness is
    reported, including intended-arrival latency, so inability to generate the offered load cannot silently hide overload.
  - One continuously backlogged 50% starts / 50% messages run.
- `mixed` (ADR-029): three start-only and three 50/50 backlogged windows, alternating
  (`saturated-starts-N`, `saturated-mixed-50-50-N`) so drift affects both alike, then fixed-interval
  50/50 arrivals at 80/s, 120/s and 160/s (`arrival-mixed-R`).
- `rolling`: healthy and tail 50/50 saturation, healthy 160 ops/s, tail 40/80/120/160 ops/s.
- `rolling-paired`: four adjacent baseline/production pairs for each healthy/tail saturation,
  alternating order ABBA, then healthy 160 ops/s and tail 40/80/120/160 ops/s for both variants.
  The same JVM, SDK and databases are used throughout. Each window has `-Seconds` duration;
  drain is outside throughput measurement, allowed up to 600s to retain overloaded requests in latency.

Tail injection: ordinal 0 and 101 of each 200 operations (one start and one message), with
deterministic rotating 1000/2000/3000ms holds **before the real SDK call**, while occupying an RPC
slot. All calls still reach real Temporal. This models isolated stragglers; it does not inject transport
failure or prove behavior when every Temporal call stalls. Message targets rotate round-robin in
these profiles. The CSV records `injected_delay_ms`; summaries include uninjected-request latency,
slot utilization and peak claimed-but-unacknowledged rows. `environment.variant` is the initial CLI
selection; each summary row's `variant` identifies the dispatcher actually used in paired profiles.

Messages use `SignalWithStart` on existing pending workflows, with 100 targets pre-started outside
measurement; each message picks a target by hash, so several messages share a target. No worker consumes them.

`results/<timestamp>/` contains environment metadata, summary JSON, per-row monotonic timestamps
(with the message target) and transaction timings in `.csv.gz`, build/test logs, source hashes, Docker
resource samples and before/after container inventory. Verify the export independently with:

```text
python loadtest/order-dispatch-bench/analyze.py loadtest/order-dispatch-bench/results/<timestamp>
python loadtest/order-dispatch-bench/compare.py <old-results> <new-results>
# Paired rolling profile: additional comparison and idle-slot time derived from raw timestamps
python loadtest/order-dispatch-bench/rolling_compare.py <results>
```

Every measured run verifies the gateway concurrency limit (one or eight), permitted RPC thread names, all rows terminal,
one attempt per row, matching input/ack/database row counts, and at most one in-flight message per target
(from the gateway and, independently, from raw RPC timestamps). Empty polls are included in claim
transaction statistics. Percentiles use nearest rank, not an average of percentiles.
The eight-worker saturated start-only runs must actually reach eight concurrent calls. ACK transaction row
counts and batch IDs are exported and independently verified. Since ADR-029 one dispatcher transaction can
carry several statements (claim of both queues, start + message ACK, renewal of both); its kind is the
statement kinds joined with `+`, with `start_rows`/`message_rows` columns.

Latency boundaries:

- `queue_to_rpc_ms`: enqueue transaction's client-side after-commit callback to beginning of gateway call.
- `claim_to_rpc_ms`: claim transaction after-commit callback to gateway call (waiting inside a claimed batch).
- `gateway_ms`: production SDK gateway call including serialization, transport and any SDK retry.
- `commit_to_ack_ms`: enqueue after-commit callback to successful acknowledgement after-commit callback.
- `transaction_ms`: complete dispatcher transaction, including connection acquisition, SQL and commit.
- `intended_arrival_to_ack_ms`: scheduled arrival to ACK, including producer/DB enqueue delay.

The timestamp callbacks execute just after JDBC commit returns, not at the server's internal WAL timestamp.
`analyze.py` also independently verifies injected/uninjected counts and percentiles, slot utilization,
and the 100-row window bound from claim/ACK timestamps. `rolling_compare.py` integrates intervals with
committed work that has not begun its RPC and fewer than eight occupied slots; SQL, lane waits and batch
barriers can all contribute, so this metric does not attribute every idle nanosecond to a straggler.
Throughput counts acknowledged operations within the window. It is not workflow execution throughput,
`PAYMENT_READY` latency or production capacity.
