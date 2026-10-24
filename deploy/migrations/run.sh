#!/bin/sh
# Up only. Application rollbacks never run database down migrations.
set -eu
case "${MIGRATION_SERVICE:-}" in
  product-service|review-service|promotion-service|inventory-service|order-service|payment-service)
    : "${FLYWAY_URL:?required}" "${FLYWAY_USER:?required}" "${FLYWAY_PASSWORD:?required}"
    export FLYWAY_LOCATIONS="filesystem:/sql/java/$MIGRATION_SERVICE"
    export FLYWAY_CLEAN_DISABLED=true FLYWAY_CONNECT_RETRIES=30 FLYWAY_VALIDATE_MIGRATION_NAMING=true
    if /flyway/flyway migrate > /tmp/migration.log 2>&1; then
      echo 'Flyway migration completed'
    else
      echo 'Flyway migration failed; database connection details redacted' >&2
      exit 1
    fi
    ;;
  auth-service|user-service|notification-service)
    : "${MIGRATE_DATABASE_URL:?required}"
    if /usr/local/bin/migrate -path "/sql/go/$MIGRATION_SERVICE" -database "$MIGRATE_DATABASE_URL" up > /tmp/migration.log 2>&1; then
      echo 'Go migration completed'
    else
      echo 'Go migration failed; database connection details redacted' >&2
      exit 1
    fi
    ;;
  *) echo 'Unknown migration owner; refusing to run' >&2; exit 2 ;;
esac
