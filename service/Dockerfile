# Build stage: always runs on the build machine's own CPU; the jar runs anywhere.
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package
# Made here, not in the runtime stage: that stage has no RUN, so images for other CPUs
# (Raspberry Pi) build without emulation.
RUN mkdir /data

# Runtime stage: JRE only.
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="dj" \
      org.opencontainers.image.description="AI 'what to play next' service for Lyrion Music Server" \
      org.opencontainers.image.source="https://github.com/geir-eilertsen/dj"
WORKDIR /app
COPY --from=build /build/target/dj-service.jar .
# A new named volume mounted here starts with this ownership, so "nobody" (65534) can write the databases.
COPY --from=build --chown=65534:65534 /data /data
ENV DJ_DATA_DIR=/data
USER 65534
EXPOSE 9010
ENTRYPOINT ["java", "-jar", "/app/dj-service.jar"]
