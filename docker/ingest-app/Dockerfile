# Stage 1: Build
FROM container-registry.oracle.com/graalvm/jdk:25i3 AS builder

WORKDIR /app

# Copy the full multi-module repository
COPY . .

# Build only the ingest-app module
RUN ./gradlew :ingest-app:bootJar -x test --no-daemon --no-build-cache --no-configuration-cache

# Stage 2: Runtime
FROM container-registry.oracle.com/graalvm/jdk:25i3

WORKDIR /app

# Copy the generated JAR from the ingest-app module
COPY --from=builder /app/modules/ingest-app/build/libs/app.jar ./app.jar
COPY docker/ingest-app/entrypoint.sh .
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
