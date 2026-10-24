# PDP catalog cache benchmark

Isolated stack and scripts behind [docs/performance/pdp-catalog-cache-benchmark-2026-10-17.md](../../docs/performance/pdp-catalog-cache-benchmark-2026-10-17.md).

| File | Role |
|---|---|
| `compose.yaml` | Own project `pdp-bench`: PostgreSQL (with `pg_stat_statements`), Product, Inventory, Review from the shared stack's images, the BFF from a jar. CPU limits: BFF 2, Product 2, Inventory 1.5, Review 1.5, PostgreSQL 3 |
| `init-db.sql` | Creates the three service databases |
| `seed/product.sql`, `seed/inventory.sql` | Deterministic catalog: 10 000 ACTIVE products, 25 000 SKUs, 85 000 media, 3-level categories, availability for every SKU |
| `../k6/pdp-cache.js` | Open-model load (`constant-arrival-rate`), Zipf or uniform popularity; `TARGET=product` calls Product directly |
| `../../scripts/ci/pdp-bench.sh` | Restarts the services, warms Product then the whole path, runs one rate per step and records k6, CPU, SQL and Prometheus samples |
| `analyze.py` | Writes `summary.json` and `summary.md` for a run |
| `baseline.env`, `cache.env`, `cache-off.env` | BFF settings per variant |

```bash
# once: start PostgreSQL, migrate and seed
SEED=1 RATES=150 DURATION=10s bash scripts/ci/pdp-bench.sh smoke loadtest/pdp-bench/jars/bff-baseline.jar loadtest/pdp-bench/baseline.env
# a variant (jar copied to loadtest/pdp-bench/jars/ so a rebuild cannot change what is measured)
RUN_ID=my-run DIST=zipf RATES="150 200 300 400" bash scripts/ci/pdp-bench.sh cache loadtest/pdp-bench/jars/bff-cache.jar loadtest/pdp-bench/cache.env
python loadtest/pdp-bench/analyze.py loadtest/pdp-bench/results/my-run
# done
docker compose -p pdp-bench -f loadtest/pdp-bench/compose.yaml down -v
```

The shared compose stack is never touched, but it shares the host's CPUs: run when `docker stats` is quiet.
`results/` and `jars/` are git-ignored.
