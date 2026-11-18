# SQL is versioned and signed with application releases; never mounted from the runner.
FROM golang:1.26.11-alpine@sha256:ecb67f987aa1a57794c91d19ade7ae0209f2f0f5496aaef9e6ae85ddf92d02ab AS gomigrate
# The deployment Job only migrates PostgreSQL. Build just that database driver.
RUN CGO_ENABLED=0 GOBIN=/out go install -trimpath -tags postgres github.com/golang-migrate/migrate/v4/cmd/migrate@v4.20.1


FROM flyway/flyway:13.12.2-alpine@sha256:f5b9740a05ef38fc31356dcf2e7796dafc693ea289546e82c7699d37f83349af
# Patch the shared libraries and remove plugins for engines this runner never uses.
USER root
ADD --chmod=0644 --checksum=sha256:b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c https://repo.maven.apache.org/maven2/com/google/guava/guava/33.7.2-jre/guava-33.7.2-jre.jar /flyway/drivers/gcp/guava-33.7.2-jre.jar
ADD --chmod=0644 --checksum=sha256:2d137aa308c78932878952fe3624ea090ecfe644414b8b23e71a305f425698c5 https://repo.maven.apache.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.2.1.jre11/mssql-jdbc-13.2.1.jre11.jar /flyway/drivers/mssql-jdbc-13.2.1.jre11.jar
RUN rm /flyway/drivers/gcp/guava-33.5.0-jre.jar /flyway/drivers/mssql-jdbc-12.10.2.jre11.jar \
       /flyway/drivers/databricks-jdbc-3.4.2.jar /flyway/lib/flyway/flyway-database-databricks-13.12.2.jar \
       /flyway/lib/flyway/flyway-database-nc-couchbase-13.12.2.jar \
    && rm -rf /flyway/drivers/couchbase
COPY --from=gomigrate /out/migrate /usr/local/bin/migrate
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
