FROM eclipse-temurin:25.0.4_7-jdk-alpine-3.24 AS builder

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod 0755 gradlew
COPY src ./src
# The release pipeline runs clean check (including host Docker/Testcontainers) before this image build.
# Docker build has no daemon socket, so the image stage only creates the already-verified distribution.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain clean installDist

FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24 AS runtime

ARG APP_VERSION=0.1.0
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="EVE Shared Map Server" \
      org.opencontainers.image.version="$APP_VERSION" \
      org.opencontainers.image.revision="$VCS_REF"

RUN addgroup -g 10001 appgroup \
    && adduser -D -H -u 10001 -G appgroup appuser \
    && mkdir -p /app/notices /tmp/eve-shared-map \
    && chown -R 10001:10001 /app /tmp/eve-shared-map

WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/build/install/eve-shared-map-server/ /app/
COPY --chown=10001:10001 README.md /app/notices/README.md
COPY --chown=10001:10001 docs/DEPENDENCY-LICENSES.md /app/notices/DEPENDENCY-LICENSES.md
COPY --chown=10001:10001 docs/PRODUCTION-DEPLOYMENT.md /app/notices/PRODUCTION-DEPLOYMENT.md

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -Djava.io.tmpdir=/tmp/eve-shared-map"

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/eve-shared-map-server"]
