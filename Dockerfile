FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY gradlew build.gradle settings.gradle fuseki.ttl ./
COPY gradle/ gradle/
COPY src/ src/
RUN ./gradlew build --no-daemon --max-workers=2

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.source="https://github.com/foederierter-datenpool/directory-api"
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10000 app \
    && useradd --system --uid 10000 --gid 10000 --no-create-home app

WORKDIR /app
COPY --from=build /build/build/libs/directory-api.jar ./app.jar
COPY --from=build /build/build/fuseki/fuseki-server.jar /build/fuseki.ttl ./
USER app
EXPOSE 8080 3030
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/readiness
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
