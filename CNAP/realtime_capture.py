#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import deque
import csv
import json
from dataclasses import asdict, dataclass
from pathlib import Path
import shutil
import statistics
import sys
import time
from typing import Any

from tusb_adapio import TUSBAdapio, TUSBAdapioError


@dataclass(frozen=True)
class ChannelSpec:
    index: int
    label: str
    unit: str = "count"
    scale: float = 1.0
    offset: float = 0.0

    @classmethod
    def parse(cls, raw: str) -> "ChannelSpec":
        parts = raw.split(":")
        if not parts or len(parts) > 5:
            raise ValueError(
                "channel must be index[:label[:unit[:scale[:offset]]]]"
            )
        index = int(parts[0])
        label = parts[1] if len(parts) >= 2 and parts[1] else f"ch{index}"
        unit = parts[2] if len(parts) >= 3 and parts[2] else "count"
        scale = float(parts[3]) if len(parts) >= 4 and parts[3] else 1.0
        offset = float(parts[4]) if len(parts) >= 5 and parts[4] else 0.0
        return cls(index=index, label=label, unit=unit, scale=scale, offset=offset)

    def convert(self, raw_value: int) -> float:
        return raw_value * self.scale + self.offset


def default_channels(raw_channels: list[str]) -> list[ChannelSpec]:
    if not raw_channels:
        return [ChannelSpec(index=0, label="ch0")]
    return [ChannelSpec.parse(value) for value in raw_channels]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Realtime capture for CNAP AUX via TUSB-ADAPIO.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    info_parser = subparsers.add_parser("info", help="Probe the device and read a few samples.")
    info_parser.add_argument(
        "--channel",
        action="append",
        default=[],
        help="index[:label[:unit[:scale[:offset]]]]",
    )
    info_parser.add_argument("--samples", type=int, default=5)
    info_parser.add_argument("--interval", type=float, default=0.2)

    capture_parser = subparsers.add_parser(
        "capture",
        help="Continuously poll one scan and write rows to CSV.",
    )
    capture_parser.add_argument(
        "--channel",
        action="append",
        default=[],
        help="index[:label[:unit[:scale[:offset]]]]",
    )
    capture_parser.add_argument("--sample-rate-hz", type=float, default=100.0)
    capture_parser.add_argument("--duration", type=float, default=0.0)
    capture_parser.add_argument("--status-interval", type=float, default=1.0)
    capture_parser.add_argument("--session-id", default="")
    capture_parser.add_argument("--output", type=Path, default=None)
    capture_parser.add_argument("--notes", default="")
    capture_parser.add_argument("--waveform-label", default="")
    capture_parser.add_argument("--waveform-window-s", type=float, default=8.0)
    capture_parser.add_argument("--waveform-min-interval-s", type=float, default=0.4)
    capture_parser.add_argument("--activity-min-std-raw", type=float, default=3.0)
    capture_parser.add_argument("--activity-min-range-raw", type=float, default=8.0)
    capture_parser.add_argument("--allow-inactive", action="store_true")
    capture_parser.add_argument("--archive-root", type=Path, default=None)
    capture_parser.add_argument("--no-archive", action="store_true")

    beat_parser = subparsers.add_parser(
        "beat",
        help="Emit one CSV row per detected beat in CNAP-like format.",
    )
    beat_parser.add_argument("--activity-channel", required=True, help="index[:label[:unit[:scale[:offset]]]]")
    beat_parser.add_argument("--sys-channel", default="")
    beat_parser.add_argument("--mean-channel", default="")
    beat_parser.add_argument("--dia-channel", default="")
    beat_parser.add_argument("--hr-channel", default="")
    beat_parser.add_argument("--sample-rate-hz", type=float, default=100.0)
    beat_parser.add_argument("--duration", type=float, default=0.0)
    beat_parser.add_argument("--status-interval", type=float, default=1.0)
    beat_parser.add_argument("--session-id", default="")
    beat_parser.add_argument("--output", type=Path, default=None)
    beat_parser.add_argument("--notes", default="")
    beat_parser.add_argument("--activity-window-s", type=float, default=8.0)
    beat_parser.add_argument("--activity-min-std-raw", type=float, default=3.0)
    beat_parser.add_argument("--activity-min-range-raw", type=float, default=8.0)
    beat_parser.add_argument("--min-beat-interval-s", type=float, default=0.4)
    beat_parser.add_argument("--max-beat-interval-s", type=float, default=2.0)
    beat_parser.add_argument("--archive-root", type=Path, default=None)
    beat_parser.add_argument("--no-archive", action="store_true")

    buffered_parser = subparsers.add_parser(
        "buffered",
        help="Run one hardware-triggered acquisition and dump the buffer.",
    )
    buffered_parser.add_argument(
        "--channel",
        action="append",
        default=[],
        help="index[:label[:unit[:scale[:offset]]]]; first channel is used for output formatting.",
    )
    buffered_parser.add_argument(
        "--mode",
        choices=("digital", "analog-rising", "analog-falling"),
        default="digital",
    )
    buffered_parser.add_argument("--end-channel", type=int, default=0)
    buffered_parser.add_argument("--buffer-size", type=int, default=100)
    buffered_parser.add_argument("--threshold", type=int, default=512)
    buffered_parser.add_argument("--trigger-channel", type=int, default=0)
    buffered_parser.add_argument("--status-interval", type=float, default=0.25)
    buffered_parser.add_argument("--timeout", type=float, default=5.0)
    buffered_parser.add_argument("--session-id", default="")
    buffered_parser.add_argument("--output", type=Path, default=None)
    buffered_parser.add_argument("--archive-root", type=Path, default=None)
    buffered_parser.add_argument("--no-archive", action="store_true")

    return parser


def repo_root_from(root: Path) -> Path:
    return root.parent


def default_archive_root(root: Path) -> Path:
    return repo_root_from(root) / "Analysis" / "Data" / "pdp" / "realtime_aux"


def ensure_output_path(root: Path, session_id: str, output: Path | None, suffix: str) -> Path:
    if output is not None:
        output = output.expanduser().resolve()
    else:
        output = root / "captures" / session_id / f"{session_id}_{suffix}.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    return output


def metadata_path_for(output_path: Path) -> Path:
    return output_path.with_suffix(".json")


def metrics_path_for(output_path: Path) -> Path:
    return output_path.with_name(output_path.stem + "_metrics.csv")


def write_json(path: Path, payload: dict) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def iso_now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime())


def format_latest(specs: list[ChannelSpec], raw_values: list[int]) -> str:
    parts = []
    for spec, raw_value in zip(specs, raw_values):
        converted = spec.convert(raw_value)
        parts.append(
            f"{spec.label}={converted:.3f} {spec.unit} (raw={raw_value})"
        )
    return ", ".join(parts)


def parse_optional_channel(raw: str) -> ChannelSpec | None:
    return ChannelSpec.parse(raw) if raw else None


def activity_window_is_valid(
    raw_values: list[int],
    *,
    min_std_raw: float,
    min_range_raw: float,
) -> bool:
    if len(raw_values) < 8:
        return False
    std = statistics.pstdev(raw_values)
    value_range = max(raw_values) - min(raw_values)
    return std >= min_std_raw and value_range >= min_range_raw


def estimate_wave_metrics(
    values: list[float],
    *,
    sample_rate_hz: float,
    min_interval_s: float,
) -> dict | None:
    if len(values) < max(8, int(sample_rate_hz * 2.5)):
        return None

    std = statistics.pstdev(values)
    if std <= 0:
        return None

    mean_value = sum(values) / len(values)
    threshold = mean_value + 0.35 * std
    refractory_samples = max(1, int(sample_rate_hz * min_interval_s))

    peaks: list[int] = []
    index = 1
    last_limit = len(values) - 1
    while index < last_limit:
        current = values[index]
        if current >= threshold and current >= values[index - 1] and current >= values[index + 1]:
            best_index = index
            best_value = current
            scan_limit = min(last_limit, index + refractory_samples)
            for look_ahead in range(index + 1, scan_limit):
                candidate = values[look_ahead]
                if (
                    candidate > best_value
                    and candidate >= values[look_ahead - 1]
                    and candidate >= values[look_ahead + 1]
                ):
                    best_index = look_ahead
                    best_value = candidate
            peaks.append(best_index)
            index = best_index + refractory_samples
            continue
        index += 1

    if len(peaks) < 2:
        return None

    intervals = [
        (later - earlier) / sample_rate_hz
        for earlier, later in zip(peaks, peaks[1:])
        if later > earlier
    ]
    if not intervals:
        return None

    mean_interval = sum(intervals) / len(intervals)
    bpm = 60.0 / mean_interval
    if bpm < 30 or bpm > 220:
        return None

    troughs: list[float] = []
    peak_values = [values[index] for index in peaks]
    for earlier, later in zip(peaks, peaks[1:]):
        troughs.append(min(values[earlier:later + 1]))

    return {
        "bpm": bpm,
        "peak_like": sum(peak_values) / len(peak_values),
        "trough_like": sum(troughs) / len(troughs) if troughs else min(values),
    }


def detect_stream_peak(
    sample_history: deque[dict[str, Any]],
    raw_history: deque[int],
    *,
    last_peak_sample_index: int | None,
    min_interval_s: float,
    sample_rate_hz: float,
    min_std_raw: float,
    min_range_raw: float,
) -> dict[str, Any] | None:
    if len(sample_history) < 3 or len(raw_history) < 3:
        return None
    if not activity_window_is_valid(
        list(raw_history),
        min_std_raw=min_std_raw,
        min_range_raw=min_range_raw,
    ):
        return None

    prev_prev = sample_history[-3]["activity_raw"]
    prev = sample_history[-2]["activity_raw"]
    current = sample_history[-1]["activity_raw"]

    if not (prev >= prev_prev and prev > current):
        return None

    mean_value = statistics.fmean(raw_history)
    std_value = statistics.pstdev(raw_history)
    threshold = mean_value + 0.35 * std_value
    peak_sample = sample_history[-2]
    if prev < threshold:
        return None

    refractory_samples = max(1, int(sample_rate_hz * min_interval_s))
    if (
        last_peak_sample_index is not None
        and peak_sample["sample_index"] - last_peak_sample_index < refractory_samples
    ):
        return None

    return peak_sample


def samples_for_interval(
    sample_history: deque[dict[str, Any]],
    *,
    start_sample_index: int,
    end_sample_index: int,
) -> list[dict[str, Any]]:
    return [
        sample
        for sample in sample_history
        if start_sample_index <= sample["sample_index"] <= end_sample_index
    ]


def mean_value_for_spec(
    spec: ChannelSpec | None,
    interval_samples: list[dict[str, Any]],
) -> float | None:
    if spec is None or not interval_samples:
        return None
    values = [sample["converted_by_label"][spec.label] for sample in interval_samples]
    if not values:
        return None
    return statistics.fmean(values)


def format_optional_value(value: float | None) -> str:
    if value is None:
        return ""
    return f"{value:.9f}"


def archive_output_path(
    root: Path,
    session_id: str,
    suffix: str,
    archive_root: Path | None,
    disabled: bool,
) -> Path | None:
    if disabled:
        return None
    base_root = archive_root.expanduser().resolve() if archive_root is not None else default_archive_root(root)
    path = base_root / session_id / f"{session_id}_{suffix}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def copy_if_present(source: Path, destination: Path | None) -> None:
    if destination is None or not source.exists():
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def run_info(root: Path, args: argparse.Namespace) -> int:
    specs = default_channels(args.channel)
    with TUSBAdapio() as device:
        summary = asdict(device.summary())
        running, sampled_num = device.adc_get_status()
        print(json.dumps(summary, indent=2, ensure_ascii=True))
        print(f"status: running={running} sampled_num={sampled_num}")
        for index in range(args.samples):
            raw_values = device.read_scan([spec.index for spec in specs])
            print(f"sample[{index}] {format_latest(specs, raw_values)}")
            if index + 1 < args.samples and args.interval > 0:
                time.sleep(args.interval)
    return 0


def run_capture(root: Path, args: argparse.Namespace) -> int:
    if abs(args.sample_rate_hz - 100.0) > 1e-9:
        raise ValueError("CNAP AUX capture is fixed at 100 Hz; use --sample-rate-hz 100")

    specs = default_channels(args.channel)
    session_id = args.session_id or time.strftime("cnap_%Y%m%d_%H%M%S", time.localtime())
    output_path = ensure_output_path(root, session_id, args.output, "poll")
    meta_path = metadata_path_for(output_path)
    archive_output = archive_output_path(root, session_id, "poll", args.archive_root, args.no_archive)
    archive_meta = metadata_path_for(archive_output) if archive_output is not None else None
    metrics_path = metrics_path_for(output_path)
    archive_metrics = metrics_path_for(archive_output) if archive_output is not None else None

    if args.sample_rate_hz <= 0:
        raise ValueError("sample-rate-hz must be positive")

    fieldnames = [
        "session_id",
        "sample_index",
        "wall_time_iso",
        "epoch_ns",
        "monotonic_ns",
        "elapsed_s",
    ]
    for spec in specs:
        fieldnames.append(f"{spec.label}_raw")
        fieldnames.append(spec.label)

    waveform_spec = next(
        (spec for spec in specs if spec.label == args.waveform_label),
        None,
    )
    if args.waveform_label and waveform_spec is None:
        raise ValueError(f"waveform-label not found among channels: {args.waveform_label}")
    process_start_mono_ns = time.monotonic_ns()
    process_started_at = iso_now()
    recording_start_mono_ns: int | None = (
        process_start_mono_ns if waveform_spec is None or args.allow_inactive else None
    )
    recording_started_at: str | None = (
        process_started_at if waveform_spec is None or args.allow_inactive else None
    )
    polled_count = 0
    row_count = 0
    last_report = time.monotonic()
    period_s = 1.0 / args.sample_rate_hz
    next_deadline = time.monotonic()
    latest_metrics: dict | None = None
    waveform_values: deque[float] | None = None
    waveform_raw_values: deque[int] | None = None
    active_state = waveform_spec is None
    if waveform_spec is not None:
        waveform_values = deque(
            maxlen=max(4, int(args.waveform_window_s * args.sample_rate_hz))
        )
        waveform_raw_values = deque(
            maxlen=max(4, int(args.waveform_window_s * args.sample_rate_hz))
        )

    with TUSBAdapio() as device:
        device_summary = asdict(device.summary())
        metadata = {
            "session_id": session_id,
            "process_started_at": process_started_at,
            "recording_started_at": recording_started_at,
            "sample_rate_hz_requested": args.sample_rate_hz,
            "capture_mode": "single-sample-poll",
            "notes": args.notes,
            "channels": [asdict(spec) for spec in specs],
            "device": device_summary,
            "output_csv": str(output_path),
            "archive_output_csv": str(archive_output) if archive_output is not None else None,
        }
        write_json(meta_path, metadata)
        if archive_meta is not None:
            write_json(archive_meta, metadata)

        with (
            output_path.open("w", newline="", encoding="utf-8") as handle,
            metrics_path.open("w", newline="", encoding="utf-8") as metrics_handle,
        ):
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            metrics_writer = csv.DictWriter(
                metrics_handle,
                fieldnames=[
                    "session_id",
                    "elapsed_s",
                    "sample_index",
                    "effective_rate_hz",
                    "waveform_label",
                    "wave_bpm",
                    "peak_like",
                    "trough_like",
                ],
            )
            metrics_writer.writeheader()

            try:
                while True:
                    now = time.monotonic()
                    if polled_count > 0 and now < next_deadline:
                        time.sleep(next_deadline - now)

                    monotonic_ns = time.monotonic_ns()
                    epoch_ns = time.time_ns()
                    raw_values = device.read_scan([spec.index for spec in specs])
                    process_elapsed_s = (monotonic_ns - process_start_mono_ns) / 1_000_000_000.0
                    wall_time_iso = time.strftime(
                        "%Y-%m-%dT%H:%M:%S",
                        time.localtime(epoch_ns / 1_000_000_000),
                    )

                    row = {
                        "session_id": session_id,
                        "sample_index": row_count,
                        "wall_time_iso": wall_time_iso,
                        "epoch_ns": epoch_ns,
                        "monotonic_ns": monotonic_ns,
                        "elapsed_s": "0.000000000",
                    }
                    current_waveform_raw: int | None = None
                    current_waveform_converted: float | None = None
                    for spec, raw_value in zip(specs, raw_values):
                        converted = spec.convert(raw_value)
                        row[f"{spec.label}_raw"] = raw_value
                        row[spec.label] = f"{converted:.9f}"
                        if waveform_values is not None and waveform_spec == spec:
                            waveform_values.append(converted)
                            waveform_raw_values.append(raw_value)
                            current_waveform_raw = raw_value
                            current_waveform_converted = converted

                    if waveform_raw_values is not None:
                        is_active = activity_window_is_valid(
                            list(waveform_raw_values),
                            min_std_raw=args.activity_min_std_raw,
                            min_range_raw=args.activity_min_range_raw,
                        )
                        if is_active and recording_start_mono_ns is None:
                            recording_start_mono_ns = monotonic_ns
                            recording_started_at = wall_time_iso
                            waveform_values.clear()
                            waveform_raw_values.clear()
                            if current_waveform_converted is not None and current_waveform_raw is not None:
                                waveform_values.append(current_waveform_converted)
                                waveform_raw_values.append(current_waveform_raw)
                            latest_metrics = None
                            print("[capture] active waveform detected; recording started")
                        if is_active != active_state:
                            state_label = "active" if is_active else "inactive"
                            print(f"[capture] waveform state -> {state_label}")
                            active_state = is_active
                    else:
                        is_active = True

                    if is_active or args.allow_inactive:
                        elapsed_s = (
                            (monotonic_ns - recording_start_mono_ns) / 1_000_000_000.0
                            if recording_start_mono_ns is not None
                            else process_elapsed_s
                        )
                        row["elapsed_s"] = f"{elapsed_s:.9f}"
                        writer.writerow(row)
                        row_count += 1
                    next_deadline += period_s
                    polled_count += 1

                    if polled_count % max(1, int(args.sample_rate_hz)) == 0:
                        handle.flush()

                    if args.status_interval > 0 and time.monotonic() - last_report >= args.status_interval:
                        effective_rate = (
                            row_count
                            / max((monotonic_ns - recording_start_mono_ns) / 1_000_000_000.0, 1e-9)
                            if row_count and recording_start_mono_ns is not None
                            else 0.0
                        )
                        metrics_suffix = ""
                        if waveform_values is not None and is_active and recording_start_mono_ns is not None:
                            metrics = estimate_wave_metrics(
                                list(waveform_values),
                                sample_rate_hz=args.sample_rate_hz,
                                min_interval_s=args.waveform_min_interval_s,
                            )
                            if metrics is not None:
                                latest_metrics = metrics
                                metrics_writer.writerow(
                                    {
                                        "session_id": session_id,
                                        "elapsed_s": f"{elapsed_s:.9f}",
                                        "sample_index": row_count,
                                        "effective_rate_hz": f"{effective_rate:.9f}",
                                        "waveform_label": waveform_spec.label,
                                        "wave_bpm": f"{metrics['bpm']:.9f}",
                                        "peak_like": f"{metrics['peak_like']:.9f}",
                                        "trough_like": f"{metrics['trough_like']:.9f}",
                                    }
                                )
                                metrics_suffix = (
                                    f" wave_bpm={metrics['bpm']:.1f}"
                                    f" peak_like={metrics['peak_like']:.3f} {waveform_spec.unit}"
                                    f" trough_like={metrics['trough_like']:.3f} {waveform_spec.unit}"
                                )
                        if recording_start_mono_ns is None and not args.allow_inactive:
                            if waveform_raw_values is not None and waveform_raw_values:
                                std_raw = statistics.pstdev(waveform_raw_values)
                                range_raw = max(waveform_raw_values) - min(waveform_raw_values)
                                print(
                                    f"[capture] waiting for active waveform "
                                    f"(std={std_raw:.2f}, range={range_raw:.2f})"
                                )
                            else:
                                print("[capture] waiting for active waveform")
                        elif is_active or args.allow_inactive:
                            elapsed_s = (
                                (monotonic_ns - recording_start_mono_ns) / 1_000_000_000.0
                                if recording_start_mono_ns is not None
                                else process_elapsed_s
                            )
                            print(
                                f"[capture] t={elapsed_s:.2f}s n={row_count} rate={effective_rate:.2f} Hz "
                                f"{format_latest(specs, raw_values)}{metrics_suffix}"
                            )
                        else:
                            print("[capture] recording paused: waveform inactive")
                        last_report = time.monotonic()

                    if args.duration > 0 and process_elapsed_s >= args.duration:
                        break
            except KeyboardInterrupt:
                print("[capture] interrupted by user", file=sys.stderr)
            finally:
                handle.flush()
                metrics_handle.flush()

    elapsed_s = (
        (time.monotonic_ns() - recording_start_mono_ns) / 1_000_000_000.0
        if recording_start_mono_ns is not None
        else 0.0
    )
    metadata = {
        "session_id": session_id,
        "process_started_at": process_started_at,
        "recording_started_at": recording_started_at,
        "stopped_at": iso_now(),
        "capture_mode": "single-sample-poll",
        "sample_rate_hz_requested": args.sample_rate_hz,
        "samples_polled": polled_count,
        "samples_captured": row_count,
        "elapsed_s": elapsed_s,
        "effective_rate_hz": row_count / max(elapsed_s, 1e-9) if row_count else 0.0,
        "channels": [asdict(spec) for spec in specs],
        "device": device_summary,
        "output_csv": str(output_path),
        "archive_output_csv": str(archive_output) if archive_output is not None else None,
        "notes": args.notes,
        "waveform_label": args.waveform_label,
        "latest_wave_metrics": latest_metrics,
    }
    write_json(meta_path, metadata)
    if archive_output is not None:
        copy_if_present(output_path, archive_output)
        copy_if_present(meta_path, archive_meta)
        copy_if_present(metrics_path, archive_metrics)
    print(
        f"[capture] wrote {row_count} rows to {output_path} "
        f"(effective_rate={metadata['effective_rate_hz']:.2f} Hz)"
    )
    if archive_output is not None:
        print(f"[capture] mirrored CSV/JSON to {archive_output.parent}")
    return 0


def run_beat(root: Path, args: argparse.Namespace) -> int:
    if abs(args.sample_rate_hz - 100.0) > 1e-9:
        raise ValueError("CNAP beat mode is fixed at 100 Hz; use --sample-rate-hz 100")

    activity_spec = ChannelSpec.parse(args.activity_channel)
    sys_spec = parse_optional_channel(args.sys_channel)
    mean_spec = parse_optional_channel(args.mean_channel)
    dia_spec = parse_optional_channel(args.dia_channel)
    hr_spec = parse_optional_channel(args.hr_channel)

    specs: list[ChannelSpec] = [activity_spec]
    for spec in (sys_spec, mean_spec, dia_spec, hr_spec):
        if spec is not None and all(existing.label != spec.label for existing in specs):
            specs.append(spec)

    session_id = args.session_id or time.strftime("cnap_%Y%m%d_%H%M%S", time.localtime())
    output_path = ensure_output_path(root, session_id, args.output, "beats")
    meta_path = metadata_path_for(output_path)
    archive_output = archive_output_path(root, session_id, "beats", args.archive_root, args.no_archive)
    archive_meta = metadata_path_for(archive_output) if archive_output is not None else None

    sample_history: deque[dict[str, Any]] = deque(
        maxlen=max(8, int(args.activity_window_s * args.sample_rate_hz) + 4)
    )
    raw_history: deque[int] = deque(maxlen=max(8, int(args.activity_window_s * args.sample_rate_hz)))
    last_peak_sample_index: int | None = None
    last_peak_elapsed_s: float | None = None
    beat_count = 0
    sample_count = 0
    last_report = time.monotonic()
    active_state = False
    started_at = iso_now()
    start_mono_ns = time.monotonic_ns()
    period_s = 1.0 / args.sample_rate_hz
    next_deadline = time.monotonic()

    fieldnames = [
        "経過時間",
        "現在時刻",
        "計測回数",
        "Beat Sys [mmHg]",
        "Beat Mean [mmHg]",
        "Beat Dia [mmHg]",
        "Beat HR [bpm]",
    ]

    with TUSBAdapio() as device:
        device_summary = asdict(device.summary())
        metadata = {
            "session_id": session_id,
            "started_at": started_at,
            "capture_mode": "beat",
            "sample_rate_hz": args.sample_rate_hz,
            "activity_channel": asdict(activity_spec),
            "sys_channel": asdict(sys_spec) if sys_spec is not None else None,
            "mean_channel": asdict(mean_spec) if mean_spec is not None else None,
            "dia_channel": asdict(dia_spec) if dia_spec is not None else None,
            "hr_channel": asdict(hr_spec) if hr_spec is not None else None,
            "device": device_summary,
            "output_csv": str(output_path),
            "archive_output_csv": str(archive_output) if archive_output is not None else None,
            "notes": args.notes,
            "activity_min_std_raw": args.activity_min_std_raw,
            "activity_min_range_raw": args.activity_min_range_raw,
            "min_beat_interval_s": args.min_beat_interval_s,
            "max_beat_interval_s": args.max_beat_interval_s,
        }
        write_json(meta_path, metadata)
        if archive_meta is not None:
            write_json(archive_meta, metadata)

        with output_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()

            try:
                while True:
                    now = time.monotonic()
                    if sample_history and now < next_deadline:
                        time.sleep(next_deadline - now)

                    monotonic_ns = time.monotonic_ns()
                    epoch_ns = time.time_ns()
                    raw_values = device.read_scan([spec.index for spec in specs])
                    elapsed_s = (monotonic_ns - start_mono_ns) / 1_000_000_000.0

                    raw_by_label = {spec.label: raw for spec, raw in zip(specs, raw_values)}
                    converted_by_label = {
                        spec.label: spec.convert(raw)
                        for spec, raw in zip(specs, raw_values)
                    }

                    activity_raw = raw_by_label[activity_spec.label]
                    sample_history.append(
                        {
                            "sample_index": sample_count,
                            "elapsed_s": elapsed_s,
                            "wall_time_iso": time.strftime(
                                "%Y-%m-%dT%H:%M:%S",
                                time.localtime(epoch_ns / 1_000_000_000),
                            ),
                            "activity_raw": activity_raw,
                            "raw_by_label": raw_by_label,
                            "converted_by_label": converted_by_label,
                        }
                    )
                    raw_history.append(activity_raw)
                    next_deadline += period_s
                    sample_count += 1

                    peak_sample = detect_stream_peak(
                        sample_history,
                        raw_history,
                        last_peak_sample_index=last_peak_sample_index,
                        min_interval_s=args.min_beat_interval_s,
                        sample_rate_hz=args.sample_rate_hz,
                        min_std_raw=args.activity_min_std_raw,
                        min_range_raw=args.activity_min_range_raw,
                    )

                    if peak_sample is not None:
                        if last_peak_elapsed_s is not None:
                            interval_s = peak_sample["elapsed_s"] - last_peak_elapsed_s
                            if args.min_beat_interval_s <= interval_s <= args.max_beat_interval_s:
                                interval_samples = samples_for_interval(
                                    sample_history,
                                    start_sample_index=last_peak_sample_index,
                                    end_sample_index=peak_sample["sample_index"],
                                )
                                activity_values = [
                                    sample["converted_by_label"][activity_spec.label]
                                    for sample in interval_samples
                                ]
                                derived_sys = max(activity_values) if activity_values else None
                                derived_mean = (
                                    statistics.fmean(activity_values) if activity_values else None
                                )
                                derived_dia = min(activity_values) if activity_values else None
                                derived_hr = 60.0 / interval_s if interval_s > 0 else None
                                beat_count += 1
                                sys_value = mean_value_for_spec(sys_spec, interval_samples)
                                mean_value = mean_value_for_spec(mean_spec, interval_samples)
                                dia_value = mean_value_for_spec(dia_spec, interval_samples)
                                hr_value = mean_value_for_spec(hr_spec, interval_samples)

                                writer.writerow(
                                    {
                                        "経過時間": f"{peak_sample['elapsed_s']:.9f}",
                                        "現在時刻": peak_sample["wall_time_iso"],
                                        "計測回数": beat_count,
                                        "Beat Sys [mmHg]": format_optional_value(
                                            sys_value if sys_value is not None else derived_sys
                                        ),
                                        "Beat Mean [mmHg]": format_optional_value(
                                            mean_value if mean_value is not None else derived_mean
                                        ),
                                        "Beat Dia [mmHg]": format_optional_value(
                                            dia_value if dia_value is not None else derived_dia
                                        ),
                                        "Beat HR [bpm]": format_optional_value(
                                            hr_value if hr_value is not None else derived_hr
                                        ),
                                    }
                                )
                                handle.flush()

                        last_peak_sample_index = peak_sample["sample_index"]
                        last_peak_elapsed_s = peak_sample["elapsed_s"]

                    if args.status_interval > 0 and time.monotonic() - last_report >= args.status_interval:
                        is_active = activity_window_is_valid(
                            list(raw_history),
                            min_std_raw=args.activity_min_std_raw,
                            min_range_raw=args.activity_min_range_raw,
                        )
                        if is_active != active_state:
                            state_label = "active" if is_active else "inactive"
                            print(f"[beat] waveform state -> {state_label}")
                            active_state = is_active
                        if is_active:
                            print(
                                f"[beat] t={elapsed_s:.2f}s beats={beat_count} "
                                f"{format_latest(specs, raw_values)}"
                            )
                        elif raw_history:
                            std_raw = statistics.pstdev(raw_history)
                            range_raw = max(raw_history) - min(raw_history)
                            print(
                                f"[beat] waiting for active waveform "
                                f"(std={std_raw:.2f}, range={range_raw:.2f})"
                            )
                        else:
                            print("[beat] waiting for active waveform")
                        last_report = time.monotonic()

                    if args.duration > 0 and elapsed_s >= args.duration:
                        break
            except KeyboardInterrupt:
                print("[beat] interrupted by user", file=sys.stderr)

    metadata = {
        "session_id": session_id,
        "started_at": started_at,
        "stopped_at": iso_now(),
        "capture_mode": "beat",
        "sample_rate_hz": args.sample_rate_hz,
        "samples_polled": sample_count,
        "beats_written": beat_count,
        "activity_channel": asdict(activity_spec),
        "sys_channel": asdict(sys_spec) if sys_spec is not None else None,
        "mean_channel": asdict(mean_spec) if mean_spec is not None else None,
        "dia_channel": asdict(dia_spec) if dia_spec is not None else None,
        "hr_channel": asdict(hr_spec) if hr_spec is not None else None,
        "device": device_summary,
        "output_csv": str(output_path),
        "archive_output_csv": str(archive_output) if archive_output is not None else None,
        "notes": args.notes,
    }
    write_json(meta_path, metadata)
    if archive_output is not None:
        copy_if_present(output_path, archive_output)
        copy_if_present(meta_path, archive_meta)

    print(f"[beat] wrote {beat_count} rows to {output_path}")
    if archive_output is not None:
        print(f"[beat] mirrored CSV/JSON to {archive_output.parent}")
    return 0


def run_buffered(root: Path, args: argparse.Namespace) -> int:
    specs = default_channels(args.channel)
    primary_spec = specs[0]
    session_id = args.session_id or time.strftime("cnap_%Y%m%d_%H%M%S", time.localtime())
    output_path = ensure_output_path(root, session_id, args.output, "buffered")
    meta_path = metadata_path_for(output_path)
    archive_output = archive_output_path(root, session_id, "buffered", args.archive_root, args.no_archive)
    archive_meta = metadata_path_for(archive_output) if archive_output is not None else None
    started_at = iso_now()

    with TUSBAdapio() as device:
        if args.mode == "digital":
            device.adc_digital_trigger(args.end_channel, args.buffer_size)
        else:
            device.adc_analog_trigger(
                args.end_channel,
                args.buffer_size,
                args.threshold,
                args.trigger_channel,
                falling_edge=args.mode == "analog-falling",
            )

        start_wait = time.monotonic()
        last_report = start_wait
        final_running = 1
        final_sampled_num = 0
        while time.monotonic() - start_wait <= args.timeout:
            running, sampled_num = device.adc_get_status()
            final_running = running
            final_sampled_num = sampled_num
            now = time.monotonic()
            if args.status_interval > 0 and now - last_report >= args.status_interval:
                print(f"[buffered] running={running} sampled_num={sampled_num}")
                last_report = now
            if not running and sampled_num >= args.buffer_size:
                break
            time.sleep(0.01)

        sample_count = min(final_sampled_num, args.buffer_size)
        if sample_count <= 0:
            raise RuntimeError("buffered capture finished with zero samples")

        values = device.adc_get_data(sample_count)
        metadata = {
            "session_id": session_id,
            "started_at": started_at,
            "stopped_at": iso_now(),
            "capture_mode": args.mode,
            "end_channel": args.end_channel,
            "buffer_size_requested": args.buffer_size,
            "buffer_size_read": sample_count,
            "threshold": args.threshold,
            "trigger_channel": args.trigger_channel,
            "device": asdict(device.summary()),
            "primary_channel": asdict(primary_spec),
            "output_csv": str(output_path),
            "archive_output_csv": str(archive_output) if archive_output is not None else None,
            "note": "For end-channel > 0 the returned values are device-order samples. Interleave is not decoded yet.",
        }
        write_json(meta_path, metadata)
        if archive_meta is not None:
            write_json(archive_meta, metadata)

    with output_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["session_id", "sample_index", f"{primary_spec.label}_raw", primary_spec.label],
        )
        writer.writeheader()
        for sample_index, raw_value in enumerate(values):
            writer.writerow(
                {
                    "session_id": session_id,
                    "sample_index": sample_index,
                    f"{primary_spec.label}_raw": raw_value,
                    primary_spec.label: f"{primary_spec.convert(raw_value):.9f}",
                }
            )

    if archive_output is not None:
        copy_if_present(output_path, archive_output)
        copy_if_present(meta_path, archive_meta)

    print(f"[buffered] wrote {sample_count} samples to {output_path}")
    if archive_output is not None:
        print(f"[buffered] mirrored CSV/JSON to {archive_output.parent}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parent

    try:
        if args.command == "info":
            return run_info(root, args)
        if args.command == "capture":
            return run_capture(root, args)
        if args.command == "beat":
            return run_beat(root, args)
        if args.command == "buffered":
            return run_buffered(root, args)
        parser.error(f"unsupported command: {args.command}")
        return 2
    except TUSBAdapioError as exc:
        print(f"USB error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
