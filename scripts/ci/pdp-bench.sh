#!/usr/bin/env bash
# PDP catalog cache benchmark (docs/performance/pdp-catalog-cache-benchmark-2026-10-17.md).
#
# Runs one BFF variant on the isolated pdp-bench stack (loadtest/pdp-bench/compose.yaml) through an open-model k6 load
# (loadtest/k6/pdp-cache.js) at a list of arrival rates, and records per step:
#   k6 summary (latency, statuses, dropped iterations), container CPU (docker stats), SQL statements per database
#   (pg_stat_statements), BFF dependency/cache counters and Product Hikari pending (Prometheus samples every 2 s).
#
#   scripts/ci/pdp-bench.sh <variant> <jar> <env-file>
#   RATES="150 200 300" DIST=zipf DURATION=60s scripts/ci/pdp-bench.sh baseline loadtest/pdp-bench/jars/bff-baseline.jar \
#       loadtest/pdp-bench/baseline.env
#
# Each run restarts Product, Inventory, Review and the BFF, warms Product directly (no BFF circuit breaker in the way:
# a cold Product behind the breaker never warms up) and then the whole path, so both variants start from the same
# state. Seed the catalog once with SEED=1. The shared compose stack is not touched.
# Results go to loadtest/pdp-bench/results/<RUN_ID>/<variant>-<dist>/ (git-ignored).
set -euo pipefail

VARIANT="${1:?variant name}"
JAR="${2:?BFF jar}"
ENV_FILE="${3:?BFF env file}"
REPO_ROOT="$(git rev-parse --show-toplevel)"
RATES="${RATES:-150 200 300 400 500 600 800 1000}"
DIST="${DIST:-zipf}"
DURATION="${DURATION:-60s}"
PAUSE="${PAUSE:-15}"
PRODUCTS="${PRODUCTS:-10000}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
PROJECT=pdp-bench
NETWORK="${PROJECT}_default"
BENCH_DIR="$REPO_ROOT/loadtest/pdp-bench"
OUT_DIR="$BENCH_DIR/results/$RUN_ID/$VARIANT-$DIST"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

mkdir -p "$OUT_DIR"
export BFF_JAR; BFF_JAR="$(host_path "$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")")"
export BFF_ENV_FILE; BFF_ENV_FILE="$(host_path "$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")")"
compose() { docker compose -p "$PROJECT" -f "$(host_path "$BENCH_DIR/compose.yaml")" "$@"; }
psql_q() { compose exec -T postgres psql -U postgres -d "$1" -At -c "$2"; }

{
  echo "variant=$VARIANT dist=$DIST rates=$RATES duration=$DURATION pause=$PAUSE products=$PRODUCTS"
  echo "jar_sha256=$(sha256sum "$JAR" | cut -d' ' -f1)"
  echo "env_file:"; sed 's/^/  /' "$ENV_FILE"
  echo "git_head=$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  echo "bff_src_dirty_files=$(git -C "$REPO_ROOT" status --porcelain -- java/services/product-detail-bff/src | wc -l)"
  for image in product-service inventory-service review-service; do
    echo "image_$image=$(docker image inspect "e-commerce-microservices-$image" --format '{{.Id}} {{.Created}}')"
  done
  docker info --format 'docker_cpus={{.NCPU}} docker_mem={{.MemTotal}}'
  echo "shared_stack_containers=$(docker ps --format '{{.Names}}' | grep -vc '^pdp-bench-' || true)"
  echo "started=$(date -Iseconds)"
} > "$OUT_DIR/environment.txt"

compose up -d postgres >/dev/null
for _ in $(seq 1 60); do compose exec -T postgres pg_isready -U postgres >/dev/null 2>&1 && break; sleep 2; done
if [ "${SEED:-0}" = "1" ]; then
  compose up -d product-service inventory-service review-service >/dev/null
  sleep 40   # Flyway migrations of the three services
  compose exec -T postgres psql -U postgres -d product_service -v "products=$PRODUCTS" -f /seed/product.sql
  compose exec -T postgres psql -U postgres -d inventory_service -v "products=$PRODUCTS" -f /seed/inventory.sql
fi
psql_q product_service "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null

wait_ready() { # $1 service, $2 port
  for _ in $(seq 1 90); do
    if compose exec -T "$1" sh -c "wget -qO- http://localhost:$2/actuator/health/readiness 2>/dev/null \
        || curl -fs http://localhost:$2/actuator/health/readiness" 2>/dev/null | grep -q UP; then return 0; fi
    sleep 2
  done
  echo "$1 not ready within 180s" >&2; compose logs --tail 60 "$1" >&2; return 1
}

echo "== restart services ($VARIANT)"
compose up -d --force-recreate product-service inventory-service review-service >/dev/null
wait_ready product-service 8081; wait_ready inventory-service 8080; wait_ready review-service 8080
compose up -d --force-recreate product-detail-bff >/dev/null
wait_ready product-detail-bff 8080

k6_run() { # $1 target, $2 rate, $3 duration, $4 dist, $5 output file name
  docker run --rm --network "$NETWORK" -e TARGET="$1" -e RATE="$2" -e DURATION="$3" -e DIST="$4" \
    -e PRODUCTS="$PRODUCTS" -e OUT="/out/$5" \
    -v "$(host_path "$REPO_ROOT/loadtest/k6"):/scripts:ro" -v "$(host_path "$OUT_DIR"):/out" \
    "$K6_IMAGE" run --quiet /scripts/pdp-cache.js 2>&1 | grep '^target=' || true
}

echo "== warm-up"
mkdir -p "$OUT_DIR/warmup"
if [ "${SKIP_WARMUP:-0}" != "1" ]; then
for rate in 20 50 100 200 300 400; do k6_run product "$rate" 20s uniform "warmup/product-$rate.json"; done
for rate in 30 60 120 200; do k6_run bff "$rate" 20s uniform "warmup/bff-$rate.json"; done
fi
sleep "$PAUSE"

# Prometheus sampler: a throwaway container on the bench network, so sampling costs no CPU inside the measured
# services' limits.
sampler_start() {
  docker rm -f pdp-bench-sampler >/dev/null 2>&1 || true
  docker run -d --network "$NETWORK" --name pdp-bench-sampler alpine:3.20 sh -c '
    while true; do
      ts=$(date +%s)
      wget -qO- http://product-service:8081/actuator/prometheus 2>/dev/null \
        | grep -E "^hikaricp_connections_(pending|active)" | sed "s/^/$ts product /"
      wget -qO- http://product-detail-bff:8080/actuator/prometheus 2>/dev/null \
        | grep -E "^(pdp_dependency_calls_seconds_count|pdp_catalog_cache_)" | sed "s/^/$ts bff /"
      sleep 2
    done' > /dev/null
}
sampler_stop() { # $1 file
  docker logs pdp-bench-sampler > "$1" 2>/dev/null || true
  docker rm -f pdp-bench-sampler >/dev/null 2>&1 || true
}
stats_start() { # $1 file
  ( while true; do
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' 2>/dev/null \
        | grep '^pdp-bench-' | sed "s/^/$(date +%s),/"
    done > "$1" ) &
  STATS_PID=$!
}

for rate in $RATES; do
  step="rate-$rate"
  echo "== $VARIANT $DIST $rate/s"
  psql_q product_service "SELECT pg_stat_statements_reset()" >/dev/null
  psql_q product_service "SELECT datname, xact_commit FROM pg_stat_database WHERE datname LIKE '%_service'" \
    > "$OUT_DIR/$step-xact-before.txt"
  sampler_start
  stats_start "$OUT_DIR/$step-docker-stats.csv"
  k6_run bff "$rate" "$DURATION" "$DIST" "$step-k6.json" | tee "$OUT_DIR/$step-k6.txt"
  kill "$STATS_PID" 2>/dev/null || true
  sampler_stop "$OUT_DIR/$step-prometheus.txt"
  psql_q product_service "SELECT d.datname, sum(s.calls) FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
      GROUP BY d.datname ORDER BY 1" > "$OUT_DIR/$step-sql-calls.txt"
  psql_q product_service "SELECT datname, xact_commit FROM pg_stat_database WHERE datname LIKE '%_service'" \
    > "$OUT_DIR/$step-xact-after.txt"
  sleep "$PAUSE"
done
echo "finished=$(date -Iseconds)" >> "$OUT_DIR/environment.txt"
echo "results: $OUT_DIR"
