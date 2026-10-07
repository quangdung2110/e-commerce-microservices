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
# Stage 2: Runtime with JRE only (slim image)
# ============================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache wget && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /app/target/promotion-service-1.0.0-SNAPSHOT.jar app.jar

USER appuser

ENV JAVA_OPTS="-Xms512m -Xmx1024m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dsun.net.inetaddr.ttl=60"

ENV SERVER_PORT=8085

EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:${SERVER_PORT:-8085}/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
