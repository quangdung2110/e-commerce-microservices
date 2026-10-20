# Order dispatcher benchmark: legacy one thread vs eight start workers

Run from PowerShell with Docker Desktop, Java 21 and Maven 3.9:

```powershell
./scripts/ci/order-dispatch-bench.ps1 -Variant legacy
./scripts/ci/order-dispatch-bench.ps1 -Variant parallel
# Optional: longer measurement windows (default 15 seconds per sustained scenario)
./scripts/ci/order-dispatch-bench.ps1 -Seconds 30 -JavaHome C:/path/to/jdk-21 -Maven C:/path/to/mvn.cmd
```

Uses a uniquely named Compose project, two isolated PostgreSQL databases (Order and Temporal),
and Temporal Server 1.25.2. Only loopback ports 25432 and 27233 are published. Override with
`ORDER_BENCH_DB_PORT` / `ORDER_BENCH_TEMPORAL_PORT`. The runner removes its own project and volumes
in `finally`; it does not start/stop services in the shared project.

The opt-in JUnit harness calls the dispatcher `start()` lifecycle, real outbox SQL,
production `TemporalWorkflowGateway`, and SDK 1.25.1. `-Variant parallel` (default) measures the
production implementation with eight start workers, batch 50, ACK batches up to 50/20ms and claim
generation fencing. `-Variant legacy` uses the test-only frozen pre-optimization dispatcher and
per-row ACK SQL in `src/test/java/com/ecommerce/order/benchmark/baseline`; it has one dispatcher
thread and one ACK transaction per row. Both use the same unchanged claim/message SQL, 30s leases,
200ms idle polling, Hikari 24/4, workload, instrumentation and Temporal gateway.
Subclasses/wrappers record monotonic timestamps without changing RPC results.
Flyway applies the real service migrations. The test refuses any database not named `order_dispatch_bench`.

This isolates dispatch, not checkout completion: there are no business workers, activities, HTTP,
Inventory, Promotion, Payment, Kafka or Redis. Temporal persists starts and signals in real PostgreSQL;
the workflows stay pending. There is no artificial RPC latency or in-memory Temporal server.
The closed-target resolver is mocked and must never be reached (only successful starts/signals).

Scenarios, after 500 warmup starts:

- Bursts of 50 and 500 committed rows, drained by one thread.
- Three fixed-duration, continuously backlogged start-only runs; producer replenishes 100 rows
  whenever outstanding work drops below 1000. Samples cover the measurement window; tail drain is separate.
- Fixed-interval arrivals at 40/s, 50/s, 70/s, 100/s, plus approximately 70%/130% of the median saturated
  throughput (deduplicated). The original baseline had the same four rates because its adaptive
  rates were 40/s and 70/s.
  No wait for dispatch before the next intended arrival. Producer lateness is reported, including
  intended-arrival latency, so inability to generate the offered load cannot silently hide overload.
- One continuously backlogged 50% starts / 50% messages run. Messages use `SignalWithStart` on
  existing pending workflows, with 100 targets pre-started outside measurement. No worker consumes them.

`results/<timestamp>/` contains environment metadata, summary JSON, per-row monotonic timestamps
and transaction timings in `.csv.gz`, build/test logs, source hashes, Docker resource samples and
before/after container inventory. Verify the export independently with:

```text
python loadtest/order-dispatch-bench/analyze.py loadtest/order-dispatch-bench/results/<timestamp>
```

Every measured run verifies the gateway concurrency limit (one or eight), permitted RPC thread names, all rows terminal,
one attempt per row, and matching input/ack/database row counts. Empty polls are included in claim
transaction statistics. Percentiles use nearest rank, not an average of percentiles.
The eight-worker saturated runs must actually reach eight concurrent calls. ACK transaction row
counts and batch IDs are exported and independently verified; message ACKs remain per-row on the coordinator.

Latency boundaries:

- `queue_to_rpc_ms`: enqueue transaction's client-side after-commit callback to beginning of gateway call.
- `claim_to_rpc_ms`: claim transaction after-commit callback to gateway call (waiting inside a claimed batch).
- `gateway_ms`: production SDK gateway call including serialization, transport and any SDK retry.
- `commit_to_ack_ms`: enqueue after-commit callback to successful acknowledgement after-commit callback.
- `transaction_ms`: complete dispatcher transaction, including connection acquisition, SQL and commit.
- `intended_arrival_to_ack_ms`: scheduled arrival to ACK, including producer/DB enqueue delay.

The timestamp callbacks execute just after JDBC commit returns, not at the server's internal WAL timestamp.
Throughput counts acknowledged operations within the window. It is not workflow execution throughput,
`PAYMENT_READY` latency, production capacity, or a performance comparison against a parallel dispatcher.
