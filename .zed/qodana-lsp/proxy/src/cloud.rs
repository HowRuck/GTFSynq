//! Keeps a local SARIF file in sync with the latest report of a Qodana Cloud project.
//!
//! The calls mirror what JetBrains' VS Code extension does:
//!
//! 1. `GET {site}/api/versions` -> the backend URL for API v1.
//! 2. `GET {api}/projects/{id}/timeline?...&limit=1` -> the latest report id.
//! 3. `GET {api}/reports/{reportId}/files?paths=qodana.sarif.json` -> a download URL.
//! 4. `GET {url}` (no credentials) -> the SARIF file.
//!
//! Steps 2 and 3 send the token, when there is one, as `Authorization: Bearer`. Public projects need
//! none (verified against the live service). The VS Code extension sends an OAuth access token
//! there; whether a Qodana project token (`QODANA_TOKEN`) is accepted for private projects is
//! unverified.

use std::fs::{self, File};
use std::io;
use std::path::Path;
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use serde_json::Value;

use crate::log;

pub const DEFAULT_SITE: &str = "https://qodana.cloud";
pub const DEFAULT_INTERVAL: Duration = Duration::from_secs(300);
pub const MIN_INTERVAL: Duration = Duration::from_secs(60);
const API_MAJOR_VERSION: &str = "1";
const TIMELINE_STATES: &str = "UPLOADED,PROCESSED,PINNED";
const TIMEOUT: Duration = Duration::from_secs(60);

/// Deliberately not `Debug`: it holds the token.
pub struct CloudConfig {
    pub site: String,
    pub project_id: String,
    /// Needed for private projects; public ones can be read anonymously.
    pub token: Option<String>,
    pub interval: Duration,
    /// Id of the report now on disk, for the client notification.
    pub report_id: Mutex<Option<String>>,
}

// --- response parsing ----------------------------------------------------------------------

/// Picks the backend URL for API v1 out of the `api/versions` response.
fn parse_backend_url(versions: &Value) -> Option<String> {
    versions
        .pointer("/api/versions")?
        .as_array()?
        .iter()
        .find(|v| {
            v.get("version")
                .and_then(Value::as_str)
                .is_some_and(|version| version.split('.').next() == Some(API_MAJOR_VERSION))
        })?
        .get("url")?
        .as_str()
        .map(|url| url.trim_end_matches('/').to_owned())
}

/// Picks the newest report id out of a timeline page.
fn parse_report_id(timeline: &Value) -> Option<String> {
    timeline
        .pointer("/items/0/reportId")?
        .as_str()
        .map(str::to_owned)
}

/// Picks the SARIF download URL out of a report's file listing.
fn parse_file_url(files: &Value) -> Option<String> {
    files
        .pointer("/files")?
        .as_array()?
        .iter()
        .find(|f| f.get("file").and_then(Value::as_str) == Some("qodana.sarif.json"))?
        .get("url")?
        .as_str()
        .map(str::to_owned)
}

// --- HTTP ----------------------------------------------------------------------------------

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(TIMEOUT))
        .build()
        .into()
}

fn get_json(agent: &ureq::Agent, url: &str, token: Option<&str>) -> Result<Value> {
    let mut request = agent
        .get(url)
        .header("User-Agent", "qodana-lsp-zed-extension");
    if let Some(token) = token {
        request = request.header("Authorization", &format!("Bearer {token}"));
    }
    request
        .call()
        .with_context(|| format!("request to {url} failed"))?
        .body_mut()
        .read_json()
        .with_context(|| format!("{url} did not return JSON"))
}

/// Downloads `url` to `dest` through a temporary file, so readers never see a partial report.
fn download(agent: &ureq::Agent, url: &str, dest: &Path) -> Result<()> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)
            .with_context(|| format!("failed to create {}", parent.display()))?;
    }
    let tmp = dest.with_extension("json.part");
    let mut response = agent
        .get(url)
        .header("User-Agent", "qodana-lsp-zed-extension")
        .call()
        .context("report download failed")?; // the URL is pre-signed: no token is sent
    let mut file =
        File::create(&tmp).with_context(|| format!("failed to create {}", tmp.display()))?;
    io::copy(&mut response.body_mut().as_reader(), &mut file)
        .context("failed to write the report")?;
    fs::rename(&tmp, dest).with_context(|| format!("failed to move report to {}", dest.display()))
}

/// Downloads the latest report unless `last_report_id` is already current. Returns the id of the
/// report now on disk.
fn sync_once(
    agent: &ureq::Agent,
    config: &CloudConfig,
    dest: &Path,
    last_report_id: Option<&str>,
) -> Result<String> {
    let versions = get_json(agent, &format!("{}/api/versions", config.site), None)?;
    let api = parse_backend_url(&versions)
        .ok_or_else(|| anyhow!("{} offers no API v{API_MAJOR_VERSION}", config.site))?;

    let timeline_url = format!(
        "{api}/projects/{}/timeline?offset=0&limit=1&states={TIMELINE_STATES}",
        config.project_id
    );
    let timeline = get_json(agent, &timeline_url, config.token.as_deref())?;
    let report_id = parse_report_id(&timeline)
        .ok_or_else(|| anyhow!("project {} has no reports", config.project_id))?;
    if last_report_id == Some(report_id.as_str()) && dest.exists() {
        return Ok(report_id);
    }

    let files_url = format!("{api}/reports/{report_id}/files?paths=qodana.sarif.json");
    let files = get_json(agent, &files_url, config.token.as_deref())?;
    let url = parse_file_url(&files)
        .ok_or_else(|| anyhow!("report {report_id} has no qodana.sarif.json"))?;
    download(agent, &url, dest)?;
    *crate::lock(&config.report_id) = Some(report_id.clone());
    log(format!("downloaded cloud report {report_id}"));
    Ok(report_id)
}

/// Syncs forever; failures are logged and retried on the next interval.
pub fn sync_loop(config: &CloudConfig, dest: &Path) {
    let agent = agent();
    let mut last_report_id: Option<String> = None;
    loop {
        match sync_once(&agent, config, dest, last_report_id.as_deref()) {
            Ok(id) => last_report_id = Some(id),
            Err(error) => log(format!("cloud sync failed: {error:#}")),
        }
        thread::sleep(config.interval);
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use serde_json::json;

    /// `(path, authorization header)` of every request the mock received.
    type SeenRequests = std::sync::Arc<std::sync::Mutex<Vec<(String, String)>>>;

    #[test]
    fn picks_the_v1_backend_and_trims_the_slash() {
        let versions = json!({"api": {"versions": [
            {"version": "2.0", "url": "https://api.example/v2"},
            {"version": "1.37", "url": "https://api.example/v1/"},
        ]}});
        assert_eq!(
            parse_backend_url(&versions).as_deref(),
            Some("https://api.example/v1")
        );
    }

    #[test]
    fn no_v1_backend_gives_none() {
        let versions = json!({"api": {"versions": [{"version": "2.0", "url": "x"}]}});
        assert_eq!(parse_backend_url(&versions), None);
        assert_eq!(parse_backend_url(&json!({})), None);
    }

    #[test]
    fn reads_the_newest_report_id() {
        let timeline = json!({"items": [{"reportId": "r2"}, {"reportId": "r1"}], "next": 1});
        assert_eq!(parse_report_id(&timeline).as_deref(), Some("r2"));
        assert_eq!(parse_report_id(&json!({"items": []})), None);
    }

    #[test]
    fn finds_the_sarif_file_url() {
        let files = json!({"files": [
            {"file": "other.json", "url": "https://x/other"},
            {"file": "qodana.sarif.json", "url": "https://x/sarif"},
        ]});
        assert_eq!(parse_file_url(&files).as_deref(), Some("https://x/sarif"));
        assert_eq!(parse_file_url(&json!({"files": []})), None);
    }

    /// Hits the real service, so it only runs on request:
    /// `cargo test -p qodana-lsp-proxy -- --ignored`.
    #[test]
    #[ignore = "needs network access to qodana.cloud"]
    fn downloads_a_public_report_without_a_token() {
        let dir = std::env::temp_dir().join(format!("qodana-live-test-{}", std::process::id()));
        let dest = dir.join("qodana.sarif.json");
        let config = CloudConfig {
            site: DEFAULT_SITE.into(),
            project_id: "Rvqv1".into(),
            token: None,
            interval: MIN_INTERVAL,
            report_id: Mutex::default(),
        };

        let id = sync_once(&agent(), &config, &dest, None).unwrap();

        assert!(!id.is_empty());
        let sarif: Value = serde_json::from_str(&fs::read_to_string(&dest).unwrap()).unwrap();
        assert!(sarif.get("runs").is_some_and(Value::is_array));
        fs::remove_dir_all(dir).unwrap();
    }

    /// Serves the four Qodana Cloud calls on localhost and records `(path, authorization)`.
    fn mock_cloud() -> (String, SeenRequests) {
        use std::io::{BufRead, BufReader, Write};
        use std::net::TcpListener;
        use std::sync::{Arc, Mutex};

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let seen = Arc::new(Mutex::new(Vec::new()));
        let (log, base_for_thread) = (Arc::clone(&seen), base.clone());
        thread::spawn(move || {
            for stream in listener.incoming() {
                let mut stream = stream.unwrap();
                let mut reader = BufReader::new(stream.try_clone().unwrap());
                let mut request_line = String::new();
                reader.read_line(&mut request_line).unwrap();
                let path = request_line.split_whitespace().nth(1).unwrap().to_owned();
                let mut authorization = String::new();
                loop {
                    let mut header = String::new();
                    reader.read_line(&mut header).unwrap();
                    if header.trim().is_empty() {
                        break;
                    }
                    if let Some((name, value)) = header.split_once(':') {
                        if name.eq_ignore_ascii_case("authorization") {
                            authorization = value.trim().to_owned();
                        }
                    }
                }
                log.lock().unwrap().push((path.clone(), authorization));
                let body = if path == "/api/versions" {
                    json!({"api": {"versions": [{"version": "1.40", "url": format!("{base_for_thread}/v1")}]}}).to_string()
                } else if path.starts_with("/v1/projects/p1/timeline") {
                    json!({"items": [{"reportId": "r1"}]}).to_string()
                } else if path.starts_with("/v1/reports/r1/files") {
                    json!({"files": [{"file": "qodana.sarif.json", "url": format!("{base_for_thread}/dl/sarif")}]}).to_string()
                } else if path == "/dl/sarif" {
                    r#"{"runs":[]}"#.to_owned()
                } else {
                    String::new()
                };
                let status = if body.is_empty() {
                    "404 Not Found"
                } else {
                    "200 OK"
                };
                write!(
                    stream,
                    "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            }
        });
        (base, seen)
    }

    #[test]
    fn syncs_the_latest_report_once_and_keeps_the_token_off_the_download() {
        let (site, seen) = mock_cloud();
        let dir = std::env::temp_dir().join(format!("qodana-cloud-test-{}", std::process::id()));
        let dest = dir.join("nested/qodana.sarif.json");
        let config = CloudConfig {
            site,
            project_id: "p1".into(),
            token: Some("secret-token".into()),
            interval: MIN_INTERVAL,
            report_id: Mutex::default(),
        };
        let agent = agent();

        let id = sync_once(&agent, &config, &dest, None).unwrap();
        assert_eq!(id, "r1");
        assert_eq!(fs::read_to_string(&dest).unwrap(), r#"{"runs":[]}"#);
        assert!(!dest.with_extension("json.part").exists());

        let calls = seen.lock().unwrap().clone();
        let auth = |prefix: &str| {
            calls
                .iter()
                .find(|(path, _)| path.starts_with(prefix))
                .map(|(_, a)| a.clone())
                .unwrap()
        };
        assert_eq!(auth("/api/versions"), "");
        assert_eq!(auth("/v1/projects/p1/timeline"), "Bearer secret-token");
        assert_eq!(auth("/v1/reports/r1/files"), "Bearer secret-token");
        assert_eq!(auth("/dl/sarif"), "");

        // Same report id and file already on disk: no second download.
        let downloads_before = calls.iter().filter(|(p, _)| p == "/dl/sarif").count();
        sync_once(&agent, &config, &dest, Some("r1")).unwrap();
        let downloads_after = seen
            .lock()
            .unwrap()
            .iter()
            .filter(|(p, _)| p == "/dl/sarif")
            .count();
        assert_eq!(downloads_before, downloads_after);

        fs::remove_dir_all(dir).unwrap();
    }
}
