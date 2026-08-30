# Shared multi-stage Dockerfile for all app modules
# Build with: docker/podman build --build-arg MODULE=api-app -f docker/Dockerfile .

# Stage 1: Build
FROM container-registry.oracle.com/graalvm/jdk:25i3 AS builder
ARG MODULE

WORKDIR /app

# Copy only what Gradle needs first so dependency downloads are cached
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/
COPY modules/ modules/

# Build only the requested module (MODULE, e.g. api-app)
# Gradle caches are persisted across builds via cache mounts
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew ":${MODULE}:bootJar" -x test --no-daemon

# Stage 2: Runtime
FROM container-registry.oracle.com/graalvm/jdk:25i3
ARG MODULE

WORKDIR /app

# Copy the generated JAR from the requested module
COPY --from=builder /app/modules/${MODULE}/build/libs/app.jar ./app.jar
COPY docker/entrypoint.sh .
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
