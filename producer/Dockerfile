FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -e dependency:go-offline || true
COPY src ./src
RUN mvn -q clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/producer-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
