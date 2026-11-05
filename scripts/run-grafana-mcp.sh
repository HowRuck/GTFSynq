#!/usr/bin/env bash
#
# Run the Grafana MCP server for local dashboard validation.
#
# The server is read-only (--disable-write) and uses the Grafana admin
# credentials from the repository's .env, the same ones the monitoring Compose
# stack uses. It is intended for the dashboards under monitoring/grafana; keep
# the JSON files as the source of truth instead of writing through the MCP.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

: "${GF_SECURITY_ADMIN_USER:?set GF_SECURITY_ADMIN_USER in .env}"
: "${GF_SECURITY_ADMIN_PASSWORD:?set GF_SECURITY_ADMIN_PASSWORD in .env}"

export GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
export GRAFANA_USERNAME="${GRAFANA_USERNAME:-$GF_SECURITY_ADMIN_USER}"
export GRAFANA_PASSWORD="${GRAFANA_PASSWORD:-$GF_SECURITY_ADMIN_PASSWORD}"

exec uvx mcp-grafana \
    --disable-write \
    --enabled-tools search,dashboard,datasource,prometheus,provisioning,navigation,annotations,runpanelquery \
    --usage-stats=disabled
