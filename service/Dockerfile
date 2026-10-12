# Build stage: compile, test and package a single runnable jar.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

# Runtime stage: JRE only.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/dj-service.jar .
USER nobody
EXPOSE 9010
ENTRYPOINT ["java", "-jar", "/app/dj-service.jar"]
