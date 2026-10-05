<div align="center">
  <h1>GTFSynq</h1>

  <img alt="Quarkus" src="https://img.shields.io/badge/quarkus-%234695EB.svg?style=for-the-badge&logo=quarkus&logoColor=white" />
  <img alt="Java" src="https://img.shields.io/badge/java-%23ED8B00.svg?style=for-the-badge&logo=openjdk&logoColor=white" />
  <img alt="Gradle" src="https://img.shields.io/badge/gradle-02303A.svg?style=for-the-badge&logo=gradle" />
  <img alt="TimescaleDB" src="https://img.shields.io/badge/timescaledb-36764?style=for-the-badge&logo=Timescale&logoColor=black&color=%23E6EE8A" />
  <img alt="Apache Kafka" src="https://img.shields.io/badge/Apache%20Kafka-000?style=for-the-badge&logo=apachekafka" />
  <img alt="Zed" src="https://img.shields.io/badge/zed-084CCF.svg?style=for-the-badge&logo=zedindustries&logoColor=white" />
</div>

<br />

> **🎓 Educational Project**: GTFSynq is an educational project to explore modern Java, Quarkus, time-series databases, and real-time data processing. It's functional, but not all features are implemented yet, and the code may not always follow production-grade patterns or performance optimizations.

GTFSynq is a modern Quarkus transit data platform for ingesting, processing, storing, and analyzing **GTFS-RT (General Transit Feed Specification - Real-Time)** feeds and **GTFS CSV** static feed data

It is built with **Gradle**, runs on **Java 25**, and is designed to work with **Kafka** and **TimescaleDB** for real-time transit data processing and time-series storage

## Features

- **Real-time processing**: Ingest and process GTFS-RT feeds with low latency
- **Static feed ingestion**: Download GTFS static (CSV) archives, store them in S3-compatible object storage, and publish an ingestion event for validation
- **Event-driven handoff**: Kafka carries GTFS-RT payloads and static feed storage references
- **Time-series storage**: Store transit data efficiently in TimescaleDB
- **Kafka integration**: Stream transit payloads through Apache Kafka
- **Protobuf support**: Encode and decode GTFS-RT messages efficiently
- **Observability**: SmallRye Health and Micrometer/Prometheus metrics
- **Docker-based runtime**: Run the full stack with a single Compose command

## Tech Stack

| Component | Technology | Purpose |
|---|---|---|
| Backend | Quarkus 3.40 (LTS) | Application framework |
| Build tool | Gradle | Build, test, and packaging |
| Language | Java 25 | Application language |
| Streaming | Apache Kafka | Event streaming and transport |
| Database | TimescaleDB | Time-series PostgreSQL storage |
| Serialization | Protobuf 4 | GTFS-RT message encoding |
| Monitoring | SmallRye Health + Micrometer/Prometheus | Health and metrics |
| Images | Quarkus container-image Jib | Container image build (no Dockerfile) |

## Prerequisites

To run the project locally, you need:

- **Java 25**
- **Gradle Wrapper**
  The repository includes a Gradle wrapper, so use `./gradlew` for all build and run commands.
- **Docker** and **Docker Compose** if you want to run Kafka and TimescaleDB in containers.

## Quick Start

### 1. Clone the repository

```bash
git clone https://github.com/evogel/GTFSynq.git
cd GTFSynq
```

### 2. Build the application with Gradle

```bash
./gradlew clean build
```

This produces the Quarkus fast-jar layout for each app:

```bash
modules/api-app/build/quarkus-app/quarkus-run.jar
modules/ingest-app/build/quarkus-app/quarkus-run.jar
modules/store-app/build/quarkus-app/quarkus-run.jar
```

### 3. Run the application locally

If Kafka and TimescaleDB are available on your machine, start an app with
Quarkus dev mode (live reload) or from the built artifact:

```bash
./gradlew :api-app:quarkusDev     # dev mode, live reload
java -jar modules/api-app/build/quarkus-app/quarkus-run.jar
```

Each app listens on its own port:

- `api-app`: `http://localhost:8080` (health: `/q/health`, metrics: `/q/metrics`)
- `ingest-app`: `http://localhost:8081`
- `store-app`: `http://localhost:8082`

## Running the Full Stack with Docker

The repository includes a Docker Compose setup that starts:

- `api-app` (port 8080)
- `ingest-app` (port 8081)
- `store-app` (port 8082)
- Kafka
- TimescaleDB
- S3-compatible object storage for GTFS static feeds (port 9000)

App images are built with the Quarkus Jib extension (no Dockerfile, no Docker
daemon required), then Compose starts everything from those prebuilt images.
First create a `.env` with strong secrets (Compose refuses to start without
them):

```bash
cp .env.template .env
# edit .env and set POSTGRES_PASSWORD, S3_SECRET_KEY and GF_SECURITY_ADMIN_PASSWORD
# generate each one with: openssl rand -base64 32
openssl rand -base64 32

./gradlew :api-app:imageBuild :ingest-app:imageBuild :store-app:imageBuild
docker compose up -d
```

All published ports bind to `127.0.0.1` only. The apps are reachable locally on:

```bash
http://localhost:8080 # api-app
http://localhost:8081 # ingest-app
http://localhost:8082 # store-app
```

To expose a service to the internet, put a reverse proxy (Pangolin/Newt, Caddy,
nginx) in front of it or use an SSH tunnel — never republish these ports on
`0.0.0.0`.

### Stopping the stack

```bash
docker compose down
```

### Viewing logs

Container logs are shipped by Vector to VictoriaLogs and are queryable in Grafana
under the **GTFSynq Logs** dashboard and **Drilldown > Logs** (Logs Drilldown),
which is backed by the Loki-compatible `loki-vl-proxy` in front of VictoriaLogs
(`http://localhost:3000`, credentials from `GF_SECURITY_ADMIN_*` in your `.env`). You can also still tail them directly:

```bash
docker compose logs -f api-app
```

## Local Development Flow

A typical local development setup looks like this:

1. Start infrastructure services with Docker Compose.
2. Run an app with `./gradlew :api-app:quarkusDev`.
3. Make changes in `src/main/java` — Quarkus dev mode reloads them on refresh.
4. Re-run `./gradlew test` and `./gradlew build` as needed.

If you want to run only the infrastructure containers and keep the app on your host machine, start Kafka, TimescaleDB and the S3 service separately using the same Compose file (`docker compose -f docker-compose.dev.yaml up -d`), export the `GTFSYNQ_STORAGE_S3_*` values from `.env.template`, then run the app locally with `./gradlew`.

## Project Structure

```text
GTFSynq/
├── build.gradle
├── settings.gradle
├── docker-compose.yaml
├── docker-compose.dev.yaml
├── modules/
│   ├── shared/
│   ├── ingest-app/
│   ├── store-app/
│   └── api-app/
├── monitoring/
└── README.md
```

Each application module keeps its runtime configuration in
`modules/<app>/src/main/resources/application.yaml`, and `store-app` keeps
its Flyway migrations in `modules/store-app/src/main/resources/db/migration`.

## Modules

### `modules/shared`
Contains code reused by multiple apps:

- GTFS domain models and DTOs
- protobuf-generated types
- message envelope encoding/decoding
- hashing utilities
- off-heap state store utilities
- GTFS formatting helpers
- JVM extras metrics binders

### `modules/ingest-app`
Responsible for getting data into Kafka and object storage:

- scheduled GTFS-RT polling on virtual threads
- native GTFS-RT parsing
- Kafka publishing
- scheduled GTFS static download, content-addressed S3 storage and ingestion events
- feed/source configuration

### `modules/store-app`
Responsible for getting data out of Kafka and into PostgreSQL/TimescaleDB:

- Kafka consumer (SmallRye Reactive Messaging)
- deduplication
- batch persistence
- Flyway migrations
- JDBC-based repository writes

### `modules/api-app`
Reserved for the REST API layer:

- Quarkus application
- PostgreSQL-backed read access
- HTTP endpoints and query models

## Runtime Flow

```text
GTFS-RT sources                        GTFS static (CSV) sources
        ↓                                        ↓
 ingest-app  ──────────────────┐          ingest-app
        │                      │                │
        │             gtfs-trip-updates         │  archive
        ↓                      │                ↓
      Kafka  ────────────────► store-app      S3 storage
                               │                │
                               │   gtfs-static-feeds (object location + digest)
                               └────────────────┘
                                ↓
                        PostgreSQL / TimescaleDB
                                ↓
                              api-app
```

## Static Feed Ingestion

Every source that has a `gtfs.sources.<id>.static-config.url` is polled on the static
schedule (`gtfsynq.static-polling.interval`, daily by default), independently of the
GTFS-RT polling cadence:

1. The archive is streamed to a temporary file while its SHA-256 digest is computed.
2. The archive is uploaded to S3-compatible object storage under a content-addressed key,
   `static/<feed-id>/<sha256>.zip`, so a revision is stored exactly once and every revision
   is kept.
3. A `StaticFeedIngested` protobuf event (feed id, bucket, key, source URL, size, digest,
   ingestion time, ETag) is published to the `gtfs-static-feeds` topic, keyed by feed id.

When the digest of a polled archive already exists in the bucket, the upload and the event
are both skipped: downstream consumers are only woken up by an actual revision change.
The archive itself never travels over Kafka - consumers fetch and validate the referenced
object themselves.

Storage is configured under `gtfsynq.storage.s3`:

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch for static ingestion; when false no archives are polled or uploaded |
| `endpoint` | `http://localhost:9000` | Endpoint of the S3-compatible service; leave empty for AWS S3 |
| `region` | `us-east-1` | Region requests are signed for |
| `bucket` | `gtfsynq-static` | Bucket holding the archives; created on first upload when missing |
| `access-key` / `secret-key` | empty | Static credentials; when empty the AWS default credentials chain is used |
| `path-style-access` | `true` | Address buckets as `endpoint/bucket`, as local S3 services require |

## Configuration

Runtime configuration lives in each app's
`modules/<app>/src/main/resources/application.yaml` and is overridden by
environment variables (Quarkus maps `gtfsynq.storage.s3.bucket` to
`GTFSYNQ_STORAGE_S3_BUCKET`, `kafka.bootstrap.servers` to
`KAFKA_BOOTSTRAP_SERVERS`, and so on).

Important defaults:

- Kafka bootstrap server: `localhost:9092`
- PostgreSQL / TimescaleDB: `localhost:5432`, database `gtfsynq`
- S3-compatible object storage: `http://localhost:9000`, bucket `gtfsynq-static`
- Realtime polling interval: `60s`; static polling interval: `24h`
- Buffered sink flush interval: `10s`, hot data retention `1h`
- Management endpoints: `/q/health`, `/q/metrics` (the apps never start Dev
  Services containers; external infrastructure comes from Compose)

When running through Docker Compose, these values are overridden with container hostnames.

## Database Migrations

Database schema migrations are managed with Flyway and are located in:

```text
modules/store-app/src/main/resources/db/migration
```

Migrations are applied automatically on startup
(`quarkus.flyway.migrate-at-start=true`).

## Build and Test Commands

### Run tests

```bash
./gradlew test
```

### Build the application artifacts

```bash
./gradlew build
```

### Clean and build from scratch

```bash
./gradlew clean build
```

### Run an app from Gradle (dev mode, live reload)

```bash
./gradlew :api-app:quarkusDev
```

## Container Image Build

Container images are built with the Quarkus Jib extension — no Dockerfile and no
Docker daemon needed:

```bash
# All three apps (JVM)
./gradlew :api-app:imageBuild :ingest-app:imageBuild :store-app:imageBuild

# Or a single app
./gradlew :api-app:imageBuild
```

This produces local images named `ghcr.io/howruck/gtfsynq-<app>:0.0.1-SNAPSHOT`
(e.g. `ghcr.io/howruck/gtfsynq-api-app:0.0.1-SNAPSHOT`), which `docker-compose.yaml`
references directly. Registry, group, name and tag are configured per app in
`application.yaml` and can be overridden on the command line, e.g.:

```bash
./gradlew :api-app:imageBuild -Dquarkus.container-image.push=true
```

### GraalVM native images

Quarkus builds native executables out of the box. For a local (non-container)
binary you need GraalVM (or Mandrel) 25 on `PATH`:

```bash
./gradlew :api-app:build -Dnative
```

Alternatively let Quarkus run the native build in a container (Docker/Podman
required, ~8 GB RAM recommended, first build takes 5-15 min per service):

```bash
./gradlew :api-app:build -Dquarkus.native.enabled=true \
  -Dquarkus.native.container-build=true -Dquarkus.package.jar.enabled=false
```

`quarkus.package.jar.enabled=false` is required: the Quarkus Gradle plugin cannot
emit a JAR and a native binary in the same build.

`ingest-app` and `store-app` pass the shared-arena flag their off-heap state store
requires via `quarkus.native.additional-build-args` in `application.yaml`:
`-H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport`
(`OffHeapLongTable` uses `Arena.ofShared()`, which GraalVM disables by default).

To run native images, point Compose at the `-native` tags. Note the native run
image has no shell, so any `wget`/`curl`-based Compose healthcheck must be
replaced (e.g. a Docker `httpGet`-style check).

## Monitoring

The `docker-compose.monitoring.yaml` stack provides metrics and logs:

| Component | URL | Purpose |
|---|---|---|
| Grafana | http://localhost:3000 | Dashboards for metrics and logs (credentials from `GF_SECURITY_ADMIN_*` in `.env`) |
| VictoriaMetrics | http://localhost:8428 | Metrics storage (Prometheus-compatible) |
| VictoriaLogs | http://localhost:9428 | Log storage (LogsQL) |
| Loki-VL-proxy | http://localhost:3100 | Loki-compatible read API for Explore/Logs Drilldown |
| Vector | http://localhost:8686 | Collects container logs into VictoriaLogs |

Vector reads container logs through the engine's Docker-compatible API. The
socket defaults to the rootless Podman socket; override `PODMAN_SOCKET` in `.env`
for other setups:

```bash
PODMAN_SOCKET=/var/run/docker.sock docker compose up -d          # Docker
PODMAN_SOCKET=/run/podman/podman.sock docker compose up -d       # rootful Podman
```

The applications expose management endpoints on their own port:

- `GET /q/health`
- `GET /q/health/live`
- `GET /q/health/ready`
- `GET /q/metrics`

VictoriaMetrics scrapes `/q/metrics` on all three apps.

## Educational Project

GTFSynq is primarily intended for educational and exploratory use. It is a good place to experiment with:

- modern Quarkus development
- real-time transit feed ingestion
- Kafka streaming
- Flyway database migrations
- TimescaleDB time-series modeling
- Protobuf-based transport formats
- off-heap state with the Java foreign memory API

## License

This project is licensed under the terms of the repository's `LICENSE` file.
