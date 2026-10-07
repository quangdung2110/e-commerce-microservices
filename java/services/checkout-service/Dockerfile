# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B -Dos.detected.classifier=linux-x86_64
COPY proto ./proto
COPY src ./src
RUN mvn package -DskipTests -B -Dos.detected.classifier=linux-x86_64

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 1001 checkout && \
    apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER checkout
EXPOSE 8080
# Liveness is process-only: a dependency outage must never restart instances; circuits and
# 503 problems handle it (docs/checkout/operations.md).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health/live || exit 1
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
