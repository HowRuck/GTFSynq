"""Serena language-server adapter for kmp-lsp.

kmp-lsp (https://github.com/Hessesian/kmp-lsp) speaks LSP over stdio for Kotlin,
Gradle scripts, Java and Swift. Serena ships a Kotlin adapter that launches it,
but Serena's built-in ``kotlin`` id only matches ``.kt``/``.kts`` files, so Java
sources would otherwise need the JVM-based Eclipse JDT-LS.

This package registers the same adapter under the ``kmp_lsp`` id with a matcher
that also covers Java and Swift. Install it next to Serena and list ``kmp_lsp`` in
``.serena/project.yml`` (``scripts/run-serena-mcp.sh`` does the installation).
"""

from pathlib import Path

from solidlsp.language_servers.kotlin_language_server import KotlinLanguageServer
from solidlsp.ls_config import ExternalLanguageServerId, FilenameMatcher, LanguageServerRegistry

_LANGUAGE_IDS = {
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".java": "java",
    ".swift": "swift",
}


class KmpLspLanguageServer(KotlinLanguageServer):
    """The Kotlin adapter, told which language each file is before it is opened."""

    def _get_language_id_for_file(self, relative_file_path: str) -> str:
        return _LANGUAGE_IDS.get(Path(relative_file_path).suffix.lower(), "kotlin")


def register() -> None:
    LanguageServerRegistry.get_instance().register(
        ExternalLanguageServerId(
            key="kmp_lsp",
            matcher=FilenameMatcher(".kt", ".kts", ".java", ".swift"),
            implementation=KmpLspLanguageServer,
        )
    )
