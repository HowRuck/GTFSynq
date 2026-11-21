//! Zed extension that runs JetBrains/qodana-lsp through `qodana-lsp-proxy`.
//!
//! The server only reports diagnostics after a client sends it the custom `setSourceLocation`
//! and `setSarifFile` requests, which Zed cannot send. The native proxy in `proxy/` sends them on
//! the extension's behalf and builds the server from source on first use. Wasm extensions cannot
//! run it themselves, so it has to be installed separately.

use zed_extension_api::{
    self as zed, serde_json::Value, settings::LspSettings, LanguageServerId, Result,
};

const PROXY_BINARY: &str = "qodana-lsp-proxy";

struct QodanaExtension;

impl zed::Extension for QodanaExtension {
    fn new() -> Self {
        Self
    }

    fn language_server_command(
        &mut self,
        server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        let lsp_settings = LspSettings::for_worktree(server_id.as_ref(), worktree)?;
        let settings = lsp_settings.settings.unwrap_or_default();

        let command = lsp_settings
            .binary
            .and_then(|binary| binary.path)
            .or_else(|| worktree.which(PROXY_BINARY))
            .ok_or_else(|| {
                format!(
                    "`{PROXY_BINARY}` not found: run `cargo install --path proxy` in the \
                     extension directory, or set `lsp.qodana.binary.path`"
                )
            })?;

        let work_dir = std::env::current_dir().map_err(|e| e.to_string())?;
        let mut args = vec![
            "--root".into(),
            worktree.root_path(),
            "--cache-dir".into(),
            work_dir.join("qodana-lsp").to_string_lossy().into_owned(),
        ];
        for (setting, flag) in [
            ("sarif_path", "--sarif"),
            ("build_java_home", "--build-java-home"),
            ("cloud_project_id", "--cloud-project"),
            ("cloud_url", "--cloud-url"),
        ] {
            if let Some(value) = settings.get(setting).and_then(Value::as_str) {
                args.extend([flag.into(), value.into()]);
            }
        }
        if let Some(seconds) = settings
            .get("cloud_refresh_seconds")
            .and_then(Value::as_u64)
        {
            args.extend(["--cloud-interval-secs".into(), seconds.to_string()]);
        }
        if settings
            .get("show_baseline_issues")
            .and_then(Value::as_bool)
            == Some(false)
        {
            args.push("--only-new-issues".into());
        }

        Ok(zed::Command {
            command,
            args,
            env: worktree.shell_env(),
        })
    }
}

zed::register_extension!(QodanaExtension);
