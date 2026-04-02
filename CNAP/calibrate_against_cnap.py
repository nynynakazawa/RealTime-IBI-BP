#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import statistics


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fit a linear raw-count -> mmHg calibration against CNAP waveform CSV.",
    )
    parser.add_argument("aux_csv", type=Path)
    parser.add_argument("cnap_waveform_csv", type=Path)
    parser.add_argument("--channel", required=True, help="AUX channel label, for example ch2 or bp_wave.")
    parser.add_argument("--sample-rate-hz", type=float, default=100.0)
    parser.add_argument("--max-offset-s", type=float, default=15.0)
    parser.add_argument("--json", action="store_true")
    return parser


def load_aux_series(path: Path, channel: str) -> list[float]:
    field = f"{channel}_raw"
    with path.open("r", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"no rows found in {path}")
    if field not in rows[0]:
        raise KeyError(f"{field} not found in {path}")
    return [float(row[field]) for row in rows]


def load_cnap_waveform(path: Path) -> list[float]:
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        reader = csv.DictReader(handle, delimiter=";")
        rows = list(reader)
    if not rows:
        raise ValueError(f"no rows found in {path}")
    candidate_fields = ("Pressure [mmHg]", '"Pressure [mmHg]"')
    pressure_field = next((field for field in candidate_fields if field in rows[0]), None)
    if pressure_field is None:
        raise KeyError(f"Pressure [mmHg] not found in {path}")
    return [float(row[pressure_field]) for row in rows if row.get(pressure_field)]


def zscore(values: list[float]) -> list[float]:
    mean_value = statistics.fmean(values)
    std_value = statistics.pstdev(values)
    if std_value == 0:
        return [0.0 for _ in values]
    return [(value - mean_value) / std_value for value in values]


def overlap_slices(
    left: list[float],
    right: list[float],
    offset_samples: int,
) -> tuple[list[float], list[float]]:
    if offset_samples >= 0:
        max_len = min(len(left) - offset_samples, len(right))
        return left[offset_samples:offset_samples + max_len], right[:max_len]

    shift = -offset_samples
    max_len = min(len(left), len(right) - shift)
    return left[:max_len], right[shift:shift + max_len]


def correlation(left: list[float], right: list[float]) -> float:
    if len(left) != len(right) or len(left) < 3:
        return 0.0
    mean_left = statistics.fmean(left)
    mean_right = statistics.fmean(right)
    centered_left = [value - mean_left for value in left]
    centered_right = [value - mean_right for value in right]
    denom_left = sum(value * value for value in centered_left)
    denom_right = sum(value * value for value in centered_right)
    if denom_left <= 0 or denom_right <= 0:
        return 0.0
    cov = sum(a * b for a, b in zip(centered_left, centered_right))
    return cov / (denom_left * denom_right) ** 0.5


def fit_linear(x_values: list[float], y_values: list[float]) -> dict:
    if len(x_values) != len(y_values) or len(x_values) < 3:
        raise ValueError("need at least 3 paired samples for linear fit")
    mean_x = statistics.fmean(x_values)
    mean_y = statistics.fmean(y_values)
    centered_x = [value - mean_x for value in x_values]
    centered_y = [value - mean_y for value in y_values]
    var_x = sum(value * value for value in centered_x)
    var_y = sum(value * value for value in centered_y)
    if var_x <= 0 or var_y <= 0:
        raise ValueError("degenerate variance in calibration data")
    cov = sum(a * b for a, b in zip(centered_x, centered_y))
    scale = cov / var_x
    offset = mean_y - scale * mean_x
    predictions = [scale * value + offset for value in x_values]
    mae = statistics.fmean(abs(pred - truth) for pred, truth in zip(predictions, y_values))
    rmse = (
        statistics.fmean((pred - truth) ** 2 for pred, truth in zip(predictions, y_values))
    ) ** 0.5
    corr = cov / (var_x * var_y) ** 0.5
    return {
        "scale": scale,
        "offset": offset,
        "corr": corr,
        "mae": mae,
        "rmse": rmse,
    }


def summarize_calibration(
    aux_values: list[float],
    cnap_values: list[float],
    sample_rate_hz: float,
    max_offset_s: float,
) -> dict:
    aux_norm = zscore(aux_values)
    cnap_norm = zscore(cnap_values)
    max_offset_samples = int(max_offset_s * sample_rate_hz)

    best_offset_samples = 0
    best_corr = None
    best_aux_overlap: list[float] = []
    best_cnap_overlap: list[float] = []

    for offset_samples in range(-max_offset_samples, max_offset_samples + 1):
        aux_overlap, cnap_overlap = overlap_slices(aux_norm, cnap_norm, offset_samples)
        current_corr = correlation(aux_overlap, cnap_overlap)
        if best_corr is None or current_corr > best_corr:
            best_corr = current_corr
            best_offset_samples = offset_samples
            best_aux_overlap, best_cnap_overlap = overlap_slices(aux_values, cnap_values, offset_samples)

    fit = fit_linear(best_aux_overlap, best_cnap_overlap)
    fit.update(
        {
            "offset_samples": best_offset_samples,
            "offset_seconds": best_offset_samples / sample_rate_hz,
            "alignment_corr": best_corr,
            "paired_samples": len(best_aux_overlap),
        }
    )
    return fit


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    aux_path = args.aux_csv.expanduser().resolve()
    cnap_path = args.cnap_waveform_csv.expanduser().resolve()
    aux_values = load_aux_series(aux_path, args.channel)
    cnap_values = load_cnap_waveform(cnap_path)
    summary = summarize_calibration(
        aux_values,
        cnap_values,
        sample_rate_hz=args.sample_rate_hz,
        max_offset_s=args.max_offset_s,
    )
    summary.update(
        {
            "aux_csv": str(aux_path),
            "cnap_waveform_csv": str(cnap_path),
            "channel": args.channel,
            "channel_spec": f"{args.channel}:mmHg:{summary['scale']}:{summary['offset']}",
        }
    )

    if args.json:
        print(json.dumps(summary, indent=2, ensure_ascii=True))
        return 0

    print(f"aux_csv: {summary['aux_csv']}")
    print(f"cnap_waveform_csv: {summary['cnap_waveform_csv']}")
    print(f"channel: {summary['channel']}")
    print(
        f"best_offset: {summary['offset_seconds']:.3f} s "
        f"({summary['offset_samples']} samples), alignment_corr={summary['alignment_corr']:.4f}"
    )
    print(
        f"scale={summary['scale']:.9f} offset={summary['offset']:.9f} "
        f"corr={summary['corr']:.4f} mae={summary['mae']:.4f} rmse={summary['rmse']:.4f}"
    )
    print(f"channel_spec: {summary['channel_spec']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
