#!/usr/bin/env bash
# Search scale benchmark (docs/performance/search-scale-benchmark.md): seeds a synthetic catalog of each size into an
# isolated Elasticsearch, starts two isolated Search instances on it from the same jar — "grouping" (the SKU grouping of
# ADR-020) and "cards" (the product_cards projection of ADR-027) — and per size runs:
#   - loadtest/k6/search-scale.js MODE=latency: one VU through every scenario, both implementations side by side;
#   - MODE=throughput MIX=browse and MIX=home: VUS VUs for DURATION, each implementation in turn;
#   - loadtest/search-bench/compare.py: both implementations page through every scenario and sort and must agree.
#
#   scripts/ci/search-bench.sh                       # sizes 1000 10000 30000 60000
#   scripts/ci/search-bench.sh 5000 20000            # chosen sizes (products, 1-5 SKUs each)
#   ROUNDS=30 VUS=24 DURATION=60s scripts/ci/search-bench.sh
#   IMPLS=grouping scripts/ci/search-bench.sh        # one implementation only (grouping or cards)
#   SEARCH_JAR_SOURCE=/path/to/other.jar scripts/ci/search-bench.sh 60000   # measure a jar built elsewhere
#
# Needs the Search jar built (java/services/search-service/target, unless SEARCH_JAR_SOURCE is set). Uses its own compose
# project "search-bench" (own network and volume) and removes it at the end; the shared compose stack is not touched.
# Results go to loadtest/search-bench/results/<timestamp>/ (git-ignored).
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
SIZES=("$@")
[ ${#SIZES[@]} -gt 0 ] || SIZES=(1000 10000 30000 60000)
ROUNDS="${ROUNDS:-20}"
WARMUP="${WARMUP:-3}"
VUS="${VUS:-16}"
DURATION="${DURATION:-45s}"
IFS=',' read -r -a IMPL_NAMES <<< "${IMPLS:-grouping,cards}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
PY_IMAGE="${PY_IMAGE:-python:3.12-alpine}"
PROJECT=search-bench
NETWORK="${PROJECT}_default"
COMPOSE_FILE="$REPO_ROOT/loadtest/search-bench/compose.yaml"
RESULTS="${RESULTS:-$REPO_ROOT/loadtest/search-bench/results/$(date +%Y%m%d-%H%M%S)}"
JAR_SOURCE="${SEARCH_JAR_SOURCE:-$REPO_ROOT/java/services/search-service/target/search-service-1.0.0-SNAPSHOT.jar}"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
service_of() {
  case "$1" in
    grouping) echo search-service ;;
    cards) echo search-service-cards ;;
    *) echo "unknown implementation $1" >&2; exit 1 ;;
  esac
}
has_impl() { [[ " ${IMPL_NAMES[*]} " == *" $1 "* ]]; }

[ -f "$JAR_SOURCE" ] || { echo "missing $JAR_SOURCE; build Search first" >&2; exit 1; }
mkdir -p "$RESULTS"
# A copy, so rebuilding Search while the benchmark runs cannot change what is measured.
cp "$JAR_SOURCE" "$RESULTS/search.jar"
export SEARCH_JAR; SEARCH_JAR="$(host_path "$RESULTS/search.jar")"
{
  echo "jar_built=$(date -r "$JAR_SOURCE" '+%Y-%m-%d %H:%M:%S')"
  echo "git_head=$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
  echo "search_service_dirty=$(git -C "$REPO_ROOT" status --porcelain -- java/services/search-service/src/main | wc -l)"
  echo "sizes=${SIZES[*]} impls=${IMPL_NAMES[*]} rounds=$ROUNDS warmup=$WARMUP vus=$VUS duration=$DURATION"
  docker info --format 'docker_cpus={{.NCPU}} docker_mem={{.MemTotal}}'
} > "$RESULTS/environment.txt"

compose() { docker compose -p "$PROJECT" -f "$(host_path "$COMPOSE_FILE")" "$@"; }
cleanup() { compose down -v --remove-orphans >/dev/null 2>&1 || true; rm -f "$RESULTS/search.jar"; }
trap cleanup EXIT

in_service() { local service="$1"; shift; compose exec -T "$service" "$@"; }
wait_answering() { # $1 service
  for _ in $(seq 1 90); do
    if in_service "$1" wget -qO- http://localhost:8080/api/v2/search/categories >/dev/null 2>&1; then return 0; fi
    sleep 2
  done
  echo "$1 did not answer within 180s" >&2; compose logs --tail 80 "$1" >&2; return 1
}
wait_cards_ready() { # the projection mirrors the seeded generation and searches read it
  for _ in $(seq 1 150); do
    # readiness is checked lazily on a search, so ask for one before reading the gauge
    in_service search-service-cards wget -qO- "http://localhost:8080/api/v2/search/products?size=1" >/dev/null 2>&1 || true
    if in_service search-service-cards wget -qO- http://localhost:8080/actuator/prometheus 2>/dev/null \
        | grep -q "^search_product_cards_ready 1"; then return 0; fi
    sleep 2
  done
  echo "product_cards did not become ready within 300s" >&2; compose logs --tail 80 search-service-cards >&2; return 1
}

sample_stats() { # $1 service; background: CPU and memory of Elasticsearch and that Search every 3 s
  while :; do
    docker stats --no-stream --format "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" \
      "${PROJECT}-elasticsearch-1" "${PROJECT}-$1-1" 2>/dev/null | sed "s/^/$(date +%s)\t/"
    sleep 3
  done
}

impls_env() {
  local out=() impl
  for impl in "${IMPL_NAMES[@]}"; do out+=("$impl=http://$(service_of "$impl"):8080"); done
  (IFS=','; echo "${out[*]}")
}

run_k6() { # $1 size, $2 mode, $3 mix (throughput), $4 implementation (throughput)
  local name="$2${3:+-$3}${4:+-$4}"
  docker run --rm --network "$NETWORK" \
    -e MODE="$2" -e MIX="${3:-browse}" -e IMPL="${4:-}" -e IMPLS="$(impls_env)" \
    -e SEARCH_URL="http://$(service_of "${4:-${IMPL_NAMES[0]}}"):8080" \
    -e LABEL="$1" -e ROUNDS="$ROUNDS" -e WARMUP="$WARMUP" -e VUS="$VUS" -e DURATION="$DURATION" \
    -v "$(host_path "$REPO_ROOT/loadtest/k6"):/scripts:ro" -v "$(host_path "$RESULTS/$1"):/out" \
    "$K6_IMAGE" run --quiet /scripts/search-scale.js 2>&1 | tee "$RESULTS/$1/k6-$name.txt"
}

run_throughput() { # $1 size, $2 mix, $3 implementation: docker stats are sampled for this run alone
  sample_stats "$(service_of "$3")" > "$RESULTS/$1/docker-stats-$2-$3.tsv" &
  local stats_pid=$!
  run_k6 "$1" throughput "$2" "$3"
  kill "$stats_pid" 2>/dev/null || true
  wait "$stats_pid" 2>/dev/null || true
}

compose up -d elasticsearch
compose up -d --wait elasticsearch

for size in "${SIZES[@]}"; do
  echo "=== $size products ==="
  size_started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  mkdir -p "$RESULTS/$size"
  compose stop search-service search-service-cards >/dev/null 2>&1 || true
  docker run --rm --network "$NETWORK" \
    -v "$(host_path "$REPO_ROOT/loadtest/search-bench"):/seed:ro" \
    -v "$(host_path "$REPO_ROOT/java/services/search-service/src/main/resources/search/schema"):/schema:ro" \
    -v "$(host_path "$RESULTS/$size"):/out" \
    "$PY_IMAGE" python -I /seed/seed.py --es http://elasticsearch:9200 --products "$size" --schema-dir /schema --out /out
  # A fresh JVM per size, so heap and JIT state do not carry over between catalogs. One at a time: two instances
  # bootstrapping search_jobs on an empty cluster at once can race.
  for impl in "${IMPL_NAMES[@]}"; do
    compose up -d --force-recreate "$(service_of "$impl")"
    wait_answering "$(service_of "$impl")"
  done
  if has_impl cards; then
    wait_cards_ready
    compose logs --no-color search-service-cards 2>/dev/null | grep -E "product cards .* published" | tail -1 \
      | sed "s/.* - //" > "$RESULTS/$size/product-cards-build.txt" || true
  fi

  run_k6 "$size" latency
  for mix in browse home; do
    for impl in "${IMPL_NAMES[@]}"; do run_throughput "$size" "$mix" "$impl"; done
  done
  if has_impl grouping && has_impl cards; then
    docker run --rm --network "$NETWORK" -v "$(host_path "$REPO_ROOT/loadtest/search-bench"):/seed:ro" \
      -v "$(host_path "$RESULTS/$size"):/out:ro" "$PY_IMAGE" python -I /seed/compare.py \
      --old http://search-service:8080 --new http://search-service-cards:8080 --manifest /out/manifest.json \
      > "$RESULTS/$size/compare.txt" 2>&1 || true
    tail -3 "$RESULTS/$size/compare.txt"
  fi
  for impl in "${IMPL_NAMES[@]}"; do
    service="$(service_of "$impl")"
    in_service "$service" wget -qO- http://localhost:8080/actuator/prometheus \
      | grep -E "^(jvm_memory_used_bytes\{area=\"heap\"|jvm_memory_max_bytes\{area=\"heap\"|jvm_gc_pause_seconds_(count|sum|max)|es_query_duration_seconds_(count|sum|max)|http_server_requests_seconds_(count|sum|max)\{.*search/products|search_product_cards)" \
      > "$RESULTS/$size/search-metrics-$impl.txt" || true
    compose logs --no-color "$service" 2>/dev/null | grep -E "OutOfMemory|ERROR|WARN" > "$RESULTS/$size/search-errors-$impl.log" || true
  done
  compose logs --no-color --since "${size_started}" elasticsearch 2>/dev/null \
    | grep -E "WARN|ERROR|Exception|breaker" > "$RESULTS/$size/elasticsearch-warnings.log" || true
done

echo "results: $RESULTS"
