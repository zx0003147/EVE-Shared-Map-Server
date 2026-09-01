FROM eclipse-temurin:25.0.4_7-jdk-alpine-3.24 AS builder

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod 0755 gradlew
COPY src ./src
RUN ./gradlew --no-daemon --console=plain clean check installDist

FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24 AS runtime

RUN addgroup -g 10001 appgroup \
    && adduser -D -H -u 10001 -G appgroup appuser \
    && mkdir -p /app/notices /tmp/eve-shared-map \
    && chown -R 10001:10001 /app /tmp/eve-shared-map

WORKDIR /app
COPY --from=builder --chown=10001:10001 /workspace/build/install/eve-shared-map-server/ /app/
COPY --chown=10001:10001 README.md /app/notices/README.md
COPY --chown=10001:10001 docs/DEPENDENCY-LICENSES.md /app/notices/DEPENDENCY-LICENSES.md

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:-HeapDumpOnOutOfMemoryError -Djava.io.tmpdir=/tmp/eve-shared-map"

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/eve-shared-map-server"]
