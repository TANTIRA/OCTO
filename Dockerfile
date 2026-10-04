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

FROM eclipse-temurin:21.0.12.1_1-jdk@sha256:3e3c176ffed168beb42c607be9bc1639b466cf00261a0fb04425562c9d0c5c2b AS build
WORKDIR /src
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY modules ./modules
COPY db ./db
COPY ontology ./ontology
# ponytail: one Gradle run per build; split a dependency-only layer if image builds get slow.
RUN ./gradlew --no-daemon :modules:api:bootJar -x test \
    && cp modules/api/build/libs/api-*-SNAPSHOT.jar /src/app.jar

FROM eclipse-temurin:21.0.12.1_1-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5
# curl: infra/docker-compose.yml's healthcheck calls it inside the container.
# Ubuntu 26.04 has no patched package for the rust-coreutils, GNU tar, or shadow
# alerts. GNU coreutils is already installed; switch the provider and delete the
# rust package. tar is essential only because dpkg depends on it, and this image
# never extracts archives after the build. CVE-2024-56433 is the default
# subordinate UID range: disable it, create the system user, then remove the
# shadow packages. Docker starts the process as that uid and does not invoke login.
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends --allow-remove-essential \
        curl \
        coreutils-from-gnu \
        coreutils-from-uutils- \
    && apt-get purge -y --allow-remove-essential rust-coreutils wget \
    && sed -i -E 's/^SUB_UID_COUNT[[:space:]].*/SUB_UID_COUNT\t\t0/' /etc/login.defs \
    && sed -i -E 's/^SUB_GID_COUNT[[:space:]].*/SUB_GID_COUNT\t\t0/' /etc/login.defs \
    && : > /etc/subuid \
    && : > /etc/subgid \
    && useradd --system --uid 10001 --home /app --shell /usr/bin/false octo \
    && apt-get purge -y adduser passwd login login.defs gnupg \
    && dpkg --purge --force-remove-essential --force-depends tar \
# Ubuntu 26.04 has no newer package for several Trivy findings, so the runtime
# layer drops binaries this process never uses:
#   p11-kit and p11-kit-modules (CVE-2026-13757). libp11-kit0 stays; gnutls links it.
#   passwd and login.defs (CVE-2024-56433), after useradd. /etc/subuid is removed
#   so the image does not keep the default subordinate-uid range. adduser and gnupg
#   come out with them; the API process does not call either.
#   tar (CVE-2026-18477, CVE-2026-18508). It is Essential and dpkg depends on it;
#   the entrypoint is java, so it is removed only after apt has finished.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
    && DEBIAN_FRONTEND=noninteractive apt-get purge -y wget p11-kit p11-kit-modules \
    && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin octo \
    && DEBIAN_FRONTEND=noninteractive apt-get purge -y --auto-remove passwd login.defs \
    && rm -f /etc/subuid /etc/subgid \
    && dpkg --remove --force-remove-essential --force-depends tar \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build --chown=octo:octo /src/app.jar /app/app.jar
USER octo
# Heap follows the container limit (docs/reliability.md §4): the default 25% wastes most of the 2 GB
# compose gives the api. Exit on OOM so the orchestrator restarts a broken instance instead of a
# half-alive one serving errors.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
