#!/usr/bin/env bash
#
# Regenerates the Gradle wrapper twice for the distribution named in
# gradle/wrapper/gradle-wrapper.properties.
#
# Renovate calls this as a postUpgradeTasks command after it bumps
# distributionUrl, and it can be run by hand. Two runs are required: the
# `wrapper` task writes gradlew, gradlew.bat and gradle-wrapper.jar from the
# templates of the Gradle that runs it, so the first run (the old distribution)
# and the second run (the new one, downloaded because the properties already
# point at it) have to happen in order. See
# .agents/skills/gradle-wrapper-upgrade.
#
# Version, distribution type and checksum are passed explicitly so the task
# regenerates exactly the distribution the properties name, instead of following
# whichever Gradle happens to be running, and so an upgrade can never switch the
# build between -bin and -all.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

properties=gradle/wrapper/gradle-wrapper.properties

url=$(sed -n 's/^distributionUrl=//p' "$properties" | sed 's/\\:/:/g')
version=$(printf '%s\n' "$url" | sed -E 's#.*/gradle-(.+)-(bin|all)\.zip$#\1#')
distribution_type=$(printf '%s\n' "$url" | sed -E 's#.*/gradle-(.+)-(bin|all)\.zip$#\2#')

if [ -z "$version" ] || [ -z "$distribution_type" ] || [ "$version" = "$url" ]; then
  echo "Cannot read the Gradle version and distribution type from $properties" >&2
  echo "distributionUrl is: $url" >&2
  exit 1
fi

checksum=$(curl -fsSL "${url}.sha256")
if [ -z "$checksum" ]; then
  echo "Cannot fetch ${url}.sha256" >&2
  exit 1
fi

echo "Regenerating the wrapper for gradle-${version} (${distribution_type}), sha256 ${checksum}"

for run in 1 2; do
  echo "wrapper run ${run}/2"
  ./gradlew wrapper \
    --gradle-version="$version" \
    --distribution-type="$distribution_type" \
    --gradle-distribution-sha256-sum="$checksum" \
    --console=plain
done

# A silently dropped checksum would disable verification of the download.
grep -q "^distributionUrl=.*gradle-${version}-${distribution_type}\.zip\$" "$properties"
grep -qx "distributionSha256Sum=${checksum}" "$properties"
