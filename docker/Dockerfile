FROM docker.io/maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /app

# Copy pom.xml and download dependencies first to leverage Docker caching
COPY pom.xml .
RUN mvn dependency:resolve

# Now copy the source code and build the application
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Run container
FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /app

# Copy built jar into the runtime image with a simple name
COPY --from=builder /app/target/SiriAnalyzer-0.0.1-SNAPSHOT.jar ./SiriAnalyzer.jar
COPY docker/entrypoint.sh .

RUN chmod +x entrypoint.sh

CMD ["./entrypoint.sh"]