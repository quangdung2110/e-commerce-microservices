# Inventory Reserve hot-SKU benchmark

Isolated stack and scripts behind [docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md](../../docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md).

| File | Role |
|---|---|
| `compose.yaml` | Own project `inventory-bench`: PostgreSQL 16 (with `pg_stat_statements`, durability defaults) and Inventory from a jar, workers on. CPU limits: Inventory 2, PostgreSQL 3 |
| `init-db.sql` | Creates the service database and `pg_stat_statements` |
| `seed/inventory.sql` | One warehouse, the hot SKU `BENCH-HOT` and 2 000 cold SKUs, all with stock that no step can exhaust; truncates every other Inventory table |
| `../k6/inventory-reserve.js` | Open-model load (`constant-arrival-rate`) of one-line ReserveOrder commands as Order sends them; `HOT_SHARE` of them for the hot SKU, the rest uniform over cold SKUs; no client retries. `MODE=reserve-commit` adds a second scenario at the same rate that finishes each order `COMMIT_DELAY` seconds later with CommitPayment, or ReleaseReservation for `RELEASE_SHARE` of them (flash sale: both commands lock the same row) |
| `../../scripts/ci/inventory-bench.sh` | Reseeds, restarts and warms Inventory, runs one rate per step and records k6, CPU, Hikari, lock waiters, SQL and the hot position |
| `analyze.py` | Writes `summary.json` and `summary.md` for a run |

```bash
# jar copied to jars/ so a rebuild cannot change what is measured
cp java/services/inventory-service/target/inventory-service-1.0.0-SNAPSHOT.jar loadtest/inventory-bench/jars/inventory-baseline.jar
# scenarios of the baseline report
RUN_ID=my-run RATES="50 100 150 200 250 300 400 600" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar hot 1
RUN_ID=my-run RATES="100 200 300 400 600 800" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar cold 0
RUN_ID=my-run RATES="100 200 300 400 600" bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar mixed 0.5
python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/my-run
# flash sale: every order is reserved, then committed (90%) or released (10%) 15 s later; RATES are orders/s
MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 DURATION=120s RUN_ID=my-flash RATES="50 75 100 125 150 200 300" \
  bash scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
python loadtest/inventory-bench/analyze.py loadtest/inventory-bench/results/my-flash
# done
INVENTORY_JAR=./jars/inventory-baseline.jar docker compose -p inventory-bench -f loadtest/inventory-bench/compose.yaml down -v
```

To compare a changed build, copy its jar to `jars/` under another name and run the same three commands with another
variant name and the same `RUN_ID`; `analyze.py` puts every variant of the run in one summary.

The shared compose stack is never touched, but it shares the host's CPUs: run when `docker stats` is quiet.
`results/` and `jars/` are git-ignored.
