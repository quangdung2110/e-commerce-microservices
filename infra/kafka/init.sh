#!/bin/sh
# kafka-init (docker-compose) và Job kafka-topics-init (deploy/k8s): chạy KafkaInit.java bằng source
# launcher của JDK trong image cp-kafka — tạo topic của topics.txt, user SCRAM và ACL của acls.txt
# (mesh ADR-002, ADR-003). Chạy lại tay: `docker compose up kafka-init`.

set -eu

DIR="$(cd "$(dirname "$0")" && pwd)"

# Log của kafka-clients chỉ từ WARN, ra stderr; không có file log4j nào khác trong container này.
cat > /tmp/kafka-init-log4j.properties <<'EOF'
log4j.rootLogger=WARN, stderr
log4j.appender.stderr=org.apache.log4j.ConsoleAppender
log4j.appender.stderr.Target=System.err
log4j.appender.stderr.layout=org.apache.log4j.PatternLayout
log4j.appender.stderr.layout.ConversionPattern=[%d] %p %m (%c)%n
EOF

exec java -Xmx256m -Dlog4j.configuration=file:/tmp/kafka-init-log4j.properties \
  -cp '/usr/share/java/kafka/*' "$DIR/KafkaInit.java"
