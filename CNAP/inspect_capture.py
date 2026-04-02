#!/usr/bin/env python3
from __future__ import annotations

import argparse
import cmath
import csv
import json
from pathlib import Path
import statistics

try:
    import numpy as np
except ImportError:  # pragma: no cover - optional at runtime
    np = None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Summarize a CNAP/TUSB-ADAPIO polling CSV.",
    )
    parser.add_argument("csv_path", type=Path)
    parser.add_argument("--sample-rate-hz", type=float, default=100.0)
    parser.add_argument("--json", action="store_true")
    return parser


def pulse_band_peak(values: list[float], sample_rate_hz: float) -> dict | None:
    if len(values) < 8:
        return None

    mean_value = sum(values) / len(values)
    centered = [value - mean_value for value in values]
    sample_count = len(centered)

    best_hz = None
    best_amplitude = None

    if np is not None:
        centered_np = np.array(centered, dtype=float)
        freqs = np.fft.rfftfreq(sample_count, d=1.0 / sample_rate_hz)
        spectrum = np.abs(np.fft.rfft(centered_np))
        mask = (freqs >= 0.5) & (freqs <= 3.0)
        if mask.any():
            masked_freqs = freqs[mask]
            masked_spectrum = spectrum[mask]
            peak_index = int(np.argmax(masked_spectrum))
            best_hz = float(masked_freqs[peak_index])
            best_amplitude = float(masked_spectrum[peak_index])
    else:
        for bin_index in range(1, sample_count // 2 + 1):
            frequency_hz = bin_index * sample_rate_hz / sample_count
            if frequency_hz < 0.5 or frequency_hz > 3.0:
                continue
            accumulator = 0j
            for sample_index, sample_value in enumerate(centered):
                angle = -2.0j * cmath.pi * bin_index * sample_index / sample_count
                accumulator += sample_value * cmath.exp(angle)
            amplitude = abs(accumulator)
            if best_amplitude is None or amplitude > best_amplitude:
                best_hz = frequency_hz
                best_amplitude = amplitude

    if best_hz is None or best_amplitude is None:
        return None

    return {
        "peak_hz": best_hz,
        "peak_bpm": best_hz * 60.0,
        "peak_amplitude": best_amplitude,
    }


def summarize(csv_path: Path, sample_rate_hz: float) -> dict:
    with csv_path.open("r", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"no rows found in {csv_path}")

    raw_fields = [field for field in rows[0].keys() if field.endswith("_raw")]
    channels = {}
    for field in raw_fields:
        values = [float(row[field]) for row in rows]
        channel_summary = {
            "count": len(values),
            "min": min(values),
            "max": max(values),
            "mean": statistics.fmean(values),
            "std": statistics.pstdev(values),
        }
        pulse_peak = pulse_band_peak(values, sample_rate_hz)
        if pulse_peak is not None:
            channel_summary.update(pulse_peak)
        channels[field[:-4]] = channel_summary

    return {
        "csv_path": str(csv_path.resolve()),
        "sample_rate_hz": sample_rate_hz,
        "row_count": len(rows),
        "channels": channels,
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    summary = summarize(args.csv_path.expanduser().resolve(), args.sample_rate_hz)

    if args.json:
        print(json.dumps(summary, indent=2, ensure_ascii=True))
        return 0

    print(f"file: {summary['csv_path']}")
    print(f"rows: {summary['row_count']} sample_rate_hz: {summary['sample_rate_hz']}")
    for label, channel in summary["channels"].items():
        line = (
            f"{label}: min={channel['min']:.3f} max={channel['max']:.3f} "
            f"mean={channel['mean']:.3f} std={channel['std']:.3f}"
        )
        if "peak_bpm" in channel:
            line += (
                f" pulse_peak={channel['peak_bpm']:.1f} bpm"
                f" peak_amp={channel['peak_amplitude']:.2f}"
            )
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
