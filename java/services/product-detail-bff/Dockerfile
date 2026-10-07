# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 1001 pdp && \
    apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER pdp
EXPOSE 8080
# Liveness is process-only: an Inventory or Review outage degrades the PDP instead of
# restarting instances (docs/product-detail-bff/operations.md).
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "app.jar"]
