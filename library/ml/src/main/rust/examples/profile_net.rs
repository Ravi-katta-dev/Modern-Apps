//! Per-kind GPU time and per-pass wall time for one model, on whatever device runs it.
//!
//! ```text
//! cargo run --release -p modelrunner --example profile_net -- nllb [path/to/nllb600.maml]
//! adb shell MODELRUNNER_TIMESTAMPS=1 /data/local/tmp/maml/profile_net nllb /data/local/tmp/maml/nllb/nllb600.maml
//! ```
//!
//! Timestamps need `MODELRUNNER_TIMESTAMPS=1`; without it only wall times print. The wall times
//! are with timestamps off unless it is set, so measure the two in separate runs.
use std::collections::BTreeMap;
use std::time::Instant;

use modelrunner::nets::nllb;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::{Net, StepParams};
use modelrunner::weights::{graph, Weights};

/// Source tokens. A longish sentence, so cross-attention costs show.
const SRC_LEN: u32 = 32;

/// Decode steps timed after one warm-up.
const STEPS: u32 = 24;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let model = args.first().map(String::as_str).unwrap_or("nllb");
    let result = match model {
        "nllb" => nllb_profile(args.get(1).map(String::as_str)),
        other => Err(format!("unknown model {other:?}; known: nllb")),
    };
    if let Err(why) = result {
        println!("failed: {why}");
    }
}

fn nllb_profile(path: Option<&str>) -> Result<(), String> {
    let path = path
        .map(str::to_owned)
        .or_else(|| std::env::var("MODELRUNNER_NLLB").ok())
        .ok_or("pass the nllb600.maml path or set MODELRUNNER_NLLB")?;
    let bytes = std::fs::read(&path).map_err(|e| format!("cannot read {path}: {e}"))?;
    let weights = Weights::parse(&bytes, graph::NLLB)?;
    let context = context::shared()?;

    let started = Instant::now();
    let mut net = Reshaped::new(
        context,
        &weights,
        nllb::Mode::Encode { len: SRC_LEN },
        |offsets, mode| nllb::build(offsets, mode),
    )?;
    println!("load      {:>8.1} ms (upload + first record)", ms(started));

    // A plausible source: the source-language token, then ordinary pieces, then `</s>`.
    let mut source: Vec<u32> = vec![256_047];
    source.extend((0..SRC_LEN - 2).map(|i| 1_000 + i * 37));
    source.push(2);
    let embedded = nllb::embed_positions(weights.reader(), &source, 0)?;

    let mut encode_ms = Vec::new();
    let mut switch_ms = Vec::new();
    let mut step_ms = Vec::new();
    // Three sentences, so the per-call re-records show up as well as steady-state steps.
    for _ in 0..3 {
        let t = Instant::now();
        net.at(nllb::Mode::Encode { len: SRC_LEN })?.infer_raw(&embedded)?;
        encode_ms.push(ms(t));

        let mut token = 2u32;
        for step in 0..=STEPS {
            let row = nllb::embed_positions(weights.reader(), &[token], step)?;
            let t = Instant::now();
            let at = net.at(nllb::Mode::DecodeStep { src_len: SRC_LEN })?;
            if step == 0 {
                switch_ms.push(ms(t));
            }
            let t = Instant::now();
            at.set_params(StepParams { prefix: step, window_start: 0 })?;
            let out = at.infer_raw(&row)?;
            if step > 0 {
                step_ms.push(ms(t));
            }
            token = greedy(&out).unwrap_or(2).min(nllb::VOCAB - 1);
        }
    }
    println!("encode    {} (includes the re-record into Encode after the first)", stats(&encode_ms));
    println!("switch    {} (re-record into DecodeStep)", stats(&switch_ms));
    println!("step      {}", stats(&step_ms));

    let decode = net.at(nllb::Mode::DecodeStep { src_len: SRC_LEN })?;
    print_kinds("decode step", decode);
    let encode = net.at(nllb::Mode::Encode { len: SRC_LEN })?;
    encode.infer_raw(&embedded)?;
    print_kinds("encode", encode);
    Ok(())
}

/// The token from a greedy plan's `[lo, hi]` output, or the argmax of a logits plan's splits.
fn greedy(out: &[Vec<f32>]) -> Option<u32> {
    if let [pair] = out {
        if let [lo, hi] = pair[..] {
            return Some(hi as u32 * 2048 + lo as u32);
        }
    }
    let mut best: Option<(u32, f32)> = None;
    let mut index = 0u32;
    for split in out {
        for &value in split {
            if best.is_none_or(|(_, top)| value > top) {
                best = Some((index, value));
            }
            index += 1;
        }
    }
    best.map(|(id, _)| id)
}

fn print_kinds(label: &str, net: &Net) {
    let times = match net.op_times() {
        Ok(times) => times,
        Err(why) => {
            println!("{label}: {why}");
            return;
        }
    };
    let mut kinds: BTreeMap<String, (usize, f64)> = BTreeMap::new();
    let (mut total, mut gaps) = (0.0, 0.0);
    for (name, us, gap) in &times {
        let kind = name.split(": ").nth(1).unwrap_or(name).to_string();
        let entry = kinds.entry(kind).or_default();
        entry.0 += 1;
        entry.1 += us;
        total += us;
        gaps += gap;
    }
    let mut rows: Vec<_> = kinds.into_iter().collect();
    rows.sort_by(|a, b| b.1 .1.total_cmp(&a.1 .1));
    println!();
    println!("{label}: {} dispatches, {:.2} ms in shaders, {:.2} ms in gaps", times.len(), total / 1e3, gaps / 1e3);
    println!("  {:<22} {:>6} {:>10} {:>10}", "kind", "count", "total ms", "mean us");
    for (kind, (count, us)) in rows {
        println!("  {kind:<22} {count:>6} {:>10.3} {:>10.1}", us / 1e3, us / count as f64);
    }
}

fn ms(since: Instant) -> f64 {
    since.elapsed().as_secs_f64() * 1e3
}

fn stats(values: &[f64]) -> String {
    if values.is_empty() {
        return "n/a".into();
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let mean = sorted.iter().sum::<f64>() / sorted.len() as f64;
    format!(
        "mean {mean:>8.2} ms  median {:>8.2}  min {:>8.2}  max {:>8.2}  (n={})",
        sorted[sorted.len() / 2],
        sorted[0],
        sorted[sorted.len() - 1],
        sorted.len()
    )
}
