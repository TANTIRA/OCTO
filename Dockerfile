# OCTO api image: infra/docker-compose.yml pulls ${REGISTRY_URL}/octo-api:${API_IMAGE_TAG}.
# Build from the repo root:  docker build -t "$REGISTRY_URL/octo-api:$API_IMAGE_TAG" .
#
# Two stages so the runtime image carries a JRE and the boot jar, not the JDK, Gradle, or sources.
# The Gradle version comes from the wrapper in the repo, not from the base image, so a bump is a
# reviewed change here rather than an image drift.
#
# Base images are pinned by version + digest (infra/README.md guardrails). Bump both together:
#   docker buildx imagetools inspect eclipse-temurin:21.0.12.1_1-jdk --format '{{.Manifest.Digest}}'
# A digest without its matching tag is unreviewable, and a tag without its digest still floats.

FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:4d06038800655fe1211760cd561de70ef2ed7a47f5d69255e9834414602b7026 AS build
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY modules ./modules
COPY db ./db
COPY ontology ./ontology
# ponytail: one Gradle run per build; split a dependency-only layer if image builds get slow.
RUN ./gradlew --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/api-*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21.0.12.1_1-jre@sha256:d7051a45dd955e4d5d1db4d3f4269fe13d1c6dff8cc6b7ef89fc8577b96c1982
# curl: infra/docker-compose.yml's healthcheck calls it inside the container.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin octo
WORKDIR /app
COPY --from=build --chown=octo:octo /src/app.jar /app/app.jar
USER octo
# Heap follows the container limit (docs/reliability.md §4): the default 25% wastes most of the 2 GB
# compose gives the api. Exit on OOM so the orchestrator restarts a broken instance instead of a
# half-alive one serving errors.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
