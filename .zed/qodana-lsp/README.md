# Qodana LSP for Zed

Shows [Qodana](https://www.jetbrains.com/qodana/) static analysis results as diagnostics in
[Zed](https://zed.dev), using [JetBrains/qodana-lsp](https://github.com/JetBrains/qodana-lsp)
(pinned to `v1.1.7`). It reads a local `qodana.sarif.json`, or downloads the latest report of a
Qodana Cloud project.

The server only reports anything after a client sends it the custom `setSourceLocation` and
`setSarifFile` requests, which Zed cannot send. `proxy/` is a small native Rust binary
(`qodana-lsp-proxy`) that sits between Zed and the server, sends them for you, and reloads the
report when the SARIF file changes. A wasm extension cannot run it, so it is installed separately.

**Platforms:** Linux (tested) and macOS (not tested). Windows is not supported.

## Prerequisites

- [Zed](https://zed.dev)
- [Rust](https://rustup.rs) with the `wasm32-wasip2` target: `rustup target add wasm32-wasip2`
- [Java](https://adoptium.net) 11 or newer on the `PATH`, to run the server
- [Git](https://git-scm.com), for the first-run build

There is no prebuilt release: the server is built from source on first use (see
[First run](#first-run)).

## Get started

1. Install the proxy: `cargo install --path proxy` in this directory. This puts
   `qodana-lsp-proxy` on the `PATH`; alternatively point `lsp.qodana.binary.path` at a built binary.
2. In Zed, run `zed: install dev extension` and pick this directory.
3. Enable the server per language in `settings.json`, for example
   `"languages": { "Java": { "language_servers": ["jdtls", "qodana"] } }`.
4. Produce a report: `qodana scan --results-dir .qodana/results`.
5. Open a file the report mentions. Its findings show up as diagnostics with the source
   `Qodana`. The first start builds the server instead, so restart the language server
   afterwards.

## First run

The server is built from source into the extension's work directory. The proxy clones the repo
with `git`, downloads a Temurin JDK 17 (the pinned Gradle 8.2 cannot run on newer JVMs, so your own
JDK is only used to run the server) and runs `./gradlew shadowJar`.

The build runs in the foreground and its output appears in Zed's language server log
(`zed: open log`). The proxy exits when the build is done, so run
`editor: restart language server` afterwards. If Zed gives up on the server before the build
finishes (its initialize timeout), the build stops with it and the next start resumes it; the
clone, the JDK and Gradle's own caches are reused.

## Qodana Cloud

Instead of scanning locally, the proxy can keep a report in sync with the latest report of a
Qodana Cloud project. It downloads it to `.qodana/cloud/qodana.sarif.json`, checks again every few
minutes, and fetches a report only when its id changes. The API calls are documented in
[`proxy/src/cloud.rs`](proxy/src/cloud.rs).

1. Set `cloud_project_id` (see [Settings](#settings)). It is the id in the project's Qodana Cloud
   URL: `https://qodana.cloud/projects/<id>/...`.
2. **Public projects need no token.**
3. **Private projects need a token**: put it in the `QODANA_TOKEN` environment variable of the
   shell Zed starts the project from. It is read from the environment on purpose, so it never
   lands in a settings file.

> **Untested for private projects:** the VS Code extension sends an OAuth access token as
> `Authorization: Bearer`. This proxy sends `QODANA_TOKEN` the same way, but only public projects
> were available to test with. If a private project fails, the proxy logs the HTTP error to Zed's
> language server log.

## Settings

```json
{
  "lsp": {
    "qodana": {
      "settings": {
        "sarif_path": ".qodana/results/qodana.sarif.json",
        "show_baseline_issues": true,
        "build_java_home": "/path/to/jdk-11-to-19",
        "cloud_project_id": "<project id>",
        "cloud_url": "https://qodana.cloud",
        "cloud_refresh_seconds": 300
      }
    }
  }
}
```

All keys are optional.

| Key | Default | Meaning |
| --- | --- | --- |
| `sarif_path` | `.qodana/results/qodana.sarif.json` | Report to show, relative to the worktree root. With `cloud_project_id` set, the default becomes `.qodana/cloud/qodana.sarif.json`. |
| `show_baseline_issues` | `true` | Set to `false` to show only new and updated issues. |
| `build_java_home` | downloads JDK 17 | A JDK 11-19 to build with. `~` is expanded, and a path to `bin/java` is accepted. |
| `cloud_project_id` | none | Turns on Qodana Cloud sync for this project. |
| `cloud_url` | `https://qodana.cloud` | Set it for a self-hosted Qodana Cloud. |
| `cloud_refresh_seconds` | `300` | Polling interval, at least 60. |

## Development

Build from a fresh clone:

```sh
cargo build --release -p qodana-lsp-proxy          # the proxy
cargo build --release --target wasm32-wasip2       # the extension (Zed does this on install)
```

Check your changes:

```sh
cargo fmt --all --check
cargo clippy -p qodana-lsp-proxy --all-targets
cargo test -p qodana-lsp-proxy
cargo test -p qodana-lsp-proxy -- --ignored        # live check against a public Qodana Cloud project (needs network)
```

The first start of a freshly built proxy needs the server built once (see
[First run](#first-run)); the unit tests do not.

## Contributing

- **Bugs and ideas for this extension:** open an issue at
  [HowRuck/GTFSynq](https://github.com/HowRuck/GTFSynq/issues).
- **Bugs in the Qodana server itself:** report them to JetBrains at
  [YouTrack](https://youtrack.jetbrains.com/issues/QD).
- **Changes:** follow the repository's [development guide](../../docs/development.md). Before
  opening a pull request, `cargo fmt`, `cargo clippy` and `cargo test` (see above) must pass.

## License

This extension is part of GTFSynq and is released under the
[GNU Affero General Public License v3.0](../../LICENSE). qodana-lsp is JetBrains' own project and
is downloaded and built separately under its own license (Apache-2.0).
