#!/usr/bin/env bash
#
# Run the Serena MCP server for structural code navigation.
#
# Serena is an MCP server that exposes symbol-level tools (get_symbols_overview,
# find_symbol, find_referencing_symbols, replace_symbol_body, ...) on top of an
# LSP backend. Here the backend is kmp-lsp, so no IDE and no JVM is required.
#
# The project is detected from the working directory, which makes the server
# work in worktrees as well as in the main checkout. Configuration lives in
# .serena/project.yml.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

command -v uvx >/dev/null 2>&1 || {
    echo "uvx is required; install uv from https://astral.sh/uv" >&2
    exit 1
}

# kmp-lsp is resolved from PATH by .serena/project.yml; cargo installs it here,
# which is not on PATH for every process that starts this server.
if [[ -d "$HOME/.cargo/bin" ]]; then
    PATH="$HOME/.cargo/bin:$PATH"
    export PATH
fi

# The local adapter registers the `kmp_lsp` language server (kmp-lsp also covers
# Java and Swift) next to Serena.
exec uvx --from serena-agent --with "$repo_root/tools/serena-kmp-lsp" serena start-mcp-server \
    --context ide \
    --project-from-cwd \
    --open-web-dashboard False
