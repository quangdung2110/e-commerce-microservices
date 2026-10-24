# SQL is versioned and signed with application releases; never mounted from the runner.
FROM migrate/migrate:v4.20.1@sha256:76cc2074cb6642631f34a898ced71e6aeaa6b1a4d78c4daa743275a22e0c5be7 AS gomigrate
FROM flyway/flyway:13.10.0-alpine@sha256:c0c2592aa3ab9edf4dde3939d36df3d5f6ee27b8dcc51924ad8bca2470d24fdd
COPY --from=gomigrate /usr/local/bin/migrate /usr/local/bin/migrate
COPY java/services/product-service/src/main/resources/db/migration/ /sql/java/product-service/
COPY java/services/review-service/src/main/resources/db/migration/ /sql/java/review-service/
COPY java/services/promotion-service/src/main/resources/db/migration/ /sql/java/promotion-service/
COPY java/services/inventory-service/src/main/resources/db/migration/ /sql/java/inventory-service/
COPY java/services/order-service/src/main/resources/db/migration/ /sql/java/order-service/
COPY java/services/payment-service/src/main/resources/db/migration/ /sql/java/payment-service/
COPY go/services/auth-service/migrations/ /sql/go/auth-service/
COPY go/services/user-service/migrations/ /sql/go/user-service/
COPY go/services/notification-service/migrations/ /sql/go/notification-service/
COPY deploy/migrations/run.sh /run-migrations.sh
ENV HOME=/tmp
USER 10001:10001
ENTRYPOINT ["/bin/sh", "/run-migrations.sh"]
