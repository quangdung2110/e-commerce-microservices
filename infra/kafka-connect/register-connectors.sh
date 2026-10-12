#!/bin/sh
# Đăng ký các Debezium outbox connector lên Kafka Connect của docker-compose.
#
# Idempotent: connector chưa có thì tạo, đang RUNNING thì bỏ qua, FAILED thì xoá rồi tạo lại.
# Chạy lặp lại cho tới khi mọi connector RUNNING hoặc hết TIMEOUT_SECONDS, vì Debezium chỉ tạo
# được publication khi bảng outbox đã tồn tại (Flyway của từng service chạy sau Postgres).
# Replication slot không bị xoá khi xoá connector nên không mất event trong lúc tạo lại.
#
# Artifact nằm trong từng service (java/services/<svc>-service/src/main/resources/kafka/);
# compose mount chúng vào ${CONNECTOR_DIR}. Placeholder ${VAR:default} trong artifact được
# thay bằng default (Connect không tự phân giải cú pháp này); ${routedByValue} không có dấu
# hai chấm nên giữ nguyên cho EventRouter.

set -u

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
CONNECTOR_DIR="${CONNECTOR_DIR:-/connectors}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-600}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"

log() { echo "[kafka-connect-init] $*"; }

connector_name() {
  sed -n 's/^[[:space:]]*"name":[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -n 1
}

# 0 khi connector và mọi task đều RUNNING.
is_running() {
  status="$(curl -sf "$CONNECT_URL/connectors/$1/status")" || return 1
  echo "$status" | grep -q '"connector":{"state":"RUNNING"' || return 1
  echo "$status" | grep -q '"tasks":\[{' || return 1
  ! echo "$status" | grep -q '"state":"\(FAILED\|UNASSIGNED\|PAUSED\)"'
}

is_failed() {
  curl -sf "$CONNECT_URL/connectors/$1/status" | grep -q '"state":"FAILED"'
}

exists() {
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$CONNECT_URL/connectors/$1")" = "200" ]
}

create() {
  sed -E 's/\$\{[A-Za-z_]+:([^}]*)\}/\1/g' "$2" |
    curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      --data-binary @- "$CONNECT_URL/connectors"
}

files="$(ls "$CONNECTOR_DIR"/*.json 2>/dev/null)"
if [ -z "$files" ]; then
  log "no connector artifacts in $CONNECTOR_DIR"
  exit 1
fi

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
while :; do
  pending=""
  for file in $files; do
    name="$(connector_name "$file")"
    if [ -z "$name" ]; then
      log "cannot read connector name from $file"
      exit 1
    fi
    if is_running "$name"; then
      continue
    fi
    pending="$pending $name"
    if exists "$name"; then
      if is_failed "$name"; then
        log "$name is FAILED, recreating"
        curl -s -o /dev/null -X DELETE "$CONNECT_URL/connectors/$name"
        log "$name create -> HTTP $(create "$name" "$file")"
      fi
    else
      log "$name create -> HTTP $(create "$name" "$file")"
    fi
  done
  if [ -z "$pending" ]; then
    log "all connectors RUNNING"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "timeout; not RUNNING:$pending"
    exit 1
  fi
  sleep "$INTERVAL_SECONDS"
done
