# GTFSynq Development Guide

This is the working guide for changing GTFSynq. It connects the task in front of you to the
right skill under `.agents/skills/`, then lists the project rules that the skill's output must
respect.

Skills are task playbooks, not documentation to skim once. Load the matching skill before you
start, follow its workflow and output rules, and use this guide for the project-specific
constraints that the skill cannot know.

**Contents**

1. [How to use the skills](#1-how-to-use-the-skills)
2. [Task routing](#2-task-routing)
3. [Project facts the skills must respect](#3-project-facts-the-skills-must-respect)
4. [Workflows](#4-workflows)
5. [Skill-specific guardrails](#5-skill-specific-guardrails)
6. [Verification and definition of done](#6-verification-and-definition-of-done)
7. [Do not](#7-do-not)
8. [Quick reference](#8-quick-reference)

---

## 1. How to use the skills

1. **Pick the matching skill** from the routing table in section 2. Load at most one or two at a
   time.
2. **Read the whole `SKILL.md`**, plus any reference file it points at. When it asks for a
   Vaadin API or design token, the answer must come from the skill reference or the Vaadin MCP,
   not from memory.
3. **Apply the skill's output rules.** Where a skill's default conflicts with a rule in this
   guide, this guide wins for this repository.
4. **Verify with the checklist in section 6.** A skill's workflow ends with verification; do not
   stop at "it compiles".

The skills are checked in under `.agents/skills/<name>/SKILL.md`; the `skills-lock.json` at the
repository root records where each came from. In OpenCode, invoke the skill tool with the
directory name (for example `aura-theme`); other editors can read the same `SKILL.md` and its
reference files directly.

### Configured MCP servers

The repository's `opencode.json` configures seven MCP servers. They are optional helpers: if one
is unavailable or fails, fall back to the local source, `./gradlew` or a local Playwright script
rather than guessing.

| Server | Use | Notes |
|---|---|---|
| `vaadin` | Vaadin 25.4 docs, component styling, Java API symbols, primer and new APIs | Remote (`https://mcp.vaadin.com/docs`); query text leaves the machine. |
| `vaadin-directory` | Search Vaadin Directory add-ons | Remote (`https://vaadin.com/directory/mcp`); use only when a built-in component or official extension is not enough, and confirm Vaadin 25.4 compatibility before adding the dependency. |
| `gradle` | Inspect Gradle tasks, dependencies and the build model | Local via `jbang run --quiet --fresh gradle-mcp@rnett`; the first run may fetch jbang and the tool. Pair with `gradle-best-practices`. |
| `quarkus-agent` | Start/stop/restart Quarkus apps, proxy Dev MCP tools, search Quarkus docs and load extension skills | Local via `jbang quarkus-agent-mcp@quarkusio`; experimental, requires JBang, and the first docs search builds an index. |
| `grafana` | Validate provisioned dashboards, panel queries and datasource health | Local via `scripts/run-grafana-mcp.sh` (`uvx mcp-grafana`); read-only, sources the Grafana admin credentials from `.env`, and needs Grafana on `localhost:3000`. |
| `playwright` | Drive the running console for visual checks | Local via `npx @playwright/mcp@latest`; launches the **Google Chrome** channel at `/opt/google/chrome/chrome`, not the system Chromium. See [Playwright MCP](#playwright-mcp). |
| `podman` | Inspect containers, read logs, check ports and start/stop individual containers | Local via `npx -y podman-mcp-server@latest`; uses the Podman socket or CLI. **Write-capable**: `container_remove`, `image_remove`, `image_push` and `image_build` mutate host state. No compose support, and `container_run` accepts only image, environment and ports (no volumes, networks or extra hosts). |

Lathe is the intended Java language server and MCP replacement for the removed Serena setup, but
its Gradle settings plugin is still pending its first Gradle Plugin Portal review, so
`id("io.github.ag-libs.lathe") version "0.3.1"` does not resolve yet. The plugin line in
`settings.gradle.kts` is commented out, and no `lathe` MCP server is configured. `.lathe/` is
already ignored. Once [the plugin page](https://plugins.gradle.org/plugin/io.github.ag-libs.lathe)
resolves:

1. Uncomment the plugin line in `settings.gradle.kts`.
2. Run `./gradlew latheSync -Plathe.capture.only=true` to write `.lathe/` and the MCP launcher.
3. Add a local server to `opencode.json`:
   ```json
   "lathe": {
     "type": "local",
     "command": ["bash", ".lathe/lathe-mcp-launcher.sh"]
   }
   ```

Tool names carry the server name (for example `vaadin.search_vaadin_docs` or
`playwright.browser_navigate`). When a server is down, say so and continue with the local
fallback; do not block a change on MCP availability.

Local servers are started when OpenCode starts. After changing `opencode.json` or a wrapper
script, restart OpenCode (or reconnect the server) so the new tool list is loaded. The Grafana
wrapper needs `uvx`, the Quarkus and Gradle servers need JBang, Playwright needs Google Chrome
installed (see [Playwright MCP](#playwright-mcp)), and Podman needs `npx` plus a running Podman
socket or CLI.

`podman` is the only write-capable server in this list. Use it to inspect state and read logs;
only stop or remove containers you started yourself, and never remove, push or rebuild images that
belong to the shared image store. It cannot read compose files, so bring the stack up with
`podman-compose`/`docker compose` and use the MCP for per-container checks.

#### Vaadin documentation

`ui-app` is on **Vaadin 25.4 (alpha)**. Before writing against an API you are not certain of, use
the configured `vaadin` MCP server:

- `get_vaadin_primer` and `get_new_apis` for what changed since your training data.
- `search_vaadin_docs` with `vaadin_version: "25.4"` and `ui_language: "java"`.
- `get_java_symbol` for a type's real members, including inherited mixins.
- `get_component_styling` for theme variants and shadow parts.
- `vaadin-directory` only when you actually need an add-on; it is a separate server.

#### Quarkus Agent MCP

`quarkus-agent` is the Quarkus-specific agent server configured in `opencode.json`. It is
experimental and requires JBang.

- For an existing app, use `quarkus_start` when the agent should own the process, or
  `quarkus_attach` when the app is already running in your terminal. All four apps enable Dev MCP
  in their `%dev` profile, so an app started with `quarkusDev` is attachable without a manual
  toggle. The apps use `application.yaml`, so pass the port explicitly for non-default ports:
  `ui-app` 8083, `ingest-app` 8081, `store-app` 8082; `api-app` uses the 8080 default.
- Call `quarkus_skills` before writing backend code to load the extension-specific patterns.
- Use `quarkus_searchDocs` for Quarkus questions; pass `projectDir` so the answer matches the
  project's Quarkus version.
- The agent proxies the application's Dev MCP tools. Direct Dev MCP is available at
  `http://localhost:<port>/q/dev-mcp`; `api-app` uses 8080 and `ui-app` uses 8083. It is enabled
  in `%dev` only and is never exposed by a production build.
- This repository has four Quarkus apps. The agent manages a project directory at a time; start
  or attach to the app you are changing. The root `devInfra`/Compose stack is not started by the
  agent, so for a full-stack run start the stack or the app in a terminal and use
  `quarkus_attach`.

#### Playwright MCP

`playwright` drives the running console for visual checks. It launches the installed **Google
Chrome** (the `chrome` channel), not a browser bundled with the MCP package.

- Prerequisite: Google Chrome at `/opt/google/chrome/chrome` (verified with 157.0.8103.38).
  `npx playwright install chrome` installs it on a bare machine. The system Chromium at
  `/usr/bin/chromium` is **not** picked up by the MCP.
- If `browser_navigate` fails with `Chromium distribution 'chrome' is not found`, Chrome is
  missing. Install it and retry; the server spawns the browser per call, so no restart is needed.
- The server runs **headed** by default, so a Chrome window opens on the desktop. Add
  `"--headless"` to the `command` array in `opencode.json` to keep it off-screen; restart OpenCode
  afterwards so the new flags load.
- File outputs are limited to the allowed roots: the repository root and `.playwright-mcp/` in it.
  A path such as `/tmp/shot.png` is refused with `outside allowed roots`. Write screenshots to
  `.playwright-mcp/`, which is git-ignored, and clean the folder up when the check is done.
- `browser_take_screenshot` requires `scale` (`"css"` matches the viewport); `filename` alone fails
  argument validation.
- The console must be running (`./gradlew :ui-app:quarkusDev`, `http://localhost:8083`). With no
  `api-app` or VictoriaMetrics, the data views show their error or empty states; check layout and
  states, not data.
- When the MCP is unavailable, drive the system Chromium directly; it renders the same console:

  ```bash
  chromium --headless --no-sandbox --window-size=1440,900 \
    --virtual-time-budget=15000 --screenshot=shot.png http://localhost:8083/metrics
  ```

  Force dark mode with `--force-dark-mode` and emulate a narrow viewport with `--window-size`.

---

## 2. Task routing

| Task | Load | Notes |
|---|---|---|
| Theme, palette, typography, density, dark mode | `aura-theme` | Read `references/property-values.md`; use curated presets unless a brand colour is explicit. |
| New or polished view, layout, spacing, motion, responsive design | `vaadin-frontend-design` | Pair with `aura-theme` when the work touches theme tokens. |
| Form, entity editor or data-entry view | `vaadin-form-layout` | Then `vaadin-frontend-design` for the surrounding view. |
| TypeScript in `ui-app/src/main/frontend` (MapLibre, charts) | `typescript-clean-code-cookbook` + `typescript-pragmatic-programmer` | Both are review lenses; keep the map/DOM code framework-free. |
| Grafana dashboard JSON, panels, layout, variables, transformations | `dashboarding` | This repo provisions dashboards from files; use the skill's JSON schema and panel guidance, not its HTTP-API push workflow. |
| PromQL / MetricsQL queries for dashboard panels | `promql` + `prometheus` | VictoriaMetrics is PromQL/MetricsQL-compatible; validate queries against VictoriaMetrics rather than assuming Prometheus parity. |
| Grafana OSS provisioning, data sources, roles, annotations | `grafana-oss` | Matches the self-hosted Grafana 13 stack under `monitoring/grafana/`. |
| Data source provisioning for a new plugin | `datasources-provisioning` | Only when adding a datasource; the existing VictoriaMetrics/VictoriaLogs/VictoriaTraces datasources are already configured. |
| OTLP instrumentation, collector or signal routing | `opentelemetry` | The apps export OTLP; the collector config is `monitoring/otel-collector/config.yaml`. |
| Gradle build, dependency or task wiring change | `gradle-best-practices` | Audit and fix findings; do not invent a parallel build convention. |
| Gradle wrapper / Gradle version bump | `gradle-wrapper-upgrade` | Use the skill's copy-paste commands rather than hand-editing `gradle-wrapper.properties`. |
| Quarkus version or project-structure check | `quarkus-update` | Current platform is Quarkus 4.0.0.Beta3; check before upgrading. |
| PostgreSQL schema or table design | `postgres` + `design-postgres-tables` | `postgres` is the umbrella; load the focused skill next. |
| PostGIS / geometry / spatial index design | `design-postgis-tables` | Only when a table actually stores geometry or geography. |
| Understanding an existing database before querying it | `schema-exploration` | Read-only catalog queries first; ask before inspecting row data. |
| Safe schema migration on existing data | `postgres-database-migration` | Plan lock level, test on a copy, plan rollback. |
| New TimescaleDB hypertable, compression or retention | `setup-timescaledb-hypertables` | Use for any new insert-heavy time-series table. |
| Deciding whether an existing table should be a hypertable | `find-hypertable-candidates` | Score candidates before changing anything. |
| Converting an identified table to a hypertable | `migrate-postgres-tables-to-hypertables` | Do not skip the candidate audit. |
| Analytical time-series SQL (percentiles, rates, time-weighted averages, OHLC) | `timescaledb-hyperfunctions` | Check whether `timescaledb_toolkit` is installed first. |
| Vector embeddings / semantic search | `pgvector-semantic-search` | Only for an actual similarity-search feature. |
| Combined keyword + semantic search | `postgres-hybrid-text-search` | Requires pgvector and the BM25 extension. |
| New Quarkus MCP server | `scaffold-quarkus-mcp-server` | Rare here; GTFSynq is an application, not an MCP server. |
| Migrating a Spring Boot app | `migrate-spring-to-quarkus` | Not applicable to the current modules; no Spring app exists. |

If two skills apply, load the narrower one first (for example `design-postgres-tables` before
the umbrella `postgres` reference), then the broader one for context.

---

## 3. Project facts the skills must respect

### Modules

| Module | Role | Rule |
|---|---|---|
| `modules/shared` | Framework-agnostic domain, protobuf, codecs, utilities | No Quarkus/Vaadin/persistence dependencies; compile-only Jakarta APIs. |
| `modules/ingest-app` | GTFS-RT polling, static download, Kafka publishing, S3 | Runs on virtual threads; keep blocking I/O off the event loop. |
| `modules/store-app` | Kafka consumer, validation, persistence, Flyway | Owns `src/main/resources/db/migration`; the only module that writes the database. |
| `modules/api-app` | Read API: REST + `GtfsQueryService` gRPC | The console's only data source; gRPC shares the HTTP port (8080). |
| `modules/ui-app` | Vaadin Flow console | Pure gRPC client: no datasource, no REST calls, no direct database access. |

### Build and toolchain

- Java 25, Gradle wrapper, `build-logic/` convention plugins.
- Quarkus 4.0.0.Beta3, Vaadin 25.4.0-alpha4, TimescaleDB, Kafka, Protobuf 4.
- Versions live in `gradle/libs.versions.toml`; do not hardcode versions already managed by a
  BOM. Vaadin's BOM wins where Vaadin and Quarkus overlap.
- Formatting: Spotless with Open Java Format (`dev.openjavaformat.java-format`). Run
  `./gradlew spotlessApply` before handing work back.
- `ingest-app` and `store-app` map the relocated `io.floci:testcontainers-floci` coordinate to
  `testcontainers-floci-aws`. This is a workaround for the Amazon Services extension's 2.17.0
  relocation, not a build preference; keep it until the extension declares the new coordinates.
- CI workflow names, job ids, step ids and matrix keys are kebab-case. See `AGENTS.md`.

### UI rules

- Aura is loaded explicitly in `AppShell` with `@StyleSheet(Aura.STYLESHEET)` **before**
  `styles.css`; `@ColorScheme(SYSTEM)` enables light and dark.
- Theme tokens belong in `src/main/resources/META-INF/resources/aura-theme.css`. Application
  CSS belongs in `styles.css`. Do not put app CSS back into the theme file.
- Use Aura tokens (`--aura-*`, `--vaadin-*`). Do **not** use `LumoUtility` or `lumo-*` tokens
  with Aura. Do not hardcode colours when a token exists.
- Prefer component theme variants (`ButtonVariant`, `BadgeVariant`, `GridVariant`) and shadow
  parts before writing custom CSS.
- Server push is enabled; long backend calls run on worker/virtual threads and update the UI via
  `ui.access`. Never call `getTranslation` or touch components from a background thread.
- Navigation uses real `RouterLink`s inside the top tabs; keep open-in-new-tab and history
  behaviour working.
- Every user-facing string goes through `getTranslation` and must be added to all three bundles:
  `translations.properties`, `translations_de.properties`, `translations_fr.properties`.
- Reuse the existing building blocks: `PageHeader`, `Columns`, `Filters`, `LazyGrid`,
  `UiComponents`, `MainLayout`.
- The console must stay native-image friendly: avoid reflection in JSON/serialization paths,
  and check the native build when adding a library or an API that may reflect.

### Database rules

- The only schema owner is `store-app`; migrations live in
  `modules/store-app/src/main/resources/db/migration`.
- Never edit an applied Flyway migration. Add the next `VYYYYMMDD_NN__description.sql`.
- Time-series tables are TimescaleDB hypertables; check existing compression, retention and
  chunk settings before adding a new one.
- Test migrations against a copy of the dev database, with a rollback or forward-fix plan.
- `schema-exploration` is read-only; do not inspect or export row data without approval.

---

## 4. Workflows

### 4.1 UI / Vaadin change

1. Load `vaadin-frontend-design`; add `aura-theme` for tokens, `vaadin-form-layout` for forms.
2. Inspect the current view, its component, `MainLayout`, `aura-theme.css` and `styles.css`
   before choosing a pattern.
3. State the direction in one line, for example: "dense operations console, warm orange accent,
   Stone surfaces, Inter, compact tables". Every choice should reinforce it.
4. Change in this order: theme token → component variant → shadow part/custom CSS.
5. Add or change strings in all three translation bundles.
6. Keep data loading off the session lock; use `CompletableFuture`/worker threads plus
   `ui.access`, and capture translations on the UI thread.
7. Run:
   ```bash
   ./gradlew :ui-app:compileJava :ui-app:test :ui-app:spotlessCheck
   ```
8. Start the console and check it visually:
   ```bash
   ./gradlew :api-app:quarkusDev     # terminal 1
   ./gradlew :ui-app:quarkusDev      # terminal 2, http://localhost:8083
   ```
   If the Compose services are already running, add `-x :devInfra`.
9. Use the `playwright` MCP server (or the headless Chromium fallback) to check:
   - all routes: `/`, `/feeds`, `/routes`, `/stops`, `/trips`, `/live`, `/metrics`;
   - light and dark (`colorScheme: 'light'` and `'dark'`);
   - 1600 px, 820 px and 480 px widths;
   - zero unexpected console errors and failed requests (map tiles may be unavailable offline);
   - no page-level horizontal overflow (grids may scroll internally);
   - navigation links are real anchors and route correctly;
   - filters and icon-only buttons have accessible names;
   - `prefers-reduced-motion` disables non-essential animation.

   The MCP launches the installed Google Chrome. If it reports that the `chrome` distribution is
   missing, follow [Playwright MCP](#playwright-mcp). Save screenshots under `.playwright-mcp/`
   and remove them afterwards so the working tree stays clean.

   If `api-app` or VictoriaMetrics is not running, the data views show their expected error or
   empty states; verify the layout and states, not the data.
10. Update `README.md` only when behaviour or setup changed.

### 4.2 Backend / Quarkus change

1. Load `quarkus-update` only for a version or project-structure change; it is not a general
   coding guide.
2. Keep the module boundary intact: `shared` stays framework-agnostic, `ui-app` stays a pure
   gRPC client, `store-app` owns writes.
3. Respect the Quarkus BOM; use `application.yaml` with the module's existing `%dev` profile.
4. For native-impacting changes (reflection, serialization, new client), build the native image:
   ```bash
   ./gradlew :<app>:build -Dnative
   ```
5. Run:
   ```bash
   ./gradlew :<app>:test :<app>:spotlessCheck
   ./gradlew :<app>:build
   ```
6. Check `/q/health` and the app log for the affected app.

### 4.3 Database / TimescaleDB change

1. Start with `schema-exploration` for any existing schema; use read-only catalog queries.
2. Load the design skill that matches the change:
   - ordinary table: `postgres` + `design-postgres-tables`;
   - spatial table: `design-postgis-tables`;
   - new time-series table: `setup-timescaledb-hypertables`;
   - hypertable audit: `find-hypertable-candidates`, then
     `migrate-postgres-tables-to-hypertables`;
   - migration on existing data: `postgres-database-migration`;
   - analytics: `timescaledb-hyperfunctions`.
3. Add the next Flyway migration under `store-app`; never edit an applied one.
4. Test on a copy:
   ```bash
   docker compose -f docker-compose.dev.yaml up -d timescaledb
   # apply against a test database, then run the affected queries and EXPLAIN
   ```
5. Document the lock level, expected duration, rollback/forward-fix, and any backfill.
6. Run `store-app` tests and verify the migration applies cleanly on a fresh database.

### 4.4 Build, dependency or CI change

1. Load `gradle-best-practices`; use `gradle-wrapper-upgrade` for a wrapper/Gradle version bump.
2. Change the version catalog and keep BOM-managed dependencies versionless.
3. Keep the build logic in `build-logic/`; do not duplicate conventions in app modules.
4. Follow the `AGENTS.md` kebab-case rule for every CI workflow, job, step and matrix key.
5. Run:
   ```bash
   ./gradlew spotlessApply
   ./gradlew build
   ```
6. If the dependency affects `ui-app` frontend or native image, run the UI build and the native
   build as well.
7. Renovate opens the dependency PRs. Routine Gradle patch/minor updates auto-merge only after
   `resolve-dependencies` passes; Quarkus/Vaadin prereleases, majors, the Gradle wrapper, Docker
   images and GitHub Actions stay manual. Do not merge a Renovate PR while its check is failing.

### 4.5 Grafana dashboards and observability change

1. Load `dashboarding` for the dashboard JSON; add `promql`/`prometheus` for panel queries,
   `grafana-oss` for provisioning, `datasources-provisioning` for a new datasource plugin, and
   `opentelemetry` when changing instrumentation or the collector.
2. The dashboards are **file-provisioned**, not pushed through the Grafana API. Edit the JSON under
   `monitoring/grafana/dashboards/`; Grafana reloads the folder every 10 seconds.
3. Keep the existing UIDs and titles unless the change requires a new dashboard:
   `gtfsynq-apps`, `gtfsynq-domain`, `gtfsynq-kafka`, `gtfsynq-logs`, `gtfsynq-postgres`.
4. Existing datasource UIDs are fixed: `VictoriaMetrics` (default), `VictoriaLogs`,
   `VictoriaTraces`. Do not hardcode container-only hostnames in panels; use the provisioned
   datasource UID.
5. VictoriaMetrics speaks PromQL/MetricsQL, not Prometheus exactly. The `promql` and `prometheus`
   skill examples are Prometheus-flavored; validate the final query in Grafana Explore or VMUI.
   The repo has no Loki/Tempo/Mimir, so the Loki/Tempo/Mimir parts of those skills do not apply.
6. Validate and reload:
   ```bash
   jq empty monitoring/grafana/dashboards/*.json
   docker compose up -d grafana   # or the full stack with `docker compose up -d`
   curl -s http://localhost:3000/api/health
   ```
   Then validate through the read-only `grafana` MCP server:
   - `search_dashboards` with `query: "GTFSynq"` — all five dashboards should be present.
   - `get_dashboard_summary` for each changed UID — check panel count, panel types and variables.
   - `get_dashboard_panel_queries` — confirm each changed panel uses the expected
     `VictoriaMetrics`/`VictoriaLogs`/`VictoriaTraces` datasource and query.
   - `check_datasources_health` — confirms the datasources are reachable before trusting query
     results.
   - `run_panel_query` only understands the core Prometheus/Loki/SQL datasource types. Every panel
     here uses the `victoriametrics-metrics-datasource` or `victoriametrics-logs-datasource` plugin,
     so this tool answers `datasource type ... is not supported by run_panel_query`. Always pass
     explicit variables too: it refuses panels whose variable is on `All`.
   - Prove the panels return data instead by querying the store directly:
     ```bash
     # PromQL/MetricsQL, one expression at a time (substitute $__rate_interval and $vars first)
     curl -sG --data-urlencode 'query=sum by (application) (rate(http.server.requests_count[5m]))' \
       http://localhost:8428/api/v1/query | jq '.status, (.data.result | length)'
     # LogsQL
     curl -sG --data-urlencode 'query=* | stats by ("service.name") count()' --data-urlencode 'start=-1h' \
       http://localhost:9428/select/logsql/query
     ```
     A `success` status proves the expression parses; a non-empty `result` proves the metric or
     field exists. Open the dashboard itself to confirm units, legends and thresholds.
   - Use `get_dashboard_by_uid` only when the full JSON is really needed; it is large.
   The MCP server runs with `--disable-write`, so it cannot change the dashboards. Keep editing
   the files under `monitoring/grafana/dashboards/` and let file provisioning apply them.
7. Metric and log-field naming comes from the OTLP pipeline, not from a Prometheus scrape:
   - Micrometer names keep their dots (`gtfs.sink.db.write`, `http.server.requests`), histograms
     add `_sum`/`_count`, and the decaying max gauge is `.max` — not `_max`.
   - The common `application` tag is added by `ApplicationTagConfiguration`, so it is a label on
     every app meter.
   - VictoriaLogs stores the OTel resource attribute as `service.name`; `service_name` groups every
     line into one unnamed bucket. `severity_text` and `_msg` are also present.
   - Confirm a name against the source before adding a panel:
     `grep -rn 'Counter.builder\|Timer.builder\|Gauge.builder' modules/*/src/main/java`.

### 4.6 Frontend TypeScript change

1. Load `typescript-clean-code-cookbook` and `typescript-pragmatic-programmer`.
2. Keep types explicit at the DOM/MapLibre boundary; avoid `any`.
3. The custom elements in `src/main/frontend/*.ts` talk to Java through element properties and
   DOM events; do not add a client-side data layer.
4. Keep the committed `pnpm-lock.yaml` in sync; use the Vaadin pnpm toolchain rather than a
   separate package manager.
5. Verify in the browser in both schemes and at narrow widths; map controls must remain usable.

---

## 5. Skill-specific guardrails

### `aura-theme`

- Read `references/property-values.md`; use the curated presets for accent/background pairs
  unless a brand colour is explicitly required.
- Only write customizable properties. Never set a read-only property such as
  `--aura-accent-color`, `--aura-surface-color`, or `--aura-font-size-*`.
- Set `color-scheme` when the app supports both schemes; GTFSynq does this through
  `@ColorScheme(SYSTEM)` and the theme file.
- Put side-nav/button component patterns in `styles.css`, not in the token block, and only when
  the app actually uses those components.
- GTFSynq uses a top `Tabs` navigation, not `SideNav`; do not copy side-nav rules into it.

### `vaadin-frontend-design`

- Pick one direction and keep it. GTFSynq is a dense operations console: compact type, one warm
  accent, flat surfaces, elevated cards, restrained motion.
- Prefer theme tokens and variants over custom CSS; never use `LumoUtility` with Aura.
- Keep animations short and purposeful; respect `prefers-reduced-motion`.
- Verify light/dark and narrow widths. A design change that only looks right at 1600 px is not
  finished.

### `vaadin-form-layout`

- Use `FormLayout` sections and responsive columns rather than hand-rolled grid CSS.
- Keep field labels and helper text translated; wire validation through Binder only when the
  view actually edits a bean.

### `postgres*` / `timescaledb*`

- `schema-exploration` is read-only; ask before inspecting rows or exporting statistics.
- `design-postgres-tables` and `postgres-database-migration` assume real production data:
  plan locks, backfills and rollback before writing SQL.
- `setup-timescaledb-hypertables` is for new time-series tables; do not convert an existing
  table without `find-hypertable-candidates` and a migration plan.
- `timescaledb-hyperfunctions` requires `timescaledb_toolkit`; confirm it is installed.

### `gradle-best-practices` / `gradle-wrapper-upgrade`

- Run the audit before proposing a rewrite; the project already has convention plugins, a
  version catalog and Isolated Projects-compatible build logic.
- Prefer the smallest fix that keeps the existing structure. Do not introduce a second way to
  declare dependencies or tasks.

### TypeScript skills

- They are review lenses, not a reason to restructure the map code. Keep the existing element
  lifecycle and property/event boundary.

### Grafana dashboard skills

- `dashboarding` is written around `POST /api/dashboards/db`; this repository uses file
  provisioning. Reuse its panel/variable/transformation JSON guidance, but edit the files and
  verify the reload instead of pushing through the API. Keep the existing `schemaVersion: 39`
  unless you deliberately migrate all dashboards.
- `promql` and `prometheus` are Prometheus-flavored. VictoriaMetrics is PromQL/MetricsQL
  compatible, not identical; validate the query in Grafana Explore or VMUI.
- `grafana-oss` matches the self-hosted Grafana OSS stack. Use it for provisioning YAML, data
  sources, roles, annotations and health checks.
- `datasources-provisioning` is for adding a new datasource plugin; the existing VictoriaMetrics,
  VictoriaLogs and VictoriaTraces datasources are already provisioned.
- `opentelemetry` is for the OTLP pipeline; the apps push metrics, logs and traces through
  `monitoring/otel-collector/config.yaml`. Do not switch them back to a Prometheus scrape job.
- There is no Loki/Tempo/Mimir here: logs and traces are VictoriaLogs and VictoriaTraces.
- Validate with the read-only `grafana` MCP server, not by pushing through the API. It runs
  `scripts/run-grafana-mcp.sh`, sources the Grafana admin credentials from `.env`, and needs
  `uvx` plus Grafana on `localhost:3000`. Start Grafana before a validation run; call
  `check_datasources_health` before `run_panel_query` so a missing VictoriaMetrics/VictoriaLogs/
  VictoriaTraces instance is reported as a datasource problem rather than a panel failure.

---

## 6. Verification and definition of done

### Verification matrix

| Change | Commands | Manual checks |
|---|---|---|
| UI | `./gradlew :ui-app:compileJava :ui-app:test :ui-app:spotlessCheck` | All routes, light/dark, 1600/820/480, console clean, no page overflow, keyboard/focus, reduced motion |
| Backend | `./gradlew :<app>:test :<app>:spotlessCheck :<app>:build` | `/q/health`, app log, gRPC/REST behaviour |
| Native | `./gradlew :<app>:build -Dnative` | Startup, health, representative query |
| Database | `./gradlew :store-app:test` plus a test database | Migration applies on a copy, query plan, rollback/forward-fix |
| Build/CI | `./gradlew build` | Workflow names and ids are kebab-case |
| Dashboards | `jq empty monitoring/grafana/dashboards/*.json`; then `grafana` MCP: `search_dashboards` → `get_dashboard_summary` → `get_dashboard_panel_queries` → `check_datasources_health` → `run_panel_query` | All five UIDs present, expected datasource/queries, changed panels return data |
| Frontend TS | `./gradlew :ui-app:build` | Map/chart behaviour in both schemes and narrow widths |

### Definition of done

- [ ] The matching skill was loaded and its output rules were followed.
- [ ] The change is the smallest one that solves the problem; no unrelated refactors.
- [ ] User-facing text exists in English, German and French.
- [ ] Aura tokens and theme variants are used; no Lumo utilities, no hardcoded colours.
- [ ] The UI was checked in light and dark, at desktop and narrow widths.
- [ ] No new console errors or failed requests.
- [ ] `spotlessCheck` and the affected tests pass.
- [ ] Native-image impact was considered, and tested when relevant.
- [ ] Database migrations are new files, tested on a copy, with a rollback/forward-fix.
- [ ] Dashboard JSON parses, Grafana provisions it without errors, and the changed panels return data.
- [ ] CI names follow the kebab-case rule in `AGENTS.md`.
- [ ] `README.md` or this guide was updated when behaviour or setup changed.

---

## 7. Do not

- Do not add a datasource or direct SQL access to `ui-app`; it is a gRPC client.
- Do not add `com.vaadin:copilot` to the production classpath; Copilot ships through the Vaadin
  Quarkus extension's dev-mode classpath and is disabled in production.
- Do not call `getTranslation` or `VaadinService`-dependent APIs from background threads.
- Do not edit applied Flyway migrations or run destructive SQL without a tested rollback.
- Do not use `LumoUtility` or Lumo tokens in this Aura application.
- Do not add hardcoded versions for dependencies managed by the Quarkus/Vaadin BOMs.
- Do not commit `build/`, `src/main/frontend/generated/`, dev bundles, or local IDE files.
- Do not add a second theme, a second build convention, or a second frontend package manager.

---

## 8. Quick reference

```bash
# Format and verify
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew build

# UI
./gradlew :ui-app:compileJava :ui-app:test
./gradlew :ui-app:quarkusDev            # http://localhost:8083
./gradlew :ui-app:quarkusDev -x :devInfra   # reuse an already running Compose stack

# Backend
./gradlew :api-app:quarkusDev           # http://localhost:8080
./gradlew :ingest-app:quarkusDev        # http://localhost:8081
./gradlew :store-app:quarkusDev         # http://localhost:8082
./gradlew :<app>:build -Dnative

# Dev infrastructure
docker compose -f docker-compose.dev.yaml up -d
docker compose -f docker-compose.dev.yaml down

# Monitoring stack
jq empty monitoring/grafana/dashboards/*.json
docker compose -f docker-compose.monitoring.yaml up -d
docker compose -f docker-compose.monitoring.yaml logs -f grafana
curl -s http://localhost:3000/api/health   # Grafana credentials: GF_SECURITY_ADMIN_*
```

Health endpoints are at `/q/health` on each app's HTTP port. Grafana is at
`http://localhost:3000`; dashboards are in the **GTFSynq** folder.
