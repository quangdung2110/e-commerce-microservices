#!/bin/sh
# Đăng ký các Debezium outbox connector lên Kafka Connect của docker-compose.
#
# Idempotent: connector chưa có thì tạo; config đang chạy khác artifact thì PUT config mới (Connect
# khởi động lại connector với offset đã lưu); FAILED thì xoá rồi tạo lại; còn lại bỏ qua.
# Chạy lặp lại cho tới khi mọi connector RUNNING hoặc hết TIMEOUT_SECONDS, vì Debezium chỉ tạo
# được publication khi bảng outbox đã tồn tại (Flyway của từng service chạy sau Postgres).
# Replication slot không bị xoá khi xoá connector nên không mất event trong lúc tạo lại.
#
# Artifact nằm trong từng service (java/services/<svc>-service/src/main/resources/kafka/, Review ở
# .../debezium/); compose mount chúng vào ${CONNECTOR_DIR}. Placeholder ${VAR:default} (VAR viết
# hoa) trong artifact được thay bằng default, vì Connect không tự phân giải cú pháp này.
# ${routedByValue} không có dấu hai chấm nên giữ nguyên cho EventRouter; ${env:SCRAM_PASSWORD_…}
# (mật khẩu principal Kafka của connector, mesh ADR-003) giữ nguyên cho config provider của worker.

set -u

CONNECT_URL="${CONNECT_URL:-http://kafka-connect:8083}"
CONNECTOR_DIR="${CONNECTOR_DIR:-/connectors}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-600}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"

log() { echo "[kafka-connect-init] $*"; }

connector_name() {
  sed -n 's/^[[:space:]]*"name":[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -n 1
}

# Artifact sau khi thay placeholder.
render() {
  sed -E 's/\$\{[A-Z_][A-Z0-9_]*:([^}]*)\}/\1/g' "$1"
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

# 0 khi config đang lưu trong Connect khác artifact (so sánh JSON, không theo thứ tự khoá).
drifted() {
  current="$(curl -sf "$CONNECT_URL/connectors/$1/config")" || return 1
  render "$2" | python3 -c '
import json, sys
want = {k: v if isinstance(v, str) else json.dumps(v) for k, v in json.load(sys.stdin)["config"].items()}
have = json.loads(sys.argv[1])
want.pop("name", None)
have.pop("name", None)
sys.exit(0 if want != have else 1)
' "$current"
}

create() {
  render "$2" |
    curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      --data-binary @- "$CONNECT_URL/connectors"
}

update() {
  render "$2" | python3 -c 'import json, sys; print(json.dumps(json.load(sys.stdin)["config"]))' |
    curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' \
      --data-binary @- "$CONNECT_URL/connectors/$1/config"
}

files="$(ls "$CONNECTOR_DIR"/*.json 2>/dev/null)"
if [ -z "$files" ]; then
  log "no connector artifacts in $CONNECTOR_DIR"
  exit 1
fi

deadline=$(( $(date +%s) + TIMEOUT_SECONDS ))
updated=""
while :; do
  pending=""
  for file in $files; do
    name="$(connector_name "$file")"
    if [ -z "$name" ]; then
      log "cannot read connector name from $file"
      exit 1
    fi
    # Một lần mỗi lượt chạy: nếu Connect chuẩn hoá một giá trị, không PUT lặp mãi.
    case " $updated " in *" $name "*) ;; *)
      if exists "$name" && drifted "$name" "$file"; then
        log "$name config differs from $file, updating -> HTTP $(update "$name" "$file")"
        updated="$updated $name"
        pending="$pending $name"
        continue
      fi ;;
    esac
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
