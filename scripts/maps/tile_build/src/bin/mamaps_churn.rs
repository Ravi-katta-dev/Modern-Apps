//! `mamaps_churn` — byte-level leaf churn between two `.mamaps` archives.
//!
//! Usage:
//!   mamaps_churn BEFORE.mamaps AFTER.mamaps
//!
//! Prints the overlay budget (see `tile_build::churn`): how many addressed
//! tiles changed/added/deleted by body bytes, the per-zoom split, the
//! per-sidecar-kind length moves, and a final `OVERLAY_BYTES=` line
//! `diff_mamaps.py --churn-budget-gb` gates on. Range-based throughout —
//! never holds either archive, only the ~24 B-per-body fingerprint tables.

use std::process::ExitCode;

use tile_build::churn::{FileReader, join, render, snapshot};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 || args[1].starts_with('-') {
        eprintln!("usage: mamaps_churn BEFORE.mamaps AFTER.mamaps");
        return ExitCode::from(2);
    }
    let (before, after) = (std::path::Path::new(&args[1]), std::path::Path::new(&args[2]));
    match run(before, after) {
        Ok(text) => {
            print!("{text}");
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("mamaps_churn: {e}");
            ExitCode::from(1)
        }
    }
}

fn run(before: &std::path::Path, after: &std::path::Path) -> tile_build::proto::Result<String> {
    let before_len = std::fs::metadata(before)
        .map_err(|e| tile_build::proto::Error(format!("cannot stat {}: {e}", before.display())))?
        .len();
    let after_len = std::fs::metadata(after)
        .map_err(|e| tile_build::proto::Error(format!("cannot stat {}: {e}", after.display())))?
        .len();
    let old = snapshot(&FileReader::open(before)?, before_len)?;
    let new = snapshot(&FileReader::open(after)?, after_len)?;
    Ok(render(&join(&old, &new)))
}
