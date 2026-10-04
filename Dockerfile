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
# Readiness is a bash /dev/tcp read of /actuator/health/readiness
# (infra/docker-compose.yml, deploy/dokploy.compose.yml). The Temurin base ships
# curl, and that binary links libgnutls30t64 and libp11-kit0. apt-cache policy on
# resolute offers only libp11-kit0 0.26.2-2. CVE-2026-13757 is fixed in upstream
# 0.26.3, which resolute has not packaged. The API uses the JRE trust store, so
# curl, GnuTLS, and libp11-kit0 are removed. Alerts #15, #62, #63.
#
# libexpat1 2.7.4-1ubuntu0.2 is the newest resolute build. CVE-2025-66382 has no
# upstream fix (libexpat issue 1076 is still open; Debian sid is still
# vulnerable). fontconfig is the only installed consumer, and this process does
# not render text, so fontconfig and libexpat1 are removed. Alert #14.
#
# No resolute package contains the fix for the two libraries that must stay
# (apt-cache candidate equals the installed version; 26.10 does not either):
#   libc6 2.43-2ubuntu2.4 — CVE-2026-18374, glibc through 2.45. Alert #12.
#   libpcre2-8-0 10.46-1build1 — CVE-2026-86145 and CVE-2026-89161, fixed in
#   PCRE2 10.48. libselinux1 and grep link this library. Alerts #16 and #17.
#
# rust-coreutils, GNU tar, and shadow have no patched Ubuntu 26.04 package.
# GNU coreutils is already installed. tar is essential only because dpkg depends
# on it; the entrypoint is java. CVE-2024-56433 is the default subordinate UID
# range: disable it, create the system user, then remove login.defs and passwd.
# Docker starts the process as that uid and does not invoke login.
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends --allow-remove-essential \
        coreutils-from-gnu \
        coreutils-from-uutils- \
    && apt-get purge -y --allow-remove-essential rust-coreutils \
    && sed -i -E 's/^SUB_UID_COUNT[[:space:]].*/SUB_UID_COUNT\t\t0/' /etc/login.defs \
    && sed -i -E 's/^SUB_GID_COUNT[[:space:]].*/SUB_GID_COUNT\t\t0/' /etc/login.defs \
    && : > /etc/subuid \
    && : > /etc/subgid \
    && useradd --system --uid 10001 --home /app --shell /usr/bin/false octo \
    && apt-get purge -y --auto-remove \
        adduser passwd login login.defs gnupg \
        curl wget \
        p11-kit p11-kit-modules libp11-kit0 libgnutls30t64 \
        fontconfig libfontconfig1 libexpat1 \
    && rm -f /etc/subuid /etc/subgid \
    && dpkg --purge --force-remove-essential --force-depends tar \
    && rm -rf /var/lib/apt/lists/* \
    && id octo >/dev/null \
    && ! dpkg -s libp11-kit0 >/dev/null 2>&1 \
    && ! dpkg -s libexpat1 >/dev/null 2>&1 \
    && ! dpkg -s curl >/dev/null 2>&1
WORKDIR /app
COPY --from=build --chown=octo:octo /src/app.jar /app/app.jar
USER octo
# Heap follows the container limit (docs/reliability.md §4): the default 25% wastes most of the 2 GB
# compose gives the api. Exit on OOM so the orchestrator restarts a broken instance instead of a
# half-alive one serving errors.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
