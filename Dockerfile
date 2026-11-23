# PCAP Player: one image with the UI and the server.
#
#   docker build -t pcap-player .
#   docker run -p 8080:8080 -e STORAGE_TYPE=filesystem pcap-player
#
# Stages: 1) build the React UI, 2) build the Java fat jar with the UI inside, 3) a small JRE runtime.
#
# Build arguments (all optional), for isolated networks and CI:
#   REGISTRY          base-image registry prefix, e.g. nexus.local/   (default: Docker Hub)
#   NPM_REGISTRY      npm mirror URL
#   MAVEN_MIRROR_URL  Maven mirror URL
#   RUN_TESTS=true    run the backend unit tests during the build
#
# Base images are pinned to exact versions so builds are reproducible.

ARG REGISTRY=docker.io/library/
ARG NODE_IMAGE=node:20.18.1-alpine3.20
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG JRE_IMAGE=eclipse-temurin:17.0.13_11-jre-jammy


# ============================================================ 1. frontend
FROM ${REGISTRY}${NODE_IMAGE} AS frontend
ARG NPM_REGISTRY=https://registry.npmjs.org/
WORKDIR /build

# Dependencies first, so this layer is reused until package*.json changes.
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm config set registry "$NPM_REGISTRY" \
 && npm ci --no-audit --no-fund

COPY frontend/ ./
# Type-checks, then bundles into /build/dist.
RUN npm run build


# ============================================================ 2. backend
FROM ${REGISTRY}${MAVEN_IMAGE} AS backend
ARG MAVEN_MIRROR_URL=
ARG RUN_TESTS=false
WORKDIR /build

RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 \
      && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
           "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
# The capture-reading tests need the native libpcap; install it only when tests run.
RUN if [ "$RUN_TESTS" = "true" ]; then \
      apt-get update && apt-get install -y --no-install-recommends libpcap0.8 && rm -rf /var/lib/apt/lists/*; \
    fi

# Dependencies first, so this layer is reused until pom.xml changes.
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src
COPY --from=frontend /build/dist ./src/main/resources/webroot
RUN if [ "$RUN_TESTS" = "true" ]; then mvn -B -q package; else mvn -B -q package -DskipTests; fi


# ============================================================ 3. runtime
FROM ${REGISTRY}${JRE_IMAGE} AS runtime

LABEL org.opencontainers.image.title="pcap-player" \
      org.opencontainers.image.description="Replays recorded pcap traffic to a service under test, with a web UI" \
      org.opencontainers.image.licenses="Proprietary"

# Captures are read by the native libpcap (through pcap4j/JNA). Only offline reading is used, so the
# container needs no capture capabilities (no CAP_NET_RAW). The unversioned symlink lets JNA find it.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libpcap0.8 \
 && ln -sf "$(find /usr/lib /lib -name 'libpcap.so.0.8' | head -n 1)" /usr/lib/libpcap.so \
 && rm -rf /var/lib/apt/lists/*

# OpenShift runs containers as an arbitrary UID in group 0, so everything written is group-0 writable.
# The store folder exists in the image so a volume mounted there inherits these permissions.
RUN mkdir -p /app /tmp/pcap-player/store \
 && chgrp -R 0 /app /tmp/pcap-player \
 && chmod -R g=u /app /tmp/pcap-player

WORKDIR /app
COPY --from=backend --chown=1001:0 /build/target/pcap-player.jar /app/pcap-player.jar

# Your configuration (S3 address and secrets) is read from /app/config/application.yml: mount it
# there (a Kubernetes/OpenShift Secret works); it is never copied into the image.
# WORK_DIR: uploads in progress and the local pcap cache. STORAGE_FILESYSTEM_ROOT is only used with
# STORAGE_TYPE=filesystem (mount a volume there to keep data); the default storage is S3.
ENV WORK_DIR=/tmp/pcap-player \
    STORAGE_FILESYSTEM_ROOT=/tmp/pcap-player/store \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Dvertx.cacheDirBase=/tmp/vertx-cache"

USER 1001
# HTTP, API and WebSocket share one port. TCP-client-mode replays also listen on the ports users choose.
EXPOSE 8080

# Docker only (Kubernetes/OpenShift use their own probes: /health/live and /health/ready).
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${SERVER_PORT:-8080}/health/live > /dev/null || exit 1

# exec: Java becomes PID 1 and receives SIGTERM, so running replays stop cleanly and write history.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pcap-player.jar"]
