# Stage 1: Build
FROM docker.io/eclipse-temurin:26-jdk-alpine AS builder
RUN apk add --no-cache libc6-compat libstdc++

WORKDIR /app

# Copy the full multi-module repository
COPY . .

# Build only the ingest-app module
RUN ./gradlew :ingest-app:bootJar -x test --no-daemon --no-build-cache --no-configuration-cache

# Stage 2: Runtime
FROM docker.io/eclipse-temurin:26-jre-alpine

WORKDIR /app

# Copy the generated JAR from the ingest-app module
COPY --from=builder /app/modules/ingest-app/build/libs/app.jar ./app.jar
COPY docker/ingest-app/entrypoint.sh .
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
