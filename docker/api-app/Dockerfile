# Stage 1: Build
FROM container-registry.oracle.com/graalvm/jdk:25i3 AS builder

WORKDIR /app

# Copy the full multi-module repository
COPY . .

# Build only the api-app module
RUN ./gradlew :api-app:bootJar -x test --no-daemon --no-build-cache --no-configuration-cache

# Stage 2: Runtime
FROM container-registry.oracle.com/graalvm/jdk:25i3

WORKDIR /app

# Copy the generated JAR from the api-app module
COPY --from=builder /app/modules/api-app/build/libs/app.jar ./app.jar
COPY docker/api-app/entrypoint.sh .
RUN chmod +x entrypoint.sh

ENTRYPOINT ["./entrypoint.sh"]
