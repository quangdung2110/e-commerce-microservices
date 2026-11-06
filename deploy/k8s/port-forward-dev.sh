#!/usr/bin/env bash
# Mở các cổng host của docker-compose (psql :15432, Redis :6379, Kafka :29092, Connect :8084, cổng
# service 8081…8096…) trên 127.0.0.1 bằng `kubectl port-forward`. Connect và Elasticsearch dùng HTTPS
# với xác thực như mô tả trong README. Cụm không mở LoadBalancer cho chúng (mesh ADR-002): Docker Desktop nghe
# trên mọi interface của máy, mà service tin header danh tính và hạ tầng không xác thực mạnh.
# Ctrl+C để đóng. Gateway (8088) và frontend (3000) vẫn là LoadBalancer, không cần script này.
#
#   bash deploy/k8s/port-forward-dev.sh            # hạ tầng + service
#   bash deploy/k8s/port-forward-dev.sh infra      # chỉ hạ tầng
#   bash deploy/k8s/port-forward-dev.sh services   # chỉ service (danh sách sinh từ compose)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
layer="${1:-all}"

# Hạ tầng viết tay ở deploy/k8s/infra; cổng giống docker-compose.
infra=(
  "data svc/postgres 15432:5432"
  "data svc/redis 6379:6379"
  # 29092 là cổng container (listener PLAINTEXT_HOST), không phải cổng của Service headless kafka.
  "data pod/kafka-0 29092:29092"
  "data svc/kafka-connect 8084:8083"
  "data svc/elasticsearch 9200:9200"
  "data svc/minio 9000:9000"
  "data svc/minio 9001:9001"
  "data svc/temporal 7233:7233"
  "data svc/temporal-ui 8233:8080"
)

forwards=()
case "$layer" in
  all|infra) forwards+=("${infra[@]}") ;;
esac
case "$layer" in
  all|services)
    # Sinh bởi generate.py từ các cổng compose bind 127.0.0.1.
    while IFS= read -r line; do
      [[ -z "$line" || "$line" == \#* ]] || forwards+=("$line")
    done < "$root/services/port-forwards.txt"
    ;;
  infra) ;;
  *) echo "lớp không hợp lệ: $layer (all | infra | services)" >&2; exit 1 ;;
esac

pids=()
trap 'kill "${pids[@]}" 2>/dev/null' EXIT INT TERM
for forward in "${forwards[@]}"; do
  read -r namespace target ports <<< "$forward"
  kubectl -n "$namespace" port-forward --address 127.0.0.1 "$target" "$ports" >/dev/null &
  pids+=($!)
done
echo "đã mở ${#forwards[@]} cổng trên 127.0.0.1 (Ctrl+C để đóng)"
wait
