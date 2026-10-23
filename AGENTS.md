# Agent Guidance

## CI workflow naming

Every CI workflow must use **kebab-case** for names and identifiers —
lowercase words joined by single hyphens (`[a-z0-9]+(-[a-z0-9]+)*`).

This applies to all of the following in `.github/workflows/*.yml`:

- workflow file names, e.g. `images.yml`
- the workflow `name`, e.g. `name: images`
- job ids, e.g. `build-push:`
- job and step display names, e.g. `name: set-up-java`
- step ids, e.g. `id: build-native`
- matrix keys, e.g. `app: [api-app, ingest-app, ...]`

Reserved matrix entries `include` and `exclude` are exempt.

Do not use Title Case, snake_case or camelCase for these fields. When adding a
job or step, write its display name as a hyphenated imperative phrase, for
example `build-and-push-native-image` rather than `Build and push native image`.
