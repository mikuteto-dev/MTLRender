use std::collections::BTreeMap;
use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

const DIFF_THRESHOLD: u8 = 8;
const DIFF_GAIN: u16 = 8;
const METRICS: [&str; 16] = [
    "gpu_ms",
    "gpu_p50_ms",
    "gpu_p99_ms",
    "fps",
    "avg_ms",
    "p50_ms",
    "p99_ms",
    "low1_fps",
    "gpu_util",
    "cpu_util",
    "shown_fps",
    "ab_a_gpu_ms",
    "ab_b_gpu_ms",
    "ab_a_frame_ms",
    "ab_b_frame_ms",
    "ab_ratio",
];

struct Image {
    width: u32,
    height: u32,
    samples: usize,
    data: Vec<u8>,
}

struct ImageDiff {
    mean_abs: f64,
    max: u8,
    over_one: usize,
    changed_ratio: f64,
    psnr: f64,
    heat: Vec<u8>,
}

struct Backend {
    name: String,
    rounds: Vec<PathBuf>,
    metrics: BTreeMap<String, Vec<f64>>,
}

fn mean(values: &[f64]) -> Option<f64> {
    if values.is_empty() {
        None
    } else {
        Some(values.iter().sum::<f64>() / values.len() as f64)
    }
}

fn confidence_interval(values: &[f64]) -> Option<(f64, f64)> {
    let average = mean(values)?;
    if values.len() < 2 {
        return Some((average, average));
    }
    let variance = values
        .iter()
        .map(|value| (value - average) * (value - average))
        .sum::<f64>()
        / (values.len() - 1) as f64;
    let critical = [
        0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179,
        2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064, 2.060,
        2.056, 2.052, 2.048, 2.045, 2.042,
    ];
    let df = values.len() - 1;
    let t = critical.get(df).copied().unwrap_or(1.96);
    let margin = t * variance.sqrt() / (values.len() as f64).sqrt();
    Some((average - margin, average + margin))
}

fn ratio_ci(a: &[f64], b: &[f64]) -> Option<(f64, f64, f64, usize)> {
    let ratios: Vec<f64> = a
        .iter()
        .zip(b)
        .filter_map(|(left, right)| {
            if *left == 0.0 {
                None
            } else {
                Some(right / left)
            }
        })
        .collect();
    let average = mean(&ratios)?;
    let interval = confidence_interval(&ratios)?;
    Some((average, interval.0, interval.1, ratios.len()))
}

fn summary(dir: &Path) -> Result<BTreeMap<String, String>, String> {
    let path = dir.join("summary.txt");
    let text = std::fs::read_to_string(&path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(text
        .lines()
        .filter_map(|line| line.split_once('='))
        .map(|(key, value)| (key.to_owned(), value.to_owned()))
        .collect())
}

fn image_points(dir: &Path) -> Result<Vec<String>, String> {
    let summary_values = summary(dir).unwrap_or_default();
    let manifest = ["points.txt", "manifest.txt"]
        .iter()
        .find_map(|name| {
            let path = dir.join(name);
            std::fs::read_to_string(path).ok()
        })
        .or_else(|| {
            summary_values
                .get("image_points")
                .map(|value| value.to_owned())
        });
    if let Some(text) = manifest {
        let points: Vec<String> = text
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
            .flat_map(|line| line.split(',').map(str::trim))
            .filter(|line| !line.is_empty())
            .map(str::to_owned)
            .collect();
        if points.is_empty() {
            return Err(format!("{}: image manifest is empty", dir.display()));
        }
        return Ok(points);
    }
    let mut points: Vec<String> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let path = entry.path();
            (path.extension().and_then(|value| value.to_str()) == Some("png"))
                .then(|| {
                    path.file_name()
                        .map(|name| name.to_string_lossy().into_owned())
                })
                .flatten()
        })
        .filter(|name| {
            name.starts_with("point-") || name == "screenshot.png" || name == "screenshot_b.png"
        })
        .collect();
    points.sort();
    Ok(points)
}

fn system_averages(dir: &Path, start: f64, end: f64) -> Vec<(&'static str, f64)> {
    let Ok(text) = std::fs::read_to_string(dir.join("system.csv")) else {
        return Vec::new();
    };
    let mut sums = [(0.0, 0u32); 2];
    for line in text.lines().skip(1) {
        let fields: Vec<&str> = line.split(',').collect();
        let Some(time) = fields.first().and_then(|v| v.parse::<f64>().ok()) else {
            continue;
        };
        if time < start || time > end {
            continue;
        }
        for (sum, field) in sums.iter_mut().zip(fields.iter().skip(1)) {
            if let Ok(value) = field.parse::<f64>() {
                sum.0 += value;
                sum.1 += 1;
            }
        }
    }
    ["gpu_util", "cpu_util"]
        .into_iter()
        .zip(sums)
        .filter(|(_, (_, count))| *count > 0)
        .map(|(key, (sum, count))| (key, sum / f64::from(count)))
        .collect()
}

fn load_backend(dir: &Path) -> Result<Backend, String> {
    let mut rounds: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.join("summary.txt").is_file())
        .collect();
    rounds.sort();
    if rounds.is_empty() {
        return Err(format!("{}: no rounds with summary.txt", dir.display()));
    }
    let mut metrics: BTreeMap<String, Vec<f64>> = BTreeMap::new();
    rounds.retain(|round| {
        summary(round)
            .ok()
            .map(|values| {
                let run_valid = values.get("run_valid").is_none_or(|value| value != "false");
                let image_valid = values
                    .get("image_gate")
                    .is_none_or(|value| value != "invalid");
                run_valid && image_valid
            })
            .unwrap_or(true)
    });
    if rounds.is_empty() {
        return Err(format!("{}: all runs are invalid", dir.display()));
    }
    for round in &rounds {
        let values = summary(round)?;
        for key in METRICS {
            if let Some(value) = values.get(key).and_then(|v| v.parse().ok()) {
                metrics.entry(key.to_owned()).or_default().push(value);
            }
        }
        let window = (
            values.get("measure_start_ms").and_then(|v| v.parse().ok()),
            values.get("measure_end_ms").and_then(|v| v.parse().ok()),
        );
        if let (Some(start), Some(end)) = window {
            for (key, value) in system_averages(round, start, end) {
                metrics.entry(key.to_owned()).or_default().push(value);
            }
        }
    }
    let name = dir
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    Ok(Backend {
        name,
        rounds,
        metrics,
    })
}

fn median(values: &[f64]) -> Option<f64> {
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let middle = sorted.len() / 2;
    if sorted.len() % 2 == 1 {
        sorted.get(middle).copied()
    } else {
        Some((sorted.get(middle.checked_sub(1)?)? + sorted.get(middle)?) / 2.0)
    }
}

fn spread(values: &[f64]) -> String {
    let min = values.iter().copied().fold(f64::INFINITY, f64::min);
    let max = values.iter().copied().fold(f64::NEG_INFINITY, f64::max);
    format!("{min:.1}-{max:.1}")
}

fn read_png(path: &Path) -> Result<Image, String> {
    let file = File::open(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut decoder = png::Decoder::new(std::io::BufReader::new(file));
    decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut reader = decoder.read_info().map_err(|e| e.to_string())?;
    let size = reader
        .output_buffer_size()
        .ok_or("image is too large to decode")?;
    let mut data = vec![0; size];
    let info = reader.next_frame(&mut data).map_err(|e| e.to_string())?;
    data.truncate(info.buffer_size());
    Ok(Image {
        width: info.width,
        height: info.height,
        samples: info.color_type.samples(),
        data,
    })
}

fn compare(a: &Image, b: &Image) -> Result<ImageDiff, String> {
    if a.width != b.width || a.height != b.height || a.samples != b.samples {
        return Err(format!(
            "screenshots differ in shape: {}x{}x{} vs {}x{}x{}",
            a.width, a.height, a.samples, b.width, b.height, b.samples
        ));
    }
    let channels = a.samples.min(3);
    let mut total = 0u64;
    let mut squared = 0u64;
    let mut over_one = 0u64;
    let mut max = 0u8;
    let mut changed = 0u64;
    let mut pixels = 0u64;
    let mut heat = Vec::with_capacity(a.data.len() / a.samples.max(1) * 3);
    for (pa, pb) in a
        .data
        .chunks_exact(a.samples)
        .zip(b.data.chunks_exact(b.samples))
    {
        let mut worst = 0u8;
        for (ca, cb) in pa.iter().zip(pb).take(channels) {
            let d = ca.abs_diff(*cb);
            total += u64::from(d);
            squared += u64::from(d) * u64::from(d);
            worst = worst.max(d);
        }
        max = max.max(worst);
        pixels += 1;
        if worst > 1 {
            over_one += 1;
        }
        if worst > DIFF_THRESHOLD {
            changed += 1;
        }
        let level = (u16::from(worst) * DIFF_GAIN).min(255) as u8;
        heat.extend([level, level / 4, 0]);
    }
    let count = (pixels * channels as u64).max(1) as f64;
    let mse = squared as f64 / count;
    Ok(ImageDiff {
        mean_abs: total as f64 / count,
        max,
        over_one: over_one as usize,
        changed_ratio: changed as f64 / pixels.max(1) as f64,
        psnr: if mse == 0.0 {
            f64::INFINITY
        } else {
            10.0 * (255.0 * 255.0 / mse).log10()
        },
        heat,
    })
}

fn write_heat(path: &Path, image: &Image, heat: &[u8]) -> Result<(), String> {
    let file = File::create(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let mut encoder = png::Encoder::new(BufWriter::new(file), image.width, image.height);
    encoder.set_color(png::ColorType::Rgb);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().map_err(|e| e.to_string())?;
    writer.write_image_data(heat).map_err(|e| e.to_string())
}

fn print_table(base: &Backend, others: &[Backend]) {
    let mut header = format!("{:<10} {:>10} {:>12}", "metric", base.name, "range");
    for other in others {
        header.push_str(&format!(
            " | {:>13} {:>12} {:>6}",
            other.name, "range", "ratio"
        ));
    }
    println!("{header}");
    for key in METRICS {
        let Some(base_values) = base.metrics.get(key) else {
            continue;
        };
        let Some(reference) = median(base_values) else {
            continue;
        };
        let base_interval = confidence_interval(base_values).unwrap_or((reference, reference));
        let mut line = format!(
            "{key:<10} {reference:>10.2} [{:.2},{:.2}] {:>12} n={}",
            base_interval.0,
            base_interval.1,
            spread(base_values),
            base_values.len()
        );
        for other in others {
            match other.metrics.get(key).and_then(|v| Some((median(v)?, v))) {
                Some((value, values)) => {
                    let interval = confidence_interval(values).unwrap_or((value, value));
                    let paired = ratio_ci(base_values, values);
                    if let Some((ratio, low, high, count)) = paired {
                        line.push_str(&format!(
                            " | {value:>8.2} [{:.2},{:.2}] {:>9} {ratio:.3}x [{low:.3},{high:.3}] n={count}",
                            interval.0, interval.1, spread(values)
                        ));
                    } else {
                        line.push_str(&format!(
                            " | {value:>8.2} [{:.2},{:.2}] {:>9} - n={}",
                            interval.0,
                            interval.1,
                            spread(values),
                            values.len()
                        ));
                    }
                }
                None => line.push_str(&format!(" | {:>8} {:>15} {:>9} {:>6}", "-", "-", "-", "-")),
            }
        }
        println!("{line}");
    }
    for backend in std::iter::once(base).chain(others) {
        if let Some(values) = backend.metrics.get("ab_ratio") {
            let interval = confidence_interval(values).unwrap_or((0.0, 0.0));
            let average = mean(values).unwrap_or(0.0);
            println!(
                "ab={} ratio={average:.4} ci95=[{:.4},{:.4}] repeats={} evidence={}",
                backend.name,
                interval.0,
                interval.1,
                values.len(),
                if values.len() < 3 {
                    "insufficient"
                } else {
                    "sufficient"
                }
            );
        }
    }
    println!(
        "rounds: {}",
        std::iter::once(base)
            .chain(others)
            .map(|b| format!("{}={}", b.name, b.rounds.len()))
            .collect::<Vec<_>>()
            .join(" ")
    );
}

fn compare_images(base: &Backend, others: &[Backend]) -> Result<(), String> {
    for other in others {
        let mut best: Option<(ImageDiff, Image, PathBuf)> = None;
        for (base_round, round) in base.rounds.iter().zip(&other.rounds) {
            let (Ok(reference), Ok(shot)) = (
                read_png(&base_round.join("screenshot.png")),
                read_png(&round.join("screenshot.png")),
            ) else {
                continue;
            };
            let diff = compare(&shot, &reference)?;
            if best
                .as_ref()
                .is_none_or(|(current, _, _)| diff.changed_ratio < current.changed_ratio)
            {
                best = Some((diff, shot, round.join("diff.png")));
            }
        }
        let Some((diff, shot, heat_path)) = best else {
            println!("image: {} screenshots missing", other.name);
            continue;
        };
        write_heat(&heat_path, &shot, &diff.heat)?;
        println!(
            "image: {} vs {} (best of same-index rounds): mean |d| {:.3}  pixels > {DIFF_THRESHOLD}: {:.3}%  PSNR {:.2} dB  heatmap {}",
            other.name,
            base.name,
            diff.mean_abs,
            diff.changed_ratio * 100.0,
            diff.psnr,
            heat_path.display()
        );
    }
    Ok(())
}

fn validate_images(
    reference_dir: &Path,
    candidate_dir: &Path,
    allowed_ratio: f64,
) -> Result<(), String> {
    if !allowed_ratio.is_finite() || !(0.0..=100.0).contains(&allowed_ratio) {
        return Err("image threshold must be a percentage from 0 to 100".to_owned());
    }
    let reference_points = image_points(reference_dir)?;
    let candidate_points = image_points(candidate_dir)?;
    let mut invalid = reference_points.is_empty();
    if reference_points.is_empty() {
        println!("image_point=error baseline has no points");
    }
    for point in &reference_points {
        let reference_path = reference_dir.join(point);
        let candidate_path = candidate_dir.join(point);
        let result = if !reference_path.is_file() {
            Err(format!("missing baseline point {point}"))
        } else if !candidate_path.is_file() {
            Err(format!("missing candidate point {point}"))
        } else {
            let reference = read_png(&reference_path);
            let candidate = read_png(&candidate_path);
            match (reference, candidate) {
                (Ok(reference), Ok(candidate)) => {
                    compare(&candidate, &reference).map(|diff| (diff.changed_ratio * 100.0, diff))
                }
                (Err(error), _) | (_, Err(error)) => Err(error),
            }
        };
        match result {
            Ok((ratio, diff)) => {
                let heat_path = candidate_dir.join(format!("{point}.heat.png"));
                let candidate = read_png(&candidate_path)?;
                write_heat(&heat_path, &candidate, &diff.heat)?;
                println!(
                    "image_point={point} pixels_over_{DIFF_THRESHOLD}={ratio:.6}% threshold={allowed_ratio:.6}%"
                );
                if ratio > allowed_ratio {
                    invalid = true;
                }
            }
            Err(error) => {
                println!("image_point={point} error={error}");
                invalid = true;
            }
        }
    }
    for point in candidate_points {
        if !reference_points.iter().any(|known| known == &point) {
            println!("image_point={point} error=point is not in baseline manifest");
            invalid = true;
        }
    }
    if invalid {
        println!("image_gate=invalid");
        Err("image gate failed".to_owned())
    } else {
        println!("image_gate=valid");
        Ok(())
    }
}

fn run(baseline: &Path, others: &[PathBuf]) -> Result<(), String> {
    let base = load_backend(baseline)?;
    let others = others
        .iter()
        .map(|dir| load_backend(dir))
        .collect::<Result<Vec<_>, _>>()?;
    print_table(&base, &others);
    compare_images(&base, &others)
}

fn run_image(a: &Path, b: &Path, heat: Option<&Path>) -> Result<(), String> {
    let reference = read_png(a)?;
    let shot = read_png(b)?;
    let diff = compare(&shot, &reference)?;
    if let Some(path) = heat {
        write_heat(path, &shot, &diff.heat)?;
    }
    let pixels = (f64::from(reference.width) * f64::from(reference.height)).max(1.0);
    println!(
        "image: mean |d| {:.4}  max |d| {}  pixels > 1: {} ({:.4}%)  pixels > {DIFF_THRESHOLD}: {:.4}%  PSNR {:.2} dB",
        diff.mean_abs,
        diff.max,
        diff.over_one,
        diff.over_one as f64 / pixels * 100.0,
        diff.changed_ratio * 100.0,
        diff.psnr
    );
    Ok(())
}

fn main() -> ExitCode {
    let args: Vec<PathBuf> = std::env::args_os().skip(1).map(PathBuf::from).collect();
    if args
        .first()
        .is_some_and(|first| first.as_os_str() == "validate-images")
    {
        let (Some(reference), Some(candidate)) = (args.get(1), args.get(2)) else {
            eprintln!(
                "usage: benchcmp validate-images <reference-dir> <candidate-dir> [threshold-percent]"
            );
            return ExitCode::from(2);
        };
        let threshold = match args
            .get(3)
            .map(|value| value.to_string_lossy().parse::<f64>())
            .transpose()
        {
            Ok(Some(value)) => value,
            Ok(None) => 0.0,
            Err(_) => {
                eprintln!("benchcmp: threshold must be a percentage from 0 to 100");
                return ExitCode::from(2);
            }
        };
        return match validate_images(reference, candidate, threshold) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("benchcmp: {error}");
                ExitCode::FAILURE
            }
        };
    }
    if args
        .first()
        .is_some_and(|first| first.as_os_str() == "image")
    {
        let (Some(a), Some(b)) = (args.get(1), args.get(2)) else {
            eprintln!("usage: benchcmp image <a.png> <b.png> [heatmap.png]");
            return ExitCode::from(2);
        };
        return match run_image(a, b, args.get(3).map(PathBuf::as_path)) {
            Ok(()) => ExitCode::SUCCESS,
            Err(error) => {
                eprintln!("benchcmp: {error}");
                ExitCode::FAILURE
            }
        };
    }
    let Some((baseline, others)) = args.split_first().filter(|(_, rest)| !rest.is_empty()) else {
        eprintln!(
            "usage: benchcmp image <a.png> <b.png> [heatmap.png]\n       benchcmp <baseline-backend-dir> <backend-dir>..."
        );
        return ExitCode::from(2);
    };
    match run(baseline, others) {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("benchcmp: {error}");
            ExitCode::FAILURE
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{Image, compare, confidence_interval, ratio_ci};

    #[test]
    fn compare_reports_shape_mismatch() {
        let a = Image {
            width: 1,
            height: 1,
            samples: 3,
            data: vec![0, 0, 0],
        };
        let b = Image {
            width: 2,
            height: 1,
            samples: 3,
            data: vec![0, 0, 0, 0, 0, 0],
        };
        assert!(compare(&a, &b).is_err());
    }

    #[test]
    fn confidence_interval_is_conservative_for_small_samples() {
        let one = confidence_interval(&[4.0]);
        let two = confidence_interval(&[1.0, 3.0]);
        let three = confidence_interval(&[1.0, 2.0, 3.0]);
        assert_eq!(one, Some((4.0, 4.0)));
        assert!(two.is_some_and(|(low, high)| low < 2.0 && high > 2.0));
        assert!(three.is_some_and(|(low, high)| low < 2.0 && high > 2.0));
    }

    #[test]
    fn paired_ratio_uses_same_repeat() {
        let result = ratio_ci(&[10.0, 20.0, 30.0], &[20.0, 30.0, 45.0]);
        assert!(result.is_some_and(|(average, low, high, count)| {
            count == 3 && average > 1.0 && low <= average && high >= average
        }));
    }

    #[test]
    fn compare_reports_max_and_over_one() {
        let a = Image {
            width: 2,
            height: 1,
            samples: 3,
            data: vec![0, 0, 0, 100, 200, 250],
        };
        let b = Image {
            width: 2,
            height: 1,
            samples: 3,
            data: vec![1, 1, 1, 102, 200, 250],
        };
        let result = compare(&a, &b).map(|diff| (diff.max, diff.over_one));
        assert_eq!(result, Ok((2, 1)));
    }
}
