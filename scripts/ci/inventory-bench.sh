#!/usr/bin/env bash
# Inventory Reserve hot-SKU benchmark (docs/performance/inventory-reserve-hot-sku-baseline-2026-10-19.md).
#
# Runs one Inventory build on the isolated inventory-bench stack (loadtest/inventory-bench/compose.yaml) through an
# open-model k6 load of ReserveOrder commands (loadtest/k6/inventory-reserve.js) at a list of arrival rates, and
# records per step:
#   k6 summary (outcome per SKU class, latency, dropped arrivals), container CPU (docker stats), Hikari
#   pending/active/timeouts and Inventory command counters (Prometheus every 1 s), PostgreSQL backends waiting on a
#   lock (pg_stat_activity every 1 s), SQL per statement (pg_stat_statements), commits/rollbacks/deadlocks and the hot
#   stock_position before and after (correctness: reserved delta must equal the RESERVED responses).
#
#   scripts/ci/inventory-bench.sh <variant> <jar> <scenario> <hot-share>
#   RATES="50 100 200" DURATION=60s scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar hot 1
#   MODE=reserve-commit COMMIT_DELAY=15 RELEASE_SHARE=0.1 DURATION=120s RATES="100 150" \
#       scripts/ci/inventory-bench.sh baseline loadtest/inventory-bench/jars/inventory-baseline.jar flash 1
#
# MODE=reserve-commit also finishes every order with CommitPayment (or ReleaseReservation for RELEASE_SHARE of them)
# COMMIT_DELAY seconds after its Reserve, at the same rate, so both commands contend for the same stock_position rows as
# in a flash sale. RATES are then orders per second (Reserve and Commit/Release each arrive at that rate).
#
# Every invocation stops Inventory, reseeds the database (seed/inventory.sql), restarts Inventory and warms it up on
# cold SKUs, so each variant and scenario starts from the same state. The shared compose stack is not touched.
# Results go to loadtest/inventory-bench/results/<RUN_ID>/<variant>-<scenario>/ (git-ignored).
set -euo pipefail

VARIANT="${1:?variant name}"
JAR="${2:?Inventory jar}"
SCENARIO="${3:?scenario name}"
HOT_SHARE="${4:?share of orders for the hot SKU, 0..1}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
RATES="${RATES:-50 100 150 200 300 400}"
DURATION="${DURATION:-60s}"
PAUSE="${PAUSE:-15}"
COLD_SKUS="${COLD_SKUS:-2000}"
MAX_VUS="${MAX_VUS:-2000}"
MODE="${MODE:-reserve}"
COMMIT_DELAY="${COMMIT_DELAY:-15}"
RELEASE_SHARE="${RELEASE_SHARE:-0}"
TIMEOUT="${TIMEOUT:-30s}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
PROJECT=inventory-bench
NETWORK="${PROJECT}_default"
BENCH_DIR="$REPO_ROOT/loadtest/inventory-bench"
OUT_DIR="$BENCH_DIR/results/$RUN_ID/$VARIANT-$SCENARIO"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

mkdir -p "$OUT_DIR"
export INVENTORY_JAR; INVENTORY_JAR="$(host_path "$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")")"
compose() { docker compose -p "$PROJECT" -f "$(host_path "$BENCH_DIR/compose.yaml")" "$@"; }
psql_q() { compose exec -T postgres psql -U postgres -d inventory_service -At -F ' | ' -c "$1"; }

{
  echo "variant=$VARIANT scenario=$SCENARIO hot_share=$HOT_SHARE rates=$RATES duration=$DURATION pause=$PAUSE"
  echo "cold_skus=$COLD_SKUS max_vus=$MAX_VUS k6_image=$K6_IMAGE"
  echo "mode=$MODE commit_delay=$COMMIT_DELAY release_share=$RELEASE_SHARE timeout=$TIMEOUT"
  echo "jar_sha256=$(sha256sum "$JAR" | cut -d' ' -f1)"
  echo "git_head=$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  echo "inventory_src_changed_files=$(git -C "$REPO_ROOT" status --porcelain -- java/services/inventory-service | wc -l)"
  docker info --format 'docker_cpus={{.NCPU}} docker_mem={{.MemTotal}}'
  echo "shared_stack_containers=$(docker ps --format '{{.Names}}' | grep -vc "^$PROJECT-" || true)"
  echo "started=$(date -Iseconds)"
} > "$OUT_DIR/environment.txt"

wait_ready() {
  for _ in $(seq 1 90); do
    if compose exec -T inventory-service wget -qO- http://localhost:8080/actuator/health/readiness 2>/dev/null \
        | grep -q UP; then return 0; fi
    sleep 2
  done
  echo "inventory-service not ready within 180s" >&2; compose logs --tail 80 inventory-service >&2; return 1
}

echo "== migrate, reseed and restart ($VARIANT $SCENARIO)"
compose up -d postgres inventory-service >/dev/null
wait_ready                                   # Flyway has migrated the schema
compose stop inventory-service >/dev/null
compose exec -T postgres psql -q -U postgres -d inventory_service -v "cold_skus=$COLD_SKUS" -f /seed/inventory.sql
compose up -d --force-recreate inventory-service >/dev/null
wait_ready
psql_q "SELECT name || '=' || setting FROM pg_settings WHERE name IN ('fsync','synchronous_commit','wal_sync_method',
    'shared_buffers','max_connections') ORDER BY 1" >> "$OUT_DIR/environment.txt"

k6_run() { # $1 rate, $2 duration, $3 hot share, $4 run tag, $5 output file name, $6 mode (default $MODE)
  docker run --rm --network "$NETWORK" -e RATE="$1" -e DURATION="$2" -e HOT_SHARE="$3" -e RUN_TAG="$4" \
    -e COLD_SKUS="$COLD_SKUS" -e MAX_VUS="$MAX_VUS" -e OUT="/out/$5" -e MODE="${6:-$MODE}" \
    -e COMMIT_DELAY="$COMMIT_DELAY" -e RELEASE_SHARE="$RELEASE_SHARE" -e TIMEOUT="$TIMEOUT" \
    -v "$(host_path "$REPO_ROOT/loadtest/k6"):/scripts:ro" -v "$(host_path "$OUT_DIR"):/out" \
    "$K6_IMAGE" run --quiet /scripts/inventory-reserve.js 2>&1 | grep '^rate=' || true
}

echo "== warm-up (cold SKUs, then a little hot)"
mkdir -p "$OUT_DIR/warmup"
if [ "${SKIP_WARMUP:-0}" != "1" ]; then
  for rate in 20 50 100 150; do k6_run "$rate" 20s 0 "w$RUN_ID-c$rate" "warmup/cold-$rate.json" reserve; done
  k6_run 30 20s 1 "w$RUN_ID-h30" "warmup/hot-30.json"   # in MODE=reserve-commit this also warms Commit/Release
fi
sleep "$PAUSE"

# Samplers are throwaway containers on the bench network, so sampling costs no CPU inside the measured limits.
samplers_start() {
  docker rm -f inventory-bench-prom inventory-bench-pg >/dev/null 2>&1 || true
  docker run -d --network "$NETWORK" --name inventory-bench-prom alpine:3.20 sh -c '
    while true; do
      ts=$(date +%s)
      wget -qO- http://inventory-service:8080/actuator/prometheus 2>/dev/null \
        | grep -E "^(hikaricp_connections_(pending|active|timeout_total)|inventory_commands_total)" | sed "s/^/$ts /"
      sleep 1
    done' > /dev/null
  docker run -d --network "$NETWORK" --name inventory-bench-pg -e PGPASSWORD=postgres postgres:16-alpine sh -c '
    while true; do
      psql -h postgres -U postgres -d inventory_service -At -F " " -c "SELECT extract(epoch FROM now())::bigint,
        count(*) FILTER (WHERE wait_event_type = '"'"'Lock'"'"'),
        count(*) FILTER (WHERE state = '"'"'active'"'"'),
        count(*) FILTER (WHERE state LIKE '"'"'idle in transaction%'"'"'),
        count(*)
        FROM pg_stat_activity WHERE datname = '"'"'inventory_service'"'"' AND backend_type = '"'"'client backend'"'"'
          AND pid <> pg_backend_pid()" 2>/dev/null
      sleep 1
    done' > /dev/null
}
samplers_stop() { # $1 step
  docker logs inventory-bench-prom > "$OUT_DIR/$1-prometheus.txt" 2>/dev/null || true
  docker logs inventory-bench-pg > "$OUT_DIR/$1-pg-activity.txt" 2>/dev/null || true
  docker rm -f inventory-bench-prom inventory-bench-pg >/dev/null 2>&1 || true
}
stats_start() { # $1 file
  ( while true; do
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
        | grep "^$PROJECT-" | sed "s/^/$(date +%s),/"
    done > "$1" ) &
  STATS_PID=$!
}
hot_position() {
  psql_q "SELECT reserved, available_to_sell, revision, committed FROM stock_position WHERE sku_id = 'BENCH-HOT'"
}
# Server-side reconciliation, independent of what the client saw (a client timeout leaves the outcome unknown to k6
# while Inventory may still commit it): reservation lines per state against the buckets of the hot position.
hot_reconcile() {
  psql_q "SELECT (SELECT count(*) FILTER (WHERE state = 'RESERVED') FROM reservation_line WHERE sku_id = 'BENCH-HOT'),
      (SELECT count(*) FILTER (WHERE state = 'COMMITTED') FROM reservation_line WHERE sku_id = 'BENCH-HOT'),
      (SELECT count(*) FILTER (WHERE state = 'RELEASED') FROM reservation_line WHERE sku_id = 'BENCH-HOT'),
      reserved, committed, on_hand - available_to_sell - quarantine
      FROM stock_position WHERE sku_id = 'BENCH-HOT'"
}
db_counters() {
  psql_q "SELECT xact_commit, xact_rollback, deadlocks FROM pg_stat_database WHERE datname = 'inventory_service'"
}

for rate in $RATES; do
  step="rate-$rate"
  echo "== $VARIANT $SCENARIO $rate/s"
  psql_q "SELECT pg_stat_statements_reset()" >/dev/null
  echo "hot_before=$(hot_position)" > "$OUT_DIR/$step-db.txt"
  echo "db_before=$(db_counters)" >> "$OUT_DIR/$step-db.txt"
  samplers_start
  stats_start "$OUT_DIR/$step-docker-stats.csv"
  k6_run "$rate" "$DURATION" "$HOT_SHARE" "$RUN_ID-$SCENARIO-$rate" "$step-k6.json" | tee "$OUT_DIR/$step-k6.txt"
  kill "$STATS_PID" 2>/dev/null || true
  samplers_stop "$step"
  sleep 3   # requests still inside Inventory's retries when k6 stopped
  echo "hot_after=$(hot_position)" >> "$OUT_DIR/$step-db.txt"
  echo "hot_reconcile=$(hot_reconcile)" >> "$OUT_DIR/$step-db.txt"
  echo "db_after=$(db_counters)" >> "$OUT_DIR/$step-db.txt"
  psql_q "SELECT calls, round(total_exec_time::numeric, 1), round(mean_exec_time::numeric, 3),
      regexp_replace(left(query, 160), '\s+', ' ', 'g')
      FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = 'inventory_service'
      ORDER BY total_exec_time DESC LIMIT 25" > "$OUT_DIR/$step-sql.txt"
  sleep "$PAUSE"
done
echo "finished=$(date -Iseconds)" >> "$OUT_DIR/environment.txt"
echo "results: $OUT_DIR"
