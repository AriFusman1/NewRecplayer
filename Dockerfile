# PCAP Player — one image: build the frontend, build the backend, run the fat jar.
# Base image tags are pinned; override the registry with --build-arg REGISTRY=my-mirror/ if needed.
ARG REGISTRY=docker.io/library/

# ---------- 1. frontend ----------
FROM ${REGISTRY}node:20.18.1-alpine3.20 AS frontend
# Point at an internal npm mirror with --build-arg NPM_REGISTRY=https://nexus.example/repository/npm/
ARG NPM_REGISTRY=https://registry.npmjs.org/
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm config set registry "$NPM_REGISTRY" && npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 2. backend ----------
FROM ${REGISTRY}maven:3.9.9-eclipse-temurin-17 AS backend
# Optional Maven mirror: --build-arg MAVEN_MIRROR_URL=https://nexus.example/repository/maven-public/
ARG MAVEN_MIRROR_URL=
WORKDIR /build
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>' "$MAVEN_MIRROR_URL" > /root/.m2/settings.xml; \
    fi
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /build/dist ./src/main/resources/webroot
RUN mvn -B -q package -DskipTests

# ---------- 3. runtime ----------
FROM ${REGISTRY}eclipse-temurin:17.0.13_11-jre-jammy
# OpenShift runs the container with an arbitrary UID in group 0: everything it writes is group-0 writable.
RUN mkdir -p /app /tmp/pcap-player \
 && chgrp -R 0 /app /tmp/pcap-player \
 && chmod -R g=u /app /tmp/pcap-player
WORKDIR /app
COPY --from=backend --chown=1001:0 /build/target/pcap-player.jar /app/pcap-player.jar
ENV WORK_DIR=/tmp/pcap-player \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Dvertx.cacheDirBase=/tmp/vertx-cache"
USER 1001
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/pcap-player.jar"]
