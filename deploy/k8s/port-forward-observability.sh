#!/usr/bin/env bash
# Mở UI observability trên 127.0.0.1 với đúng cổng của docker-compose (compose chỉ bind loopback cho các UI
# này, nên cụm không mở LoadBalancer cho chúng). Ctrl+C để đóng.
#   Grafana http://localhost:3001  Prometheus :9090  Alertmanager :9093  Loki :3100  Jaeger :16686
set -euo pipefail

forwards=(
  "svc/grafana 3001:3000"
  "svc/prometheus 9090:9090"
  "svc/alertmanager 9093:9093"
  "svc/loki 3100:3100"
  "svc/jaeger 16686:16686"
)

pids=()
trap 'kill "${pids[@]}" 2>/dev/null' EXIT INT TERM
for forward in "${forwards[@]}"; do
  # shellcheck disable=SC2086
  kubectl -n observability port-forward $forward >/dev/null &
  pids+=($!)
done
echo "Grafana http://localhost:3001 · Prometheus :9090 · Alertmanager :9093 · Loki :3100 · Jaeger :16686 (Ctrl+C để đóng)"
wait
