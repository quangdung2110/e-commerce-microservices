# Theo thiết kế docs/shipping/technology_choice.md mục 4: Java 21 + Maven cho Shipping Service.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/shipping-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java","-jar","/app/app.jar"]
