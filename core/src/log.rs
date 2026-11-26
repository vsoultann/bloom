//! Diagnostic log: failed API calls and other errors, kept in memory and appended
//! to `<data>/logs/bloom.log` (trimmed to ~512 KB) so it survives restarts.

use std::collections::VecDeque;
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;

static LOG: Mutex<VecDeque<String>> = Mutex::new(VecDeque::new());
static FILE: Mutex<Option<PathBuf>> = Mutex::new(None);

pub fn init(data_dir: &str) {
    let dir = PathBuf::from(data_dir).join("logs");
    let _ = std::fs::create_dir_all(&dir);
    let f = dir.join("bloom.log");
    if std::fs::metadata(&f).map(|m| m.len() > 512 * 1024).unwrap_or(false) {
        // keep the newest half
        if let Ok(s) = std::fs::read_to_string(&f) {
            let cut = s.len() / 2;
            let start = s[cut..].find('\n').map(|i| cut + i + 1).unwrap_or(cut);
            let _ = std::fs::write(&f, &s[start..]);
        }
    }
    *FILE.lock().unwrap_or_else(|p| p.into_inner()) = Some(f);
}

fn stamp() -> String {
    let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    format!("{t}")
}

pub fn write(kind: &str, text: &str) {
    let line = format!("{}\t{kind}\t{}", stamp(), text.replace('\n', " "));
    {
        let mut l = LOG.lock().unwrap_or_else(|p| p.into_inner());
        l.push_back(line.clone());
        while l.len() > 500 { l.pop_front(); }
    }
    if let Some(f) = FILE.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        // keep the file small: past 512 KB, drop the oldest half
        if std::fs::metadata(f).map(|m| m.len() > 512 * 1024).unwrap_or(false) {
            if let Ok(all) = std::fs::read_to_string(f) {
                let cut = all.len() / 2;
                let start = all[cut..].find('\n').map(|i| cut + i + 1).unwrap_or(cut);
                let _ = std::fs::write(f, &all[start..]);
            }
        }
        if let Ok(mut fh) = std::fs::OpenOptions::new().create(true).append(true).open(f) {
            let _ = writeln!(fh, "{line}");
        }
    }
}

pub fn api(path: &str, err: &str) {
    write("api", &format!("{path}: {err}"));
}

/// Everything in the log file (falls back to memory), newest last.
pub fn read() -> String {
    if let Some(f) = FILE.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
        if let Ok(s) = std::fs::read_to_string(f) { return s; }
    }
    LOG.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned().collect::<Vec<_>>().join("\n")
}

pub fn clear() {
    LOG.lock().unwrap_or_else(|p| p.into_inner()).clear();
    if let Some(f) = FILE.lock().unwrap_or_else(|p| p.into_inner()).as_ref() { let _ = std::fs::write(f, ""); }
}
