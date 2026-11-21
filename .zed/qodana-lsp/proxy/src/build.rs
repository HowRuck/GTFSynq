//! Builds JetBrains/qodana-lsp from source into `<cache>/src`.
//!
//! The release has no binaries. Its pinned Gradle wrapper (8.2) cannot run on newer JVMs, so the
//! build uses a JDK 11-19: the one given in the `build_java_home` setting, or a Temurin 17 that is
//! downloaded into the cache.

use std::fs::{self, File, TryLockError};
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

use anyhow::{anyhow, bail, Context, Result};
use flate2::read::GzDecoder;

use crate::log;

const TAG: &str = "v1.1.7";
const REPO: &str = "https://github.com/JetBrains/qodana-lsp";
const DOWNLOAD_JDK: u32 = 17;
const MIN_JDK: u32 = 11;
const MAX_JDK: u32 = 19;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(30);
/// Generous: the JDK archive is about 190 MB.
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// Builds the server in this process. All output goes to stderr (Zed's language server log):
/// stdout is the LSP channel and stdin belongs to the client, so the child processes get neither.
///
/// Only one build runs at a time. The lock is released by the OS when the process dies, so a
/// killed build never leaves a stale lock.
pub fn run(cache: &Path, java_home: Option<&str>) -> Result<()> {
    fs::create_dir_all(cache).with_context(|| format!("failed to create {}", cache.display()))?;
    let lock = File::create(cache.join("build.lock")).context("failed to create the build lock")?;
    match lock.try_lock() {
        Ok(()) => {}
        Err(TryLockError::WouldBlock) => bail!("another build is already running"),
        Err(TryLockError::Error(error)) => return Err(error).context("failed to lock the build"),
    }

    let src = cache.join("src");
    if !src.exists() {
        log(format!("cloning {REPO} at {TAG}"));
        let tmp = cache.join("src.tmp");
        remove_dir_if_exists(&tmp)?;
        run_command(
            Command::new("git")
                .args(["clone", "--depth", "1", "--branch", TAG, REPO])
                .arg(&tmp),
            "git clone",
        )?;
        fs::rename(&tmp, &src).context("failed to move the clone into place")?;
    }

    let jdk = match java_home {
        Some(setting) => validate_jdk(setting)?,
        None => download_jdk(cache)?,
    };

    log(format!("building with JAVA_HOME={}", jdk.display()));
    run_command(
        Command::new("./gradlew")
            .args(["--no-daemon", "shadowJar"])
            .current_dir(&src)
            .env("JAVA_HOME", &jdk),
        "gradle build",
    )?;
    log("build finished");
    Ok(())
}

fn run_command(command: &mut Command, what: &str) -> Result<()> {
    let status = command
        .stdin(Stdio::null())
        .stdout(io::stderr())
        .status()
        .with_context(|| format!("failed to start {what}"))?;
    if !status.success() {
        bail!("{what} failed ({status})");
    }
    Ok(())
}

fn remove_dir_if_exists(path: &Path) -> Result<()> {
    if path.exists() {
        fs::remove_dir_all(path).with_context(|| format!("failed to remove {}", path.display()))?;
    }
    Ok(())
}

// --- JDK ----------------------------------------------------------------------------------

/// Expands a leading `~`, and turns a path to `bin` or `bin/java` into the JDK directory.
fn normalize_jdk_home(input: &str, home: Option<&str>) -> PathBuf {
    let expanded = home
        .and_then(|home| match input {
            "~" => Some(PathBuf::from(home)),
            _ => input
                .strip_prefix("~/")
                .map(|rest| Path::new(home).join(rest)),
        })
        .unwrap_or_else(|| PathBuf::from(input));
    let jdk = if expanded.ends_with("bin/java") {
        expanded.ancestors().nth(2)
    } else if expanded.ends_with("bin") {
        expanded.parent()
    } else {
        None
    };
    jdk.map_or(expanded.clone(), Path::to_path_buf)
}

/// Reads the major version from the first line of `java -version`, e.g. `openjdk version "17.0.1"`
/// or the old `java version "1.8.0_392"`.
fn parse_java_major(output: &str) -> Option<u32> {
    let version = output.lines().next()?.split('"').nth(1)?;
    let mut parts = version.split(['.', '-', '+', '_']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

/// macOS JDK bundles keep the home under `Contents/Home`.
fn jdk_home_dir(jdk: PathBuf) -> PathBuf {
    let bundled = jdk.join("Contents/Home");
    if bundled.exists() {
        bundled
    } else {
        jdk
    }
}

fn validate_jdk(setting: &str) -> Result<PathBuf> {
    let jdk = jdk_home_dir(normalize_jdk_home(
        setting,
        std::env::var("HOME").ok().as_deref(),
    ));
    let java = jdk.join("bin/java");
    if !java.is_file() {
        bail!(
            "build_java_home must be a JDK directory containing bin/java, got {}",
            jdk.display()
        );
    }
    let output = Command::new(&java)
        .arg("-version")
        .output()
        .with_context(|| format!("failed to run {}", java.display()))?;
    let major = parse_java_major(&String::from_utf8_lossy(&output.stderr));
    match major {
        Some(major) if (MIN_JDK..=MAX_JDK).contains(&major) => Ok(jdk),
        _ => bail!(
            "build_java_home must be a JDK {MIN_JDK}-{MAX_JDK} (found {}): {}. Remove the setting \
             to download a Temurin {DOWNLOAD_JDK} automatically.",
            major.map_or_else(|| "unknown".to_owned(), |m| m.to_string()),
            jdk.display()
        ),
    }
}

/// Adoptium's os and architecture names for this machine.
fn adoptium_platform() -> Result<(&'static str, &'static str)> {
    let os = match std::env::consts::OS {
        "linux" => "linux",
        "macos" => "mac",
        other => {
            bail!("no JDK download for {other}; set build_java_home to a JDK {MIN_JDK}-{MAX_JDK}")
        }
    };
    let arch = match std::env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "aarch64",
        other => {
            bail!("no JDK download for {other}; set build_java_home to a JDK {MIN_JDK}-{MAX_JDK}")
        }
    };
    Ok((os, arch))
}

/// A stalled connection must not hang the first-run build forever.
fn download_agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_connect(Some(CONNECT_TIMEOUT))
        .timeout_global(Some(DOWNLOAD_TIMEOUT))
        .build()
        .into()
}

fn download_jdk(cache: &Path) -> Result<PathBuf> {
    let dest = cache.join(format!("jdk-{DOWNLOAD_JDK}"));
    if dest.exists() {
        return Ok(jdk_home_dir(dest));
    }
    let (os, arch) = adoptium_platform()?;
    // The API redirects to a GitHub release asset.
    let url = format!(
        "https://api.adoptium.net/v3/binary/latest/{DOWNLOAD_JDK}/ga/{os}/{arch}/jdk/hotspot/normal/eclipse"
    );
    log(format!("downloading Temurin {DOWNLOAD_JDK}"));
    let mut response = download_agent()
        .get(&url)
        .header("User-Agent", "qodana-lsp-zed-extension")
        .call()
        .with_context(|| format!("failed to download {url}"))?;

    let tmp = cache.join("jdk-tmp");
    remove_dir_if_exists(&tmp)?;
    unpack_single_top_dir(response.body_mut().as_reader(), &tmp, &dest)
        .context("failed to unpack the JDK")?;
    remove_dir_if_exists(&tmp)?;
    Ok(jdk_home_dir(dest))
}

/// Unpacks a `.tar.gz` that contains one top-level directory into `scratch`, then moves that
/// directory to `dest`. `tar` refuses entries that would escape `scratch`.
fn unpack_single_top_dir(reader: impl Read, scratch: &Path, dest: &Path) -> Result<()> {
    tar::Archive::new(GzDecoder::new(reader)).unpack(scratch)?;
    let top = fs::read_dir(scratch)?
        .next()
        .ok_or_else(|| anyhow!("the archive is empty"))??
        .path();
    fs::rename(top, dest)?;
    Ok(())
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn expands_the_home_directory() {
        assert_eq!(
            normalize_jdk_home("~/jdks/17", Some("/home/me")),
            PathBuf::from("/home/me/jdks/17")
        );
        assert_eq!(
            normalize_jdk_home("~", Some("/home/me")),
            PathBuf::from("/home/me")
        );
        assert_eq!(
            normalize_jdk_home("/opt/jdk", None),
            PathBuf::from("/opt/jdk")
        );
    }

    #[test]
    fn accepts_a_path_to_bin_or_the_java_binary() {
        assert_eq!(
            normalize_jdk_home("/opt/jdk/bin/java", None),
            PathBuf::from("/opt/jdk")
        );
        assert_eq!(
            normalize_jdk_home("/opt/jdk/bin", None),
            PathBuf::from("/opt/jdk")
        );
    }

    #[test]
    fn reads_the_java_major_version() {
        assert_eq!(
            parse_java_major("openjdk version \"17.0.21\" 2026-04-21\n"),
            Some(17)
        );
        assert_eq!(parse_java_major("java version \"1.8.0_392\"\n"), Some(8));
        assert_eq!(
            parse_java_major("openjdk version \"27\" 2026-09-15\n"),
            Some(27)
        );
        assert_eq!(parse_java_major("openjdk version \"26-ea\"\n"), Some(26));
        assert_eq!(parse_java_major("not java"), None);
    }

    #[test]
    fn unpacks_the_top_level_directory_into_place() {
        let dir = std::env::temp_dir().join(format!("qodana-build-test-{}", std::process::id()));
        let mut archive = Vec::new();
        {
            let encoder = flate2::write::GzEncoder::new(&mut archive, flate2::Compression::fast());
            let mut builder = tar::Builder::new(encoder);
            let mut header = tar::Header::new_gnu();
            header.set_size(2);
            header.set_mode(0o755);
            header.set_cksum();
            builder
                .append_data(&mut header, "jdk-17.0.1+12/bin/java", &b"hi"[..])
                .unwrap();
            builder.into_inner().unwrap().finish().unwrap();
        }

        let dest = dir.join("jdk-17");
        unpack_single_top_dir(archive.as_slice(), &dir.join("tmp"), &dest).unwrap();

        assert_eq!(fs::read(dest.join("bin/java")).unwrap(), b"hi");
        fs::remove_dir_all(dir).unwrap();
    }
}
