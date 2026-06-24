FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
COPY mvnw ./
COPY .mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget && \
    addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/target/inventory-service-*.jar app.jar
USER appuser
EXPOSE 8083
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8083/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]