# Mesta-Asset api image: infra/docker-compose.yml pulls ${REGISTRY_URL}/mesta-api:${API_IMAGE_TAG}.
# Build from the repo root:  docker build -t "$REGISTRY_URL/mesta-api:$API_IMAGE_TAG" .
#
# Two stages so the runtime image carries a JRE and the boot jar, not the JDK, Gradle, or sources.
# The Gradle version comes from the wrapper in the repo, not from the base image, so a bump is a
# reviewed change here rather than an image drift.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY modules ./modules
COPY db ./db
COPY ontology ./ontology
# ponytail: one Gradle run per build; split a dependency-only layer if image builds get slow.
RUN ./gradlew --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/api-*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21-jre
# curl: infra/docker-compose.yml's healthcheck calls it inside the container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin mesta
WORKDIR /app
COPY --from=build --chown=mesta:mesta /src/app.jar /app/app.jar
USER mesta
# Heap follows the container limit (docs/reliability.md §4): the default 25% wastes most of the 2 GB
# compose gives the api. Exit on OOM so the orchestrator restarts a broken instance instead of a
# half-alive one serving errors.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
