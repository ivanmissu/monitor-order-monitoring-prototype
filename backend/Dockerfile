# syntax=docker/dockerfile:1.7

# Development image: JDK 25 + Maven 3.9.12 + pre-warmed Monitor dependencies.
FROM maven:3.9.12-eclipse-temurin-25

LABEL org.opencontainers.image.title="Monitor backend development environment" \
      org.opencontainers.image.description="JDK 25, Maven 3.9.12 and cached dependencies for the Monitor Spring Boot backend" \
      org.opencontainers.image.source="https://github.com/ivanmissu/monitor-order-monitoring-prototype" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV MAVEN_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

WORKDIR /workspace

# Keep dependency download in a reusable layer. Warm both the lean demo graph and
# the default production integration graph so the image can be used for either.
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress \
        -Dspring-boot.run.profiles=demo \
        dependency:go-offline \
    && mvn --batch-mode --no-transfer-progress \
        -Pproduction-integrations \
        dependency:go-offline

COPY src ./src

# Fail image construction if the dependency cache or the Demo profile is broken.
RUN mvn --batch-mode --no-transfer-progress \
        -Dspring-boot.run.profiles=demo \
        package

EXPOSE 8080
STOPSIGNAL SIGTERM

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health || exit 1

CMD ["mvn", "--batch-mode", "--no-transfer-progress", "spring-boot:run", "-Dspring-boot.run.profiles=demo"]
