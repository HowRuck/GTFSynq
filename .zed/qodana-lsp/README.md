# Qodana LSP for Zed

Shows Qodana SARIF results as diagnostics in Zed, using
[JetBrains/qodana-lsp](https://github.com/JetBrains/qodana-lsp) (pinned to `v1.1.7`).

The server only reports anything after a client sends it the custom `setSourceLocation` and
`setSarifFile` requests, which Zed cannot send. `proxy/` is a small native Rust binary
(`qodana-lsp-proxy`) that sits between Zed and the server, sends them for you, and reloads the
report when the SARIF file changes. A wasm extension cannot run it, so install it separately.

## Install

1. `cargo install --path proxy` in this directory (puts `qodana-lsp-proxy` on the PATH), or set
   `lsp.qodana.binary.path` to a built binary.
2. In Zed, run `zed: install dev extension` and pick this directory (needs `rustup` with the
   `wasm32-wasip2` target).
3. Enable the server per language in `settings.json`, for example
   `"languages": { "Java": { "language_servers": ["jdtls", "qodana"] } }`.
4. Produce a report: `qodana scan --results-dir .qodana/results`.

Requirements: `java` (11+) on the PATH, plus `git` for the first-run build.

## First run

The release has no binaries, so the server is built from source into the extension's work
directory. The proxy itself clones the repo with `git`, downloads a Temurin JDK 17 (the pinned
Gradle 8.2 cannot run on newer JVMs, so your own JDK 25 or 27 is only used to run the server) and
runs `./gradlew shadowJar`.

The build runs in the foreground and its output appears in Zed's language server log
(`zed: open log`). The proxy exits when the build is done, so restart the language server
(`editor: restart language server`) afterwards. If Zed gives up on the server before the build
finishes (its initialize timeout), the build stops with it and the next start resumes it; the
clone, the JDK and Gradle's own caches are reused.

## Qodana Cloud

The proxy can keep a local report in sync with the latest report of a Qodana Cloud project. It
follows the calls JetBrains' VS Code extension makes: it finds the API through
`{site}/api/versions`, reads the project's newest report from `projects/{id}/timeline`, gets the
`qodana.sarif.json` download URL from `reports/{id}/files`, and downloads it (without the token) to
`.qodana/cloud/qodana.sarif.json`. A new report is fetched only when its id changes.

1. Set `cloud_project_id` in the settings below. It is the id in the project's Qodana Cloud URL
   (`https://qodana.cloud/projects/<id>/...`).
2. **Public projects need no token** (checked against the live service).
3. **Private projects need a token**: put it in the `QODANA_TOKEN` environment variable of the shell
   Zed starts the project from. It is read from the environment on purpose, so it never lands in a
   settings file.

The HTTP flow is covered by a local mock-server test, and
`cargo test -p qodana-lsp-proxy -- --ignored` runs a live check against a public project.

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

All keys are optional. `sarif_path` is relative to the worktree root. Set `show_baseline_issues`
to `false` to show only new and updated issues. `build_java_home` skips the JDK download.
`cloud_url` defaults to `https://qodana.cloud` (set it for a self-hosted instance) and
`cloud_refresh_seconds` to 300 (minimum 60). With `cloud_project_id` set and no `sarif_path`, the
report path becomes `.qodana/cloud/qodana.sarif.json`.

## Development

`cargo test -p qodana-lsp-proxy` and `cargo clippy -p qodana-lsp-proxy --all-targets`.
