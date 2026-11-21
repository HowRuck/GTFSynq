//! Stdio proxy between Zed and JetBrains/qodana-lsp.
//!
//! * Starts the built server, or builds it from source in a detached process when it is missing.
//! * After the client's `initialized` notification, sends the custom `setSourceLocation` and
//!   `setSarifFile` requests the server needs before it reports anything, and re-sends
//!   `setSarifFile` whenever the SARIF file changes.
//! * Drops the responses to its own requests and removes `diagnosticProvider` from the server
//!   capabilities: the server answers pull diagnostics with an empty report, which would hide
//!   the diagnostics it pushes.

mod build;
mod cloud;

use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{self, Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{anyhow, bail, Context, Result};
use serde_json::{json, Value};

use crate::cloud::CloudConfig;

const MAIN_CLASS: &str = "org.jetbrains.qodana.SarifLanguageServerLauncher";
const PROXY_ID_PREFIX: &str = "qodana-proxy-";
const POLL: Duration = Duration::from_secs(2);
const EXIT_GRACE: Duration = Duration::from_secs(5);

struct Args {
    root: PathBuf,
    cache_dir: PathBuf,
    sarif: PathBuf,
    build_java_home: Option<String>,
    show_baseline_issues: bool,
    cloud: Option<Arc<CloudConfig>>,
}

fn log(message: impl std::fmt::Display) {
    eprintln!("[qodana-lsp] {message}");
}

/// Locks `mutex`, carrying on with the inner value if another thread panicked while holding it:
/// the guarded data (a pipe or an id) stays valid.
fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn parse_args() -> Result<Args> {
    let (mut root, mut cache_dir, mut build_java_home) = (None, None, None);
    let mut sarif = None;
    let mut show_baseline_issues = true;
    let mut cloud_project = None;
    let mut cloud_site = cloud::DEFAULT_SITE.to_owned();
    let mut cloud_interval = cloud::DEFAULT_INTERVAL;
    let mut args = std::env::args().skip(1);
    while let Some(flag) = args.next() {
        let mut value = || {
            args.next()
                .ok_or_else(|| anyhow!("missing value for {flag}"))
        };
        match flag.as_str() {
            "--root" => root = Some(PathBuf::from(value()?)),
            "--cache-dir" => cache_dir = Some(PathBuf::from(value()?)),
            "--sarif" => sarif = Some(PathBuf::from(value()?)),
            "--build-java-home" => build_java_home = Some(value()?),
            "--only-new-issues" => show_baseline_issues = false,
            "--cloud-project" => cloud_project = Some(value()?),
            "--cloud-url" => cloud_site = value()?.trim_end_matches('/').to_owned(),
            "--cloud-interval-secs" => {
                let secs = value()?
                    .parse()
                    .context("--cloud-interval-secs must be a number")?;
                cloud_interval = Duration::from_secs(secs).max(cloud::MIN_INTERVAL);
            }
            other => bail!("unknown argument {other}"),
        }
    }
    let root = root.context("--root is required")?;

    // The optional token (private projects only) comes from the environment so it never lands in
    // settings files or argv.
    let cloud = cloud_project.map(|project_id| {
        Arc::new(CloudConfig {
            site: cloud_site,
            project_id,
            token: std::env::var("QODANA_TOKEN")
                .ok()
                .filter(|token| !token.is_empty()),
            interval: cloud_interval,
        })
    });
    // Cloud reports get their own file so they never overwrite a locally produced report.
    let default_sarif = if cloud.is_some() {
        ".qodana/cloud/qodana.sarif.json"
    } else {
        ".qodana/results/qodana.sarif.json"
    };
    Ok(Args {
        sarif: root.join(sarif.unwrap_or_else(|| PathBuf::from(default_sarif))), // absolute replaces
        root,
        cache_dir: cache_dir.context("--cache-dir is required")?,
        build_java_home,
        show_baseline_issues,
        cloud,
    })
}

// --- build ---------------------------------------------------------------------------------

fn find_jar(cache: &Path) -> Option<PathBuf> {
    fs::read_dir(cache.join("src/vscode/qodana/lib"))
        .ok()?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|p| {
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            name.starts_with("sarif-lsp-") && name.ends_with(".jar")
        })
        .max()
}

// --- LSP framing ---------------------------------------------------------------------------

fn read_message(reader: &mut impl BufRead) -> io::Result<Option<Vec<u8>>> {
    let mut length = None;
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            return Ok(None);
        }
        let line = line.trim();
        if line.is_empty() {
            break;
        }
        if let Some((name, value)) = line.split_once(':') {
            if name.eq_ignore_ascii_case("content-length") {
                length = value.trim().parse::<usize>().ok();
            }
        }
    }
    let Some(length) = length else {
        return Ok(None);
    };
    let mut body = vec![0; length];
    reader.read_exact(&mut body)?;
    Ok(Some(body))
}

fn write_message(writer: &Mutex<impl Write>, body: &[u8]) -> io::Result<()> {
    let mut writer = lock(writer);
    write!(writer, "Content-Length: {}\r\n\r\n", body.len())?;
    writer.write_all(body)?;
    writer.flush()
}

// --- injected requests ---------------------------------------------------------------------

/// Tells the client (a `window/showMessage` notification, which Zed shows as a toast) that a
/// report was loaded.
fn notify_client(client: &Mutex<io::Stdout>, message: &str) -> io::Result<()> {
    let notification = json!({
        "jsonrpc": "2.0",
        "method": "window/showMessage",
        "params": { "type": 3, "message": message },
    });
    write_message(client, notification.to_string().as_bytes())
}

fn watch_sarif(server: &Mutex<ChildStdin>, client: &Mutex<io::Stdout>, args: &Args) {
    let mut counter = 0;
    let mut request = |method: &str, params: Value| {
        counter += 1;
        let message = json!({
            "jsonrpc": "2.0",
            "id": format!("{PROXY_ID_PREFIX}{counter}"),
            "method": method,
            "params": params,
        });
        write_message(server, message.to_string().as_bytes())
    };

    if request("setSourceLocation", json!({ "path": args.root })).is_err() {
        return;
    }
    let (mut previous, mut sent) = (None, None);
    let mut loads = 0;
    loop {
        let current = fs::metadata(&args.sarif).and_then(|m| m.modified()).ok();
        // Load only once the file has stopped changing between two polls.
        if current.is_some() && current == previous && current != sent {
            sent = current;
            log(format!("loading {}", args.sarif.display()));
            let params = json!({
                "path": args.sarif,
                "showBaselineIssues": args.show_baseline_issues,
            });
            if request("setSarifFile", params).is_err() {
                return;
            }
            loads += 1;
            let verb = if loads == 1 { "loaded" } else { "reloaded" };
            let name = args.sarif.file_name().map_or_else(
                || args.sarif.display().to_string(),
                |n| n.to_string_lossy().into_owned(),
            );
            if notify_client(client, &format!("Qodana: report {verb} ({name})")).is_err() {
                return;
            }
        }
        previous = current;
        thread::sleep(POLL);
    }
}

// --- server -> client ----------------------------------------------------------------------

/// What to do with one message from the server.
#[derive(Debug, PartialEq)]
enum Forward {
    /// Send these bytes to the client.
    Send(Vec<u8>),
    /// A response to one of the proxy's own requests.
    Drop,
}

fn filter_server_message(body: Vec<u8>, initialize_id: Option<&Value>) -> Forward {
    let Ok(mut message) = serde_json::from_slice::<Value>(&body) else {
        return Forward::Send(body);
    };
    if message.get("method").is_some() {
        return Forward::Send(body);
    }
    let id = message.get("id").cloned().unwrap_or(Value::Null);
    if id.as_str().is_some_and(|s| s.starts_with(PROXY_ID_PREFIX)) {
        if let Some(error) = message.get("error") {
            log(format!("server rejected a request: {error}"));
        }
        return Forward::Drop;
    }
    if initialize_id == Some(&id) {
        if let Some(capabilities) = message
            .pointer_mut("/result/capabilities")
            .and_then(Value::as_object_mut)
        {
            capabilities.remove("diagnosticProvider");
        }
        return Forward::Send(message.to_string().into_bytes());
    }
    Forward::Send(body)
}

/// Forwards the server's messages to the client until its stdout closes, then ends the process
/// with the server's exit status. Exiting from this thread is deliberate: the main thread is
/// blocked reading the client's stdin and would never notice a dead server.
fn forward_server_messages(
    server_out: impl Read,
    client_out: &Mutex<io::Stdout>,
    initialize_id: &Mutex<Option<Value>>,
    server: &Mutex<Child>,
) -> ! {
    let mut reader = BufReader::new(server_out);
    while let Ok(Some(body)) = read_message(&mut reader) {
        let forward = filter_server_message(body, lock(initialize_id).as_ref());
        if let Forward::Send(body) = forward {
            if write_message(client_out, &body).is_err() {
                // The client is gone: stop the server with it.
                let _ = wait_or_kill(server, Duration::ZERO);
                process::exit(1);
            }
        }
    }
    process::exit(wait_or_kill(server, EXIT_GRACE).unwrap_or(1));
}

// --- server process ------------------------------------------------------------------------

struct Server {
    child: Arc<Mutex<Child>>,
    stdin: Arc<Mutex<ChildStdin>>,
    stdout: ChildStdout,
}

fn spawn_server(cache_dir: &Path, jar: &Path) -> Result<Server> {
    let run_dir = cache_dir.join("run"); // the server logs to ./logs
    fs::create_dir_all(&run_dir)
        .with_context(|| format!("failed to create {}", run_dir.display()))?;
    let mut child = Command::new("java")
        .arg("-cp")
        .arg(jar)
        .arg(MAIN_CLASS)
        .current_dir(run_dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .context("failed to start the server with `java`")?;
    let stdin = child.stdin.take().context("server stdin is not piped")?;
    let stdout = child.stdout.take().context("server stdout is not piped")?;
    Ok(Server {
        child: Arc::new(Mutex::new(child)),
        stdin: Arc::new(Mutex::new(stdin)),
        stdout,
    })
}

/// Waits for the server to exit and kills it once `grace` has passed. Returns its exit code.
fn wait_or_kill(server: &Mutex<Child>, grace: Duration) -> Result<i32> {
    let deadline = Instant::now() + grace;
    loop {
        let mut child = lock(server);
        if let Some(status) = child.try_wait().context("failed to poll the server")? {
            return Ok(status.code().unwrap_or(1));
        }
        if Instant::now() >= deadline {
            child.kill().context("failed to stop the server")?;
            let status = child.wait().context("failed to reap the server")?;
            return Ok(status.code().unwrap_or(1));
        }
        drop(child);
        thread::sleep(Duration::from_millis(100));
    }
}

// --- main ----------------------------------------------------------------------------------

fn build_on_first_run(args: &Args) -> Result<i32> {
    log("qodana-lsp is not built yet: building it from source (first run only)");
    build::run(&args.cache_dir, args.build_java_home.as_deref())?;
    log("restart the language server to start using it");
    Ok(0)
}

fn start_cloud_sync(args: &Args) {
    if let Some(config) = &args.cloud {
        let (config, dest) = (Arc::clone(config), args.sarif.clone());
        thread::spawn(move || cloud::sync_loop(&config, &dest));
    }
}

/// Passes the client's messages to the server until the client closes its end. Watches for the
/// `initialize` id (to rewrite the response) and starts the SARIF watcher after `initialized`.
fn forward_client_messages(
    args: &Arc<Args>,
    server_in: &Arc<Mutex<ChildStdin>>,
    client_out: &Arc<Mutex<io::Stdout>>,
    initialize_id: &Mutex<Option<Value>>,
) -> Result<()> {
    let mut stdin = BufReader::new(io::stdin());
    let mut watching = false;
    while let Some(body) = read_message(&mut stdin).context("failed to read from the client")? {
        let message = serde_json::from_slice::<Value>(&body).unwrap_or(Value::Null);
        match message.get("method").and_then(Value::as_str) {
            Some("initialize") => *lock(initialize_id) = message.get("id").cloned(),
            Some("initialized") if !watching => {
                watching = true;
                let (server_in, client_out, args) = (
                    Arc::clone(server_in),
                    Arc::clone(client_out),
                    Arc::clone(args),
                );
                thread::spawn(move || watch_sarif(&server_in, &client_out, &args));
            }
            _ => {}
        }
        write_message(server_in, &body).context("failed to write to the server")?;
    }
    Ok(())
}

fn run() -> Result<i32> {
    let args = Arc::new(parse_args()?);
    let Some(jar) = find_jar(&args.cache_dir) else {
        return build_on_first_run(&args);
    };

    let server = spawn_server(&args.cache_dir, &jar)?;
    start_cloud_sync(&args);

    let initialize_id = Arc::new(Mutex::new(None));
    let client_out = Arc::new(Mutex::new(io::stdout()));
    {
        let (initialize_id, child) = (Arc::clone(&initialize_id), Arc::clone(&server.child));
        let client_out = Arc::clone(&client_out);
        thread::spawn(move || {
            forward_server_messages(server.stdout, &client_out, &initialize_id, &child)
        });
    }
    forward_client_messages(&args, &server.stdin, &client_out, &initialize_id)?;

    // The client went away: give the server a moment to exit, then stop it.
    wait_or_kill(&server.child, EXIT_GRACE)?;
    Ok(0)
}

fn main() {
    match run() {
        Ok(code) => process::exit(code),
        Err(error) => {
            log(format!("{error:#}"));
            process::exit(1);
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn framed(body: &str) -> Vec<u8> {
        format!("Content-Length: {}\r\n\r\n{body}", body.len()).into_bytes()
    }

    #[test]
    fn reads_framed_messages_back_to_back() {
        let mut input = framed(r#"{"a":1}"#);
        input.extend(framed(r#"{"b":2}"#));
        let mut reader = BufReader::new(input.as_slice());
        assert_eq!(read_message(&mut reader).unwrap().unwrap(), br#"{"a":1}"#);
        assert_eq!(read_message(&mut reader).unwrap().unwrap(), br#"{"b":2}"#);
        assert!(read_message(&mut reader).unwrap().is_none());
    }

    #[test]
    fn write_message_round_trips() {
        let out = Mutex::new(Vec::new());
        write_message(&out, b"{}").unwrap();
        assert_eq!(out.into_inner().unwrap(), framed("{}"));
    }

    #[test]
    fn strips_diagnostic_provider_from_initialize_response() {
        let body = br#"{"jsonrpc":"2.0","id":1,"result":{"capabilities":{"textDocumentSync":2,"diagnosticProvider":{"interFileDependencies":false}}}}"#;
        let id = json!(1);
        let Forward::Send(out) = filter_server_message(body.to_vec(), Some(&id)) else {
            panic!("initialize response must be forwarded");
        };
        let out: Value = serde_json::from_slice(&out).unwrap();
        assert_eq!(
            out["result"]["capabilities"],
            json!({ "textDocumentSync": 2 })
        );
    }

    #[test]
    fn leaves_other_responses_with_the_same_shape_alone() {
        let body =
            br#"{"jsonrpc":"2.0","id":7,"result":{"capabilities":{"diagnosticProvider":{}}}}"#;
        let id = json!(1);
        assert_eq!(
            filter_server_message(body.to_vec(), Some(&id)),
            Forward::Send(body.to_vec())
        );
    }

    #[test]
    fn drops_responses_to_proxy_requests() {
        let body = br#"{"jsonrpc":"2.0","id":"qodana-proxy-1","result":null}"#;
        assert_eq!(filter_server_message(body.to_vec(), None), Forward::Drop);
    }

    #[test]
    fn forwards_notifications_untouched() {
        let body = br#"{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{}}"#;
        assert_eq!(
            filter_server_message(body.to_vec(), None),
            Forward::Send(body.to_vec())
        );
    }
}
