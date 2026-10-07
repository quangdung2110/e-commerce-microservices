# ============================================================
# Stage 1: Build with Maven
# ============================================================
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml ./
RUN mvn dependency:go-offline -q

COPY src ./src

RUN mvn clean package -DskipTests -q

# ============================================================
# Stage 2: Runtime with JRE only
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache wget && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/inventory-service-1.0.0-SNAPSHOT.jar app.jar

USER appuser

ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
