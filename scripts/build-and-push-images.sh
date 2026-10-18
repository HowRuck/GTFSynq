#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

apps=(api-app ingest-app store-app)

for app in "${apps[@]}"; do
  echo "Building and pushing JVM image for ${app}"
  ./gradlew ":${app}:imageBuild" \
    -Dquarkus.container-image.push=true

  echo "Building and pushing native image for ${app}"
  ./gradlew ":${app}:imageBuild" \
    -Dquarkus.container-image.push=true \
    -Dquarkus.container-image.name="gtfsynq-${app}-native" \
    -Dquarkus.native.enabled=true \
    -Dquarkus.native.container-build=true \
    -Dquarkus.package.jar.enabled=false
done
