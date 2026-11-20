#!/bin/bash
# Entrypoint của broker Kafka local (cp-kafka, chế độ ZooKeeper) với SASL/SCRAM + ACL (mesh ADR-003).
#
# Broker xác thực inter-broker bằng SCRAM, nên credential của kafka-broker phải có trong ZooKeeper
# trước khi broker khởi động; kafka-admin (super user của kafka-init và công cụ dev) được tạo cùng
# lúc. Hai lệnh idempotent: chạy lại chỉ đặt lại mật khẩu (salt mới). Mọi user khác do kafka-init
# tạo qua listener SASL bằng kafka-admin (infra/kafka/sync-acls.sh).
#
# Ghi /etc/kafka/admin.properties cho công cụ chạy trong container này (healthcheck, `docker exec
# kafka kafka-topics --command-config /etc/kafka/admin.properties …`).
#
# Biến không bắt đầu bằng KAFKA_ vì ảnh cp-kafka biến mọi KAFKA_* thành dòng của kafka.properties.
# Mật khẩu không được chứa ',', '[', ']' hay '"' (cú pháp của kafka-configs và JAAS).

set -euo pipefail

: "${SCRAM_BROKER_USERNAME:?}" "${SCRAM_BROKER_PASSWORD:?}"
: "${SCRAM_ADMIN_USERNAME:?}" "${SCRAM_ADMIN_PASSWORD:?}"
: "${KAFKA_ZOOKEEPER_CONNECT:?}"

log() { echo "[kafka-entrypoint] $*"; }

cub zk-ready "$KAFKA_ZOOKEEPER_CONNECT" "${KAFKA_CUB_ZK_TIMEOUT:-60}"

for user in broker admin; do
  if [ "$user" = broker ]; then
    name="$SCRAM_BROKER_USERNAME"; password="$SCRAM_BROKER_PASSWORD"
  else
    name="$SCRAM_ADMIN_USERNAME"; password="$SCRAM_ADMIN_PASSWORD"
  fi
  KAFKA_HEAP_OPTS="-Xmx128m" kafka-configs --zookeeper "$KAFKA_ZOOKEEPER_CONNECT" --alter \
    --entity-type users --entity-name "$name" \
    --add-config "SCRAM-SHA-512=[password=${password}]" >/dev/null
  log "SCRAM credential của $name đã có trong ZooKeeper"
done

cat > /etc/kafka/admin.properties <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="${SCRAM_ADMIN_USERNAME}" password="${SCRAM_ADMIN_PASSWORD}";
EOF

# Inter-broker: listener INTERNAL dùng chính kafka-broker. Listener HOST chỉ xác thực client.
export KAFKA_LISTENER_NAME_INTERNAL_SCRAM___SHA___512_SASL_JAAS_CONFIG="org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${SCRAM_BROKER_USERNAME}\" password=\"${SCRAM_BROKER_PASSWORD}\";"
export KAFKA_LISTENER_NAME_HOST_SCRAM___SHA___512_SASL_JAAS_CONFIG="org.apache.kafka.common.security.scram.ScramLoginModule required;"
export KAFKA_SUPER_USERS="User:${SCRAM_BROKER_USERNAME};User:${SCRAM_ADMIN_USERNAME}"

exec /etc/confluent/docker/run
