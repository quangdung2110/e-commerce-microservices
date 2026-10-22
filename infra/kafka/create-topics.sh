#!/bin/sh
# Tạo mọi topic trong ${TOPICS_FILE} trên ${BOOTSTRAP_SERVER} (mesh ADR-002).
#
# Broker tắt auto.create.topics.enable, nên client không còn tạo topic bằng cách produce vào một
# tên bất kỳ. Script idempotent: --if-not-exists bỏ qua topic đã có và không sửa partition hay
# cấu hình của nó. Chạy bởi kafka-init (docker-compose) và Job kafka-topics-init (deploy/k8s).
# Exit khác 0 khi một topic không tạo được, để service phụ thuộc không khởi động trên broker thiếu topic.

set -eu

BOOTSTRAP_SERVER="${BOOTSTRAP_SERVER:-kafka:9092}"
TOPICS_FILE="${TOPICS_FILE:-/scripts/topics.txt}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

log() { echo "[kafka-init] $*"; }

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
until kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" --list >/dev/null 2>&1; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "broker $BOOTSTRAP_SERVER không sẵn sàng sau ${TIMEOUT_SECONDS}s"
    exit 1
  fi
  sleep 3
done

existing="$(kafka-topics --bootstrap-server "$BOOTSTRAP_SERVER" --list)"
created=0
kept=0
# Bỏ comment và dòng trống; mỗi dòng còn lại: tên, số partition, [cấu hình a=b,c=d].
sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "$TOPICS_FILE" > /tmp/topics.list
while read -r name partitions configs; do
  if printf '%s\n' "$existing" | grep -qx "$name"; then
    kept=$((kept + 1))
    continue
  fi
  set -- --bootstrap-server "$BOOTSTRAP_SERVER" --create --if-not-exists \
    --topic "$name" --partitions "${partitions:-1}" --replication-factor 1
  if [ -n "${configs:-}" ]; then
    for config in $(printf '%s' "$configs" | tr ',' ' '); do
      set -- "$@" --config "$config"
    done
  fi
  kafka-topics "$@" >/dev/null
  created=$((created + 1))
  log "đã tạo $name ($partitions partition)"
done < /tmp/topics.list

log "xong: tạo $created topic, $kept topic đã có"
