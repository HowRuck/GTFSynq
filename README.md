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
- **Web console**: A server-side Vaadin Flow console with an overview, lazy-loaded static feed / route / stop / trip tables, and a live trip-update table with a per-trip map and stop timeline that refresh every minute
- **Kafka integration**: Stream transit payloads through Apache Kafka
- **Protobuf support**: Encode and decode GTFS-RT messages efficiently
- **GraalVM native images**: Ahead-of-time compile any app into a standalone native executable for fast startup and low memory
- **Observability**: OpenTelemetry metrics, logs and traces (Micrometer metrics and OTLP export) stored in the VictoriaMetrics stack
- **Docker-based runtime**: Run the full stack with a single Compose command

## Tech Stack

| Component | Technology | Purpose |
|---|---|---|
| Backend | Quarkus 4.0 (beta) | Application framework for the backend apps |
| Console | Vaadin Flow 25.4 + Quarkus 4.0 (beta) | Server-side web console (shares the Quarkus platform and BOM with the other apps) |
| Build tool | Gradle | Build, test, and packaging |
| Language | Java 25 | Application language |
| Streaming | Apache Kafka | Event streaming and transport |
| Database | TimescaleDB | Time-series PostgreSQL storage |
| Serialization | Protobuf 4 | GTFS-RT message encoding |
| Monitoring | SmallRye Health + OpenTelemetry (OTLP) | Health, metrics, logs and traces |
| Images | Quarkus container-image Jib | Container image build (no Dockerfile) |
| Native image | GraalVM / Mandrel 25 | Ahead-of-time compiled native executables |

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
modules/ui-app/build/quarkus-app/quarkus-run.jar
```

`ui-app` also builds its production frontend bundle. Vaadin's extension downloads
the Node/pnpm toolchain for that on the first build, so the first `ui-app` build
takes longer than the backend modules.

### 3. Run the application locally

Quarkus dev mode (live reload) starts the infrastructure containers by itself,
so this is the only command needed:

```bash
./gradlew :api-app:quarkusDev     # dev mode, live reload
```

The built artifact still runs against whatever Kafka and TimescaleDB the
environment points it at:

```bash
java -jar modules/api-app/build/quarkus-app/quarkus-run.jar
```

Each app listens on its own port:

- `api-app`: `http://localhost:8080` (health: `/q/health`; gRPC shares this port)
- `ingest-app`: `http://localhost:8081`
- `store-app`: `http://localhost:8082`
- `ui-app`: `http://localhost:8083` (health: `/q/health`)

The Vaadin console lives in `modules/ui-app` (see [Web Console](#web-console)).
It is a pure gRPC client: it reads everything from `api-app` on port `8080`
(Quarkus 4 serves gRPC from the HTTP server) and never calls the REST API. Run
it in dev mode next to the API:

```bash
./gradlew :api-app:quarkusDev      # terminal 1
./gradlew :ui-app:quarkusDev       # terminal 2 → http://localhost:8083
```

Point it at a different backend with `GTFS_API_GRPC_HOST` and
`GTFS_API_GRPC_PORT`.

## Running the Full Stack with Docker

The repository includes a Docker Compose setup that starts:

- `api-app` (port 8080, REST and gRPC)
- `ui-app` (port 8083) — Vaadin console; reaches `api-app` over gRPC
- `ingest-app` (port 8081)
- `store-app` (port 8082)
- Kafka
- TimescaleDB
- S3-compatible object storage for GTFS static feeds (port 9000)

All images are built with the Quarkus Jib extension (no Dockerfile, no Docker
daemon required). Compose starts everything from the resulting images. First
create a `.env` with strong secrets (Compose refuses to start without them):

```bash
cp .env.template .env
# edit .env and set POSTGRES_PASSWORD, S3_SECRET_KEY and GF_SECURITY_ADMIN_PASSWORD
# generate each one with: openssl rand -base64 32
openssl rand -base64 32

./gradlew :api-app:imageBuild :ingest-app:imageBuild :store-app:imageBuild :ui-app:imageBuild
docker compose up -d
```

All published ports bind to `127.0.0.1` only. The apps are reachable locally on:

```bash
http://localhost:8083 # Vaadin console
http://localhost:8080 # api-app (REST + health)
http://localhost:8081 # ingest-app
http://localhost:8082 # store-app
```

To expose a service to the internet, put a reverse proxy (Pangolin/Newt, Caddy,
nginx) in front of it or use an SSH tunnel — never republish these ports on
`0.0.0.0`. Point that proxy at the console on port 8083; the console keeps all
data access server-side over gRPC, so the browser only talks to `ui-app`.

### Stopping the stack

```bash
docker compose down
```

### Viewing logs

Application logs are exported over OTLP to VictoriaLogs and are queryable in
Grafana under the **GTFSynq Logs** dashboard and **Explore** (`http://localhost:3000`,
credentials from `GF_SECURITY_ADMIN_*` in your `.env`). Each log line also carries
its `traceId`/`spanId`, so it can be correlated with a trace in VictoriaTraces. You
can also still tail them directly:

```bash
docker compose logs -f api-app
```

## Local Development Flow

Dev mode starts the infrastructure itself. Quarkus [Compose Dev
Services](https://quarkus.io/guides/compose-dev-services/) brings up Kafka,
TimescaleDB and the S3 gateway from `docker-compose.dev.yaml`, waits for their
health checks, and copies their credentials into the app (the `config_map`
labels in that file map the container environment onto `quarkus.datasource.*`
and `gtfsynq.storage.s3.*`), so nothing has to be exported.

1. Run an app with `./gradlew :api-app:quarkusDev`.
2. Make changes in `src/main/java` — Quarkus dev mode reloads them on refresh.
3. Re-run `./gradlew test` and `./gradlew build` as needed.

The apps share one Compose project (`gtfsynq`), so a second app started in dev
mode reuses the running stack rather than starting another copy on the same host
ports. The stack is started once per build, before any dev-mode app (the root
`devInfra` task); the apps themselves only discover it. It is left running when
dev mode stops, so stop it explicitly when you are done:

```bash
docker compose -f docker-compose.dev.yaml down
```

Starting every app from one build also works with `./gradlew quarkusDev`. Add
`-Ddebug=false` to such a run: Quarkus dev mode opens a JDWP debug port per app,
and several apps starting at once would race for port 5005. Alternatively start
each app in its own terminal.

Dev mode needs a working Docker or Podman with Compose V2 (`docker compose` or
`podman compose`). Starting the infrastructure yourself with the same file still
works; dev mode then discovers the running containers instead of starting them.

## Web Console

The console in `modules/ui-app` is a server-side Vaadin Flow application. It is a
pure gRPC client of `api-app`: the browser only talks to `ui-app` on port `8083`,
while every query is a unary `GtfsQueryService` call made on the server. It opens
no datasource and never calls the REST API.

| Page | Path | Contents |
|---|---|---|
| Overview | `/` | Row counts for static feeds, routes, stops, trips and live updates, plus the most recent static feed revisions |
| Static Feeds | `/feeds` | One current revision per feed: agency, digest, size, object key, source, ingestion and load times |
| Routes | `/routes` | Static routes with a feed filter and search |
| Stops | `/stops` | Stops and stations with coordinates, location type and parent station |
| Trips | `/trips` | Trips with their route, service and direction |
| Live Updates | `/live` | Current trip updates with the agency of each trip's route; select a trip to open its stop timeline and route map beside the table, plus a map of every live trip |

Behaviour shared by the tables:

- Rows load lazily as you scroll; the backend returns one page at a time, so the
  console never holds a whole table in memory.
- Tables fill the remaining viewport height and scroll internally, keeping the
  header and filters in place.
- Sortable columns sort server-side; columns are sized to their content and share
  any remaining width.
- Routes, stops and trips offer a feed selector and a search box (three or more
  characters, matching the API).
- The live view polls every minute through server push, keeps the open trip detail
  pane in sync across refreshes, and has a manual refresh button. A vertical split
  between the map and the table can be dragged to resize them. The overview map
  plots one marker per live trip at the most advanced stop the trip has actually
  reached (a stop whose predicted time is not in the future), independently of the
  table's filters and paging: it loads its own unpaged `ListTripMarkers` result
  (cached for ten seconds so open sessions share one query), clusters markers
  client-side and falls back to raster tiles when the vector basemap is
  unreachable. Marker snapshots travel as compact
  `[lat, lon, label, detail, delay, reachedMinutesAgo]` tuples and are only pushed
  when they change; co-located trips are spread a few metres so a cluster's number
  matches the markers that become visible when zooming in. The popup also says how
  long ago the trip reached that stop. Trip, route and stop labels prefer their
  static names and fall back to ids when no matching static name is available; long
  labels are shortened to 32 characters with the full value in a tooltip. Each trip
  row also shows the agency of its route and its current delay, taken from the last
  stop that reports one.
- Selecting a trip opens a full-height detail pane to the right of the map and
  table. A draggable vertical splitter separates the overview and the detail pane,
  like the horizontal one between the map and the table, and remembers the width the
  user dragged it to. The pane has a small map at the top with the trip's path through its
  stops and a pin on the current stop, followed by a scrollable timeline of every
  stop. Reached stops are green, the current stop carries a pin and a badge, upcoming
  stops are muted, skipped stops are marked, and schedule-filled stops are labelled
  as such. Stop times and delays come from the merged static/live stop list, loaded
  off the UI thread so the session lock is never held while the backend answers.
  A centred progress indicator fills the pane until the stop list arrives, keeping
  the selected trip's header visible instead of showing an empty sidebar.
- gRPC failures appear as notifications instead of breaking the view. The API
  host and port come from `GTFS_API_GRPC_HOST` / `GTFS_API_GRPC_PORT`.
- All text goes through Vaadin's I18N provider
  (`src/main/resources/vaadin-i18n/translations.properties`); English, German and
  French are bundled, the initial language follows the browser and a navbar
  switcher overrides it for the session. The look comes from the Aura theme
  configured in `META-INF/resources/styles.css` (Inter font, orange accent, warm
  light/dark backgrounds).

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
│   ├── api-app/
│   └── ui-app/
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
- protobuf-generated types, including the `GtfsQueryService` gRPC contract
  (`src/main/proto/gtfs-query.proto`) shared by `api-app` and `ui-app`
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
- GTFS static feed validation (fetch from object storage, verify the digest, stream-validate)
- GTFS static feed persistence (stream the validated archive into the `static_*` tables)
- deduplication
- batch persistence
- Flyway migrations
- JDBC-based repository writes

### `modules/api-app`
The read API used by the console and by external clients:

- Quarkus application
- PostgreSQL-backed read access
- REST resources and query models on port `8080`
- `GtfsQueryService` gRPC service (served from the HTTP port), the console's only data source

### `modules/ui-app`
The Vaadin Flow web console (see [Web Console](#web-console)):

- server-side views for `Overview`, `Static Feeds`, `Routes`, `Stops`, `Trips` and
  `Live Updates`, with top navigation and localized page titles
- lazy-loaded grids backed by paged unary `GtfsQueryService` calls, with
  server-side sorting, content-sized columns that fill the grid, feed filters and
  search
- a flat live trip table whose rows open a resizable detail pane, split off from
  the overview, with a stop timeline, a per-trip path map and the trip's current
  position, refreshed every minute through server push
- a live trip overview map that loads every positioned trip independently of the
  table's paging, so the overview stays complete while the table stays lazy
- English, German and French translations via Vaadin I18N, Aura theme with the
  Inter font and an orange accent palette
- no database access and no REST calls — it is a pure gRPC client of `api-app`

## Runtime Flow

```mermaid
flowchart TB
  subgraph L1["Inputs"]
    RTS["GTFS-RT"]
    STS["GTFS static"]
  end

  subgraph L2["2 · Ingestion"]
    ING["ingest-app"]
    STORE["store-app"]
  end

  subgraph L3["3 · Messaging and storage"]
    KAFKA[["Kafka"]]
    S3[("S3")]
    DB[("TimescaleDB")]
    OBS["OTLP"]
  end

  subgraph L4["4 · Read and presentation"]
    API["api-app"]
    UI["ui-app"]
  end

  subgraph L5["5 · Outputs"]
    EXT["REST clients"]
    BR["Browser"]
  end

  RTS --> ING
  STS --> ING
  ING --> KAFKA
  ING --> S3
  KAFKA --> STORE
  S3 --> STORE
  STORE --> DB
  DB --> API
  API -->|"gRPC"| UI
  API -->|"REST"| EXT
  UI --> BR
  L2 -.-> OBS
  L4 -.-> OBS
```

Arrows follow the data, not the request: `api-app` → `ui-app` and `api-app` →
REST clients mean the API answers them. Dashed arrows are telemetry exports.

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

## Static Feed Validation

`store-app` consumes `gtfs-static-feeds` and validates each revision before anything is
persisted, so rows only reach the database once they are known good:

1. The archive is streamed out of object storage to a temporary file while its SHA-256 digest
   is computed.
2. The digest is compared against the one recorded at ingestion. A mismatch means the object is
   not the revision the event describes, so the archive is rejected unvalidated.
3. The archive is validated by the `shared` streaming validator (`GtfsStaticArchiveValidator`),
   which walks each entry once, checks row-level rules with YAVI, and answers referential
   questions - duplicate primary keys, `trips.route_id`, `stop_times.trip_id`/`stop_id`, and
   per-trip `stop_sequence` ordering - from hashed id indexes.
4. The outcome is reported as metrics and logs; an invalid feed is not handed on for
   persistence, so the last good revision stays in place.

The validator reads the archive exactly once per entry and holds only hashed ids, so peak
memory does not grow with archive size. Two guards bound it further: violation details are
capped per file, and both the per-trip sequence tracking and the id indexes stop growing at a
configured limit - when that limit is hit the affected files are reported as *not fully
checked* rather than validated against partial state.

Every outcome is acknowledged. Validation is idempotent and repeatable, so retrying would
re-fetch the same archive without changing the answer; failures surface through the
`gtfs.static.validation.*` meters (`feeds{result=...}`, `violations{file=...}`, `duration`,
`rows`) instead of a retry loop.

## Static Feed Persistence

A revision that passed validation is written to the `static_*` tables. Validation gates it, so
the feed in the database is always a revision that was checked end to end:

1. `StaticFeedRepository` compares the digest the event carries against the one recorded in
   `static_feeds`. An event for the revision already loaded opens a writer that discards the
   rows it is handed, so a redelivery after a restart costs a fetch and a validation, not a
   rewrite.
2. Any other revision clears that feed's rows from every `static_*` table and streams the
   archive back in through `GtfsStaticArchiveReader`, the same entry order the validator used,
   into `StaticFeedWriter`.
3. The `static_feeds` row naming the loaded revision is written last, so the revision marker and
   the rows it describes are committed together.

The replace and the insert share one transaction on a single pooled connection, so a feed is
either fully at the new revision or fully at the old one - a failure halfway through a
multi-million row load rolls back and the previous revision stays queryable. Loads are
serialised per feed with a transaction-scoped advisory lock, so two events for one feed cannot
interleave even when consumed concurrently, while loads of different feeds never block each
other.

Rows stream entry by entry in batches (`gtfsynq.static.persistence.batch-size`, 5000 by
default) from the reader into JDBC batches, so peak memory depends on the batch size rather than
on archive size. `gtfsynq.static.persistence.enabled=false` stops after validation and leaves
the stored revision untouched.

A GTFS time may run past midnight (`25:10:00`), which a `TIME` column cannot hold, so
`static_stop_times` stores the time of day beside the day overflow - the same split the realtime
tables use for a trip descriptor's start time. Cells are typed at that boundary: the row shapes
the validator checked are all strings, and the writer converts them once, per row, on the way in.
A cell that will not convert means the validator and the reader disagree about the same bytes;
the load fails loudly rather than writing a placeholder.

The persistence stage reports through `gtfs.static.persistence.*` meters - `revisions{result=loaded |
unchanged | failed | disabled}`, total and per-table `rows`, per-table `batch.duration`, `batch.rows`,
`batch.attempts` and `batch.failures`, plus `transaction.phase.duration{phase=...}` for connection
acquisition, advisory-lock wait, revision lookup, clearing, commit and rollback. Ingestion-wide
`gtfs.static.ingestion.*` meters track `queue.age`, `archive.size`, end-to-end `duration{outcome=...}`,
and the active `in_flight` long-task timer. The `GTFSynq Domain` Grafana dashboard includes panels
for these signals. The `unchanged` outcome keeps a redelivery visibly distinct from a real replacement.

| Property | Default | Purpose |
|---|---|---|
| `gtfsynq.static.persistence.enabled` | `true` | Master switch for the write stage; when false, a validated archive is dropped and the stored revision stays |
| `gtfsynq.static.persistence.batch-size` | `5000` | Rows per reader callback and JDBC batch |

Storage is configured under `gtfsynq.storage.s3` in both apps - `ingest-app` writes archives
to it, `store-app` reads them back - so one set of deployment values serves both:

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch; when false, `ingest-app` polls and uploads nothing and `store-app` acknowledges static events without fetching |
| `endpoint` | `http://localhost:9000` | Endpoint of the S3-compatible service; leave empty for AWS S3 |
| `region` | `us-east-1` | Region requests are signed for |
| `bucket` | `gtfsynq-static` | Bucket holding the archives; created on first upload when missing, and only used as a fallback on the read path, which prefers the bucket named by the event |
| `access-key` / `secret-key` | empty | Static credentials; when empty the AWS default credentials chain is used |
| `path-style-access` | `true` | Address buckets as `endpoint/bucket`, as local S3 services require (`quarkus.s3.path-style-access`) |

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
- Static persistence: enabled, batch size `5000` rows
- Realtime polling interval: `60s`; static polling interval: `24h`
- Buffered sink flush interval: `10s`, hot data retention `1h`
- Management endpoints: `/q/health` (the apps never start Dev
  Services containers; external infrastructure comes from Compose)
- Console backend: `GTFS_API_GRPC_HOST` (default `localhost`) and
  `GTFS_API_GRPC_PORT` (default `8080`, the api-app HTTP port)
- Live-map transport overlay: disabled by default. Set
  `GTFS_MAP_TRANSPORT_OVERLAY_URL` to a Thunderforest Transport raster template
  (API key required) and optionally `GTFS_MAP_TRANSPORT_OVERLAY_OPACITY`
  (default `0.55`). The tiles are attributed to OpenStreetMap contributors and
  Thunderforest; their usage terms apply.

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

### Build scans

Every build publishes a [Develocity build scan](https://scans.gradle.com) — a
shareable report of what the build did (tasks, tests, timings, cache hits).
Publishing happens on developer machines and in CI alike; in CI the scan URL is
also collected into the GitHub Actions job summary by `gradle/actions/setup-gradle`.

```bash
./gradlew build --no-scan              # skip publishing for this build
./gradlew build -Dscan.uploadInBackground=false  # wait for the upload before exiting
```

### Run an app from Gradle (dev mode, live reload)

```bash
./gradlew :api-app:quarkusDev   # backend
./gradlew :ui-app:quarkusDev    # console → http://localhost:8083
```

## Container Image Build

All images are built with the Quarkus Jib extension — no Dockerfile and no
Docker daemon needed. Every app, including the Vaadin console, is also compiled
to a GraalVM native executable (see below):

```bash
# Every app (JVM)
./gradlew :api-app:imageBuild :ingest-app:imageBuild :store-app:imageBuild :ui-app:imageBuild

# Or a single app
./gradlew :api-app:imageBuild
```

On `main`, the `images` workflow detects which apps actually changed and builds
only those; a change under `modules/shared/`, `build-logic/` or the root Gradle
files selects every app, and a manual dispatch builds all four. Each selected
app still gets both its JVM and native image.

The Gradle commands produce local JVM images named
`ghcr.io/howruck/gtfsynq-<app>:0.0.1-SNAPSHOT` (e.g.
`ghcr.io/howruck/gtfsynq-api-app:0.0.1-SNAPSHOT`); `docker-compose.yaml`
references the `-native` variants. Registry, group, name and tag are configured
in each app's `application.yaml` and can be overridden on the command line, e.g.:

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

The Vaadin console (`ui-app`) is native-capable too: Vaadin's Quarkus extension
ships the native-image build steps (Atmosphere/websocket patches, reflection and
resource registrations) and embeds the production frontend bundle, so it needs no
extra flags or GraalVM configuration.

Compose already references the `-native` tags; swap them for the plain tags to
run the JVM images instead. Note the native run image has no shell, so any
`wget`/`curl`-based Compose healthcheck must be replaced (e.g. a Docker
`httpGet`-style check).

## Monitoring

The `docker-compose.monitoring.yaml` stack provides metrics, logs and traces. All
three signals are exported by the apps as OTLP and fanned out by a single
OpenTelemetry Collector to dedicated VictoriaMetrics stores:

| Component | URL | Purpose |
|---|---|---|
| Grafana | http://localhost:3000 | Dashboards for metrics, logs and traces (credentials from `GF_SECURITY_ADMIN_*` in `.env`) |
| OpenTelemetry Collector | http://localhost:4317 | Single OTLP intake (gRPC `:4317`, HTTP `:4318`) fanning out to the stores below |
| VictoriaMetrics | http://localhost:8428 | Metrics storage (Prometheus/MetricsQL-compatible; ingests OTLP) |
| VictoriaLogs | http://localhost:9428 | Log storage (LogsQL; ingests OTLP) |
| VictoriaTraces | http://localhost:10428 | Trace storage; serves the Jaeger query API (Grafana Jaeger datasource) |

The apps export telemetry via OpenTelemetry:

- **Metrics** are produced by Micrometer — including the custom `gtfs.*` /
  `offheap.*` domain meters, JVM extras, Agroal pool and Kafka client metrics — and
  exported over OTLP by the `quarkus-micrometer-opentelemetry` bridge.
- **Logs** are exported over OTLP by `quarkus-opentelemetry` (`quarkus.otel.logs.enabled`).
- **Traces** are on by default and cover the HTTP → Kafka → JDBC path.

Because metrics are pushed rather than scraped, the apps no longer expose a
Prometheus `/q/metrics` endpoint and there is no scrape job for them.

The console has a **Metrics** tab that reads the last hour from VictoriaMetrics
over its HTTP query API and charts ingest rate, feed validation results, the
trip update and stop time update rates, Kafka consumer lag and JVM heap usage.
It uses `GTFS_METRICS_URL` (default `http://victoriametrics:8428`;
`http://localhost:8428` in dev mode); set it to an empty value to disable the
view's data source.

If the apps run without this monitoring stack, they log repeated
`Failed to export ... otel-collector` warnings. Either start the stack with the
apps (`docker compose up -d` includes `docker-compose.monitoring.yaml`) or set
`QUARKUS_OTEL_SDK_DISABLED=true` in `.env` to turn the SDK off. The collector
endpoint is configurable through `QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT`.

The applications expose health endpoints on their own port:

- `GET /q/health`
- `GET /q/health/live`
- `GET /q/health/ready`

The host's rootless Podman socket (via `PODMAN_SOCKET` in `.env`) is read by the
`prometheus-podman-exporter` for container metrics:

```bash
PODMAN_SOCKET=/var/run/docker.sock docker compose up -d          # Docker
PODMAN_SOCKET=/run/podman/podman.sock docker compose up -d       # rootful Podman
```

## Educational Project

GTFSynq is primarily intended for educational and exploratory use. It is a good place to experiment with:

- modern Quarkus development
- real-time transit feed ingestion
- Kafka streaming
- Flyway database migrations
- TimescaleDB time-series modeling
- Protobuf-based transport formats
- GraalVM/Mandrel native image compilation
- off-heap state with the Java foreign memory API

## License

This project is licensed under the terms of the repository's `LICENSE` file.
