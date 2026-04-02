#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import deque
import csv
import json
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
import shutil
import statistics
import sys
import time
from typing import Any

from tusb_adapio import TUSBAdapio, TUSBAdapioError


DEFAULT_ACTIVITY_CHANNEL = "2:wave"
DEFAULT_SAMPLE_RATE_HZ = 100.0
DEFAULT_ACTIVITY_WINDOW_S = 8.0
DEFAULT_ACTIVITY_MIN_STD_RAW = 3.0
DEFAULT_ACTIVITY_MIN_RANGE_RAW = 8.0
DEFAULT_MIN_BEAT_INTERVAL_S = 0.4
DEFAULT_MAX_BEAT_INTERVAL_S = 2.0
DEFAULT_MAX_ALIGNMENT_OFFSET_S = 15.0


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
            raise ValueError("channel must be index[:label[:unit[:scale[:offset]]]]")
        index = int(parts[0])
        label = parts[1] if len(parts) >= 2 and parts[1] else f"ch{index}"
        unit = parts[2] if len(parts) >= 3 and parts[2] else "count"
        scale = float(parts[3]) if len(parts) >= 4 and parts[3] else 1.0
        offset = float(parts[4]) if len(parts) >= 5 and parts[4] else 0.0
        return cls(index=index, label=label, unit=unit, scale=scale, offset=offset)

    def convert(self, raw_value: int) -> float:
        return raw_value * self.scale + self.offset

    def raw_copy(self) -> "ChannelSpec":
        return ChannelSpec(index=self.index, label=self.label)

    def to_spec_string(self) -> str:
        return f"{self.index}:{self.label}:{self.unit}:{self.scale}:{self.offset}"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "CNAP AUX realtime logger. "
            "Default: calibrated beat logger. "
            "--proof: calibration proof capture or proof finalization."
        ),
    )
    parser.add_argument(
        "--proof",
        action="store_true",
        help=(
            "Calibration proof mode. If a pending proof session and an official "
            "waveform.csv are available, finalize calibration. Otherwise start a new proof capture."
        ),
    )
    parser.add_argument(
        "--waveform-csv",
        type=Path,
        default=None,
        help="Optional official CNAP waveform.csv used to finalize a pending proof session.",
    )
    parser.add_argument(
        "--activity-channel",
        default="",
        help=(
            "Override activity channel. Proof mode defaults to 2:wave. "
            "Normal mode defaults to the calibrated activity channel from calibration.json."
        ),
    )
    parser.add_argument("--session-id", default="")
    parser.add_argument("--duration", type=float, default=0.0)
    parser.add_argument("--status-interval", type=float, default=1.0)
    parser.add_argument("--notes", default="")
    parser.add_argument("--archive-root", type=Path, default=None)
    parser.add_argument("--no-archive", action="store_true")
    parser.add_argument("--config", type=Path, default=None, help=argparse.SUPPRESS)
    parser.add_argument("--activity-window-s", type=float, default=DEFAULT_ACTIVITY_WINDOW_S)
    parser.add_argument("--activity-min-std-raw", type=float, default=DEFAULT_ACTIVITY_MIN_STD_RAW)
    parser.add_argument("--activity-min-range-raw", type=float, default=DEFAULT_ACTIVITY_MIN_RANGE_RAW)
    parser.add_argument("--min-beat-interval-s", type=float, default=DEFAULT_MIN_BEAT_INTERVAL_S)
    parser.add_argument("--max-beat-interval-s", type=float, default=DEFAULT_MAX_BEAT_INTERVAL_S)
    parser.add_argument("--max-offset-s", type=float, default=DEFAULT_MAX_ALIGNMENT_OFFSET_S)
    return parser


def repo_root_from(root: Path) -> Path:
    return root.parent


def default_archive_root(root: Path) -> Path:
    return repo_root_from(root) / "Analysis" / "Data" / "pdp" / "realtime_aux"


def config_path_for(root: Path, explicit: Path | None) -> Path:
    return explicit.expanduser().resolve() if explicit is not None else root / "calibration.json"


def proof_state_path_for(root: Path) -> Path:
    return root / "proof_state.json"


def imports_root_for(root: Path) -> Path:
    return root / "imports"


def ensure_output_path(root: Path, session_id: str, suffix: str) -> Path:
    path = root / "captures" / session_id / f"{session_id}_{suffix}.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def metadata_path_for(output_path: Path) -> Path:
    return output_path.with_suffix(".json")


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


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def iso_now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def iso_from_epoch_ns(epoch_ns: int) -> str:
    return datetime.fromtimestamp(epoch_ns / 1_000_000_000.0).astimezone().isoformat(
        timespec="milliseconds"
    )


def activity_window_is_valid(
    raw_values: list[int],
    *,
    min_std_raw: float,
    min_range_raw: float,
) -> bool:
    if len(raw_values) < 8:
        return False
    std_value = statistics.pstdev(raw_values)
    range_value = max(raw_values) - min(raw_values)
    return std_value >= min_std_raw and range_value >= min_range_raw


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


def format_optional_value(value: float | None) -> str:
    if value is None:
        return ""
    return f"{value:.9f}"


def waiting_message(raw_history: deque[int]) -> str:
    if raw_history:
        std_value = statistics.pstdev(raw_history)
        range_value = max(raw_history) - min(raw_history)
        return f"waiting for active waveform (std={std_value:.2f}, range={range_value:.2f})"
    return "waiting for active waveform"


def load_proof_series(path: Path) -> list[float]:
    with path.open("r", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise ValueError(f"no rows found in {path}")
    if "Activity Raw" not in rows[0]:
        raise KeyError(f"Activity Raw not found in {path}")
    return [float(row["Activity Raw"]) for row in rows]


def load_cnap_waveform(path: Path) -> list[float]:
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        rows = list(csv.DictReader(handle, delimiter=";"))
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


def fit_linear(x_values: list[float], y_values: list[float]) -> dict[str, float]:
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
    rmse = statistics.fmean((pred - truth) ** 2 for pred, truth in zip(predictions, y_values)) ** 0.5
    corr_value = cov / (var_x * var_y) ** 0.5
    return {
        "scale": scale,
        "offset": offset,
        "corr": corr_value,
        "mae": mae,
        "rmse": rmse,
    }


def summarize_calibration(
    aux_values: list[float],
    cnap_values: list[float],
    *,
    sample_rate_hz: float,
    max_offset_s: float,
) -> dict[str, float]:
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
            "offset_samples": float(best_offset_samples),
            "offset_seconds": best_offset_samples / sample_rate_hz,
            "alignment_corr": best_corr if best_corr is not None else 0.0,
            "paired_samples": float(len(best_aux_overlap)),
        }
    )
    return fit


def discover_waveform_csv(root: Path, explicit_path: Path | None) -> Path | None:
    if explicit_path is not None:
        return explicit_path.expanduser().resolve()

    imports_root = imports_root_for(root)
    if not imports_root.exists():
        return None
    candidates = [path for path in imports_root.rglob("*waveform.csv") if path.is_file()]
    if not candidates:
        return None
    return max(candidates, key=lambda candidate: candidate.stat().st_mtime_ns)


def try_finalize_proof(root: Path, args: argparse.Namespace) -> bool:
    state_path = proof_state_path_for(root)
    if not state_path.exists():
        return False

    waveform_csv = discover_waveform_csv(root, args.waveform_csv)
    if waveform_csv is None:
        return False

    state = read_json(state_path)
    proof_csv = Path(state["proof_csv"]).expanduser().resolve()
    if not proof_csv.exists():
        raise FileNotFoundError(f"proof csv not found: {proof_csv}")

    aux_values = load_proof_series(proof_csv)
    cnap_values = load_cnap_waveform(waveform_csv)
    summary = summarize_calibration(
        aux_values,
        cnap_values,
        sample_rate_hz=DEFAULT_SAMPLE_RATE_HZ,
        max_offset_s=args.max_offset_s,
    )

    channel = ChannelSpec.parse(state["activity_channel_spec"])
    calibrated_channel = ChannelSpec(
        index=channel.index,
        label=channel.label,
        unit="mmHg",
        scale=summary["scale"],
        offset=summary["offset"],
    )
    config_path = config_path_for(root, args.config)
    config = {
        "created_at": iso_now(),
        "proof_session_id": state["session_id"],
        "proof_csv": str(proof_csv),
        "waveform_csv": str(waveform_csv),
        "activity_channel_spec": calibrated_channel.to_spec_string(),
        "scale": summary["scale"],
        "offset": summary["offset"],
        "alignment_corr": summary["alignment_corr"],
        "offset_seconds": summary["offset_seconds"],
        "offset_samples": int(summary["offset_samples"]),
        "corr": summary["corr"],
        "mae": summary["mae"],
        "rmse": summary["rmse"],
        "paired_samples": int(summary["paired_samples"]),
    }
    write_json(config_path, config)
    state_path.unlink(missing_ok=True)

    print(f"[proof] calibration saved to {config_path}")
    print(f"[proof] waveform_csv={waveform_csv}")
    print(
        f"[proof] activity_channel_spec={config['activity_channel_spec']} "
        f"alignment_corr={config['alignment_corr']:.4f} rmse={config['rmse']:.4f}"
    )
    return True


def active_elapsed_s(monotonic_ns: int, recording_start_mono_ns: int) -> float:
    return (monotonic_ns - recording_start_mono_ns) / 1_000_000_000.0


def run_proof_capture(root: Path, args: argparse.Namespace) -> int:
    raw_activity_channel = ChannelSpec.parse(args.activity_channel or DEFAULT_ACTIVITY_CHANNEL).raw_copy()
    session_id = args.session_id or time.strftime("cnap_%Y%m%d_%H%M%S", time.localtime())
    output_path = ensure_output_path(root, session_id, "proof")
    meta_path = metadata_path_for(output_path)
    archive_output = archive_output_path(root, session_id, "proof", args.archive_root, args.no_archive)
    archive_meta = metadata_path_for(archive_output) if archive_output is not None else None

    process_started_at = iso_now()
    process_start_mono_ns = time.monotonic_ns()
    history_limit = max(8, int(args.activity_window_s * DEFAULT_SAMPLE_RATE_HZ))
    raw_history: deque[int] = deque(maxlen=history_limit)
    recording_start_mono_ns: int | None = None
    recording_started_at: str | None = None
    row_count = 0
    polled_count = 0
    last_report = time.monotonic()
    next_deadline = time.monotonic()
    active_state = False

    fieldnames = [
        "開始時刻",
        "記録開始時刻",
        "現在時刻",
        "epoch_ns",
        "monotonic_ns",
        "sample_index",
        "経過時間",
        "Activity Label",
        "Activity Unit",
        "Activity Raw",
        "Activity Value",
    ]

    with TUSBAdapio() as device:
        device_summary = asdict(device.summary())
        metadata = {
            "session_id": session_id,
            "mode": "proof_capture",
            "process_started_at": process_started_at,
            "recording_started_at": recording_started_at,
            "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
            "activity_channel": asdict(raw_activity_channel),
            "output_csv": str(output_path),
            "archive_output_csv": str(archive_output) if archive_output is not None else None,
            "device": device_summary,
            "notes": args.notes,
        }
        write_json(meta_path, metadata)
        if archive_meta is not None:
            write_json(archive_meta, metadata)

        with output_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            print(
                f"[proof] session started_at={process_started_at} "
                f"activity_channel={raw_activity_channel.index}:{raw_activity_channel.label}"
            )

            try:
                while True:
                    now = time.monotonic()
                    if polled_count > 0 and now < next_deadline:
                        time.sleep(next_deadline - now)

                    monotonic_ns = time.monotonic_ns()
                    epoch_ns = time.time_ns()
                    raw_value = device.read_scan([raw_activity_channel.index])[0]
                    wall_time_iso = iso_from_epoch_ns(epoch_ns)
                    raw_history.append(raw_value)

                    is_active = activity_window_is_valid(
                        list(raw_history),
                        min_std_raw=args.activity_min_std_raw,
                        min_range_raw=args.activity_min_range_raw,
                    )
                    if is_active and recording_start_mono_ns is None:
                        recording_start_mono_ns = monotonic_ns
                        recording_started_at = wall_time_iso
                        print(f"[proof] active waveform detected; recording started at {recording_started_at}")
                    if is_active != active_state:
                        state_label = "active" if is_active else "inactive"
                        print(f"[proof] waveform state -> {state_label}")
                        active_state = is_active

                    if recording_start_mono_ns is not None and is_active:
                        row = {
                            "開始時刻": process_started_at,
                            "記録開始時刻": recording_started_at,
                            "現在時刻": wall_time_iso,
                            "epoch_ns": epoch_ns,
                            "monotonic_ns": monotonic_ns,
                            "sample_index": row_count,
                            "経過時間": f"{active_elapsed_s(monotonic_ns, recording_start_mono_ns):.9f}",
                            "Activity Label": raw_activity_channel.label,
                            "Activity Unit": raw_activity_channel.unit,
                            "Activity Raw": raw_value,
                            "Activity Value": f"{raw_activity_channel.convert(raw_value):.9f}",
                        }
                        writer.writerow(row)
                        row_count += 1

                    polled_count += 1
                    next_deadline += 1.0 / DEFAULT_SAMPLE_RATE_HZ

                    if polled_count % int(DEFAULT_SAMPLE_RATE_HZ) == 0:
                        handle.flush()

                    if args.status_interval > 0 and time.monotonic() - last_report >= args.status_interval:
                        if recording_start_mono_ns is None:
                            print(f"[proof] {waiting_message(raw_history)}")
                        else:
                            elapsed_s = active_elapsed_s(monotonic_ns, recording_start_mono_ns)
                            print(
                                f"[proof] t={elapsed_s:.2f}s n={row_count} "
                                f"{raw_activity_channel.label}={raw_value:.3f} {raw_activity_channel.unit} "
                                f"(raw={raw_value})"
                            )
                        last_report = time.monotonic()

                    if args.duration > 0:
                        process_elapsed_s = (monotonic_ns - process_start_mono_ns) / 1_000_000_000.0
                        if process_elapsed_s >= args.duration:
                            break
            except KeyboardInterrupt:
                print("[proof] interrupted by user", file=sys.stderr)

    elapsed_s = (
        active_elapsed_s(time.monotonic_ns(), recording_start_mono_ns)
        if recording_start_mono_ns is not None
        else 0.0
    )
    metadata = {
        "session_id": session_id,
        "mode": "proof_capture",
        "process_started_at": process_started_at,
        "recording_started_at": recording_started_at,
        "stopped_at": iso_now(),
        "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
        "samples_polled": polled_count,
        "samples_captured": row_count,
        "elapsed_s": elapsed_s,
        "activity_channel": asdict(raw_activity_channel),
        "output_csv": str(output_path),
        "archive_output_csv": str(archive_output) if archive_output is not None else None,
        "device": device_summary,
        "notes": args.notes,
    }
    write_json(meta_path, metadata)
    if archive_output is not None:
        copy_if_present(output_path, archive_output)
        copy_if_present(meta_path, archive_meta)

    print(f"[proof] wrote {row_count} rows to {output_path}")
    if archive_output is not None:
        print(f"[proof] mirrored CSV/JSON to {archive_output.parent}")

    if row_count > 0:
        proof_state = {
            "created_at": iso_now(),
            "session_id": session_id,
            "proof_csv": str(output_path),
            "activity_channel_spec": raw_activity_channel.to_spec_string(),
            "recording_started_at": recording_started_at,
            "samples_captured": row_count,
        }
        state_path = proof_state_path_for(root)
        write_json(state_path, proof_state)
        imports_root = imports_root_for(root)
        imports_root.mkdir(parents=True, exist_ok=True)
        print(f"[proof] pending proof saved to {state_path}")
        print(
            f"[proof] copy the official CNAP waveform.csv into {imports_root} "
            f"and then run `python realtime_capture.py --proof` again"
        )
    else:
        proof_state_path_for(root).unlink(missing_ok=True)

    return 0


def resolve_activity_channel(root: Path, args: argparse.Namespace) -> tuple[ChannelSpec, dict[str, Any]]:
    if args.activity_channel:
        channel = ChannelSpec.parse(args.activity_channel)
        return channel, {
            "source": "command_line",
            "activity_channel_spec": channel.to_spec_string(),
        }

    config_path = config_path_for(root, args.config)
    if not config_path.exists():
        raise FileNotFoundError(
            f"calibration not found: {config_path}. Run `python realtime_capture.py --proof` first."
        )
    config = read_json(config_path)
    if "activity_channel_spec" not in config:
        raise KeyError(f"activity_channel_spec not found in {config_path}")
    return ChannelSpec.parse(config["activity_channel_spec"]), config


def run_beat_logger(root: Path, args: argparse.Namespace) -> int:
    activity_channel, calibration = resolve_activity_channel(root, args)
    session_id = args.session_id or time.strftime("cnap_%Y%m%d_%H%M%S", time.localtime())
    output_path = ensure_output_path(root, session_id, "beats")
    meta_path = metadata_path_for(output_path)
    archive_output = archive_output_path(root, session_id, "beats", args.archive_root, args.no_archive)
    archive_meta = metadata_path_for(archive_output) if archive_output is not None else None

    started_at = iso_now()
    sample_history: deque[dict[str, Any]] = deque(
        maxlen=max(8, int(args.activity_window_s * DEFAULT_SAMPLE_RATE_HZ) + 4)
    )
    raw_history: deque[int] = deque(maxlen=max(8, int(args.activity_window_s * DEFAULT_SAMPLE_RATE_HZ)))
    recording_start_mono_ns: int | None = None
    recording_started_at: str | None = None
    last_peak_sample_index: int | None = None
    last_peak_elapsed_s: float | None = None
    beat_count = 0
    sample_count = 0
    last_report = time.monotonic()
    next_deadline = time.monotonic()
    active_state = False
    process_start_mono_ns = time.monotonic_ns()

    fieldnames = [
        "開始時刻",
        "記録開始時刻",
        "現在時刻",
        "epoch_ns",
        "monotonic_ns",
        "経過時間",
        "計測回数",
        "Activity Label",
        "Activity Unit",
        "Activity Raw",
        "Activity Value",
        "Beat Sys [mmHg]",
        "Beat Mean [mmHg]",
        "Beat Dia [mmHg]",
        "Beat HR [bpm]",
    ]

    with TUSBAdapio() as device:
        device_summary = asdict(device.summary())
        metadata = {
            "session_id": session_id,
            "mode": "beat_logger",
            "started_at": started_at,
            "recording_started_at": recording_started_at,
            "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
            "activity_channel": asdict(activity_channel),
            "calibration": calibration,
            "output_csv": str(output_path),
            "archive_output_csv": str(archive_output) if archive_output is not None else None,
            "device": device_summary,
            "notes": args.notes,
        }
        write_json(meta_path, metadata)
        if archive_meta is not None:
            write_json(archive_meta, metadata)

        with output_path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            print(
                f"[beat] session started_at={started_at} "
                f"activity_channel={activity_channel.index}:{activity_channel.label} "
                f"unit={activity_channel.unit}"
            )

            try:
                while True:
                    now = time.monotonic()
                    if sample_count > 0 and now < next_deadline:
                        time.sleep(next_deadline - now)

                    monotonic_ns = time.monotonic_ns()
                    epoch_ns = time.time_ns()
                    raw_value = device.read_scan([activity_channel.index])[0]
                    converted_value = activity_channel.convert(raw_value)
                    wall_time_iso = iso_from_epoch_ns(epoch_ns)

                    sample_history.append(
                        {
                            "sample_index": sample_count,
                            "elapsed_s": (
                                active_elapsed_s(monotonic_ns, recording_start_mono_ns)
                                if recording_start_mono_ns is not None
                                else 0.0
                            ),
                            "wall_time_iso": wall_time_iso,
                            "epoch_ns": epoch_ns,
                            "monotonic_ns": monotonic_ns,
                            "activity_raw": raw_value,
                            "activity_value": converted_value,
                        }
                    )
                    raw_history.append(raw_value)

                    is_active = activity_window_is_valid(
                        list(raw_history),
                        min_std_raw=args.activity_min_std_raw,
                        min_range_raw=args.activity_min_range_raw,
                    )
                    if is_active and recording_start_mono_ns is None:
                        recording_start_mono_ns = monotonic_ns
                        recording_started_at = wall_time_iso
                        sample_history.clear()
                        raw_history.clear()
                        sample_history.append(
                            {
                                "sample_index": sample_count,
                                "elapsed_s": 0.0,
                                "wall_time_iso": wall_time_iso,
                                "epoch_ns": epoch_ns,
                                "monotonic_ns": monotonic_ns,
                                "activity_raw": raw_value,
                                "activity_value": converted_value,
                            }
                        )
                        raw_history.append(raw_value)
                        last_peak_sample_index = None
                        last_peak_elapsed_s = None
                        beat_count = 0
                        print(f"[beat] active waveform detected; recording started at {recording_started_at}")
                    if is_active != active_state:
                        state_label = "active" if is_active else "inactive"
                        print(f"[beat] waveform state -> {state_label}")
                        active_state = is_active

                    if recording_start_mono_ns is not None:
                        sample_history[-1]["elapsed_s"] = active_elapsed_s(monotonic_ns, recording_start_mono_ns)

                    peak_sample = detect_stream_peak(
                        sample_history,
                        raw_history,
                        last_peak_sample_index=last_peak_sample_index,
                        min_interval_s=args.min_beat_interval_s,
                        sample_rate_hz=DEFAULT_SAMPLE_RATE_HZ,
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
                                interval_values = [sample["activity_value"] for sample in interval_samples]
                                beat_count += 1
                                sys_value = max(interval_values) if interval_values else None
                                mean_value = statistics.fmean(interval_values) if interval_values else None
                                dia_value = min(interval_values) if interval_values else None
                                hr_value = 60.0 / interval_s if interval_s > 0 else None
                                row = {
                                    "開始時刻": started_at,
                                    "記録開始時刻": recording_started_at,
                                    "現在時刻": peak_sample["wall_time_iso"],
                                    "epoch_ns": peak_sample["epoch_ns"],
                                    "monotonic_ns": peak_sample["monotonic_ns"],
                                    "経過時間": f"{peak_sample['elapsed_s']:.9f}",
                                    "計測回数": beat_count,
                                    "Activity Label": activity_channel.label,
                                    "Activity Unit": activity_channel.unit,
                                    "Activity Raw": peak_sample["activity_raw"],
                                    "Activity Value": f"{peak_sample['activity_value']:.9f}",
                                    "Beat Sys [mmHg]": format_optional_value(sys_value),
                                    "Beat Mean [mmHg]": format_optional_value(mean_value),
                                    "Beat Dia [mmHg]": format_optional_value(dia_value),
                                    "Beat HR [bpm]": format_optional_value(hr_value),
                                }
                                writer.writerow(row)
                                handle.flush()
                                print(
                                    f"[beat] start={started_at} now={peak_sample['wall_time_iso']} "
                                    f"t={peak_sample['elapsed_s']:.2f}s #{beat_count} "
                                    f"{activity_channel.label}={peak_sample['activity_value']:.3f} {activity_channel.unit} "
                                    f"(raw={peak_sample['activity_raw']}) "
                                    f"Sys={row['Beat Sys [mmHg]']} "
                                    f"Mean={row['Beat Mean [mmHg]']} "
                                    f"Dia={row['Beat Dia [mmHg]']} "
                                    f"HR={row['Beat HR [bpm]']}"
                                )
                        last_peak_sample_index = peak_sample["sample_index"]
                        last_peak_elapsed_s = peak_sample["elapsed_s"]

                    sample_count += 1
                    next_deadline += 1.0 / DEFAULT_SAMPLE_RATE_HZ

                    if args.status_interval > 0 and time.monotonic() - last_report >= args.status_interval:
                        if recording_start_mono_ns is None:
                            print(f"[beat] {waiting_message(raw_history)}")
                        else:
                            elapsed_s = active_elapsed_s(monotonic_ns, recording_start_mono_ns)
                            print(
                                f"[beat] t={elapsed_s:.2f}s beats={beat_count} "
                                f"{activity_channel.label}={converted_value:.3f} {activity_channel.unit} "
                                f"(raw={raw_value})"
                            )
                        last_report = time.monotonic()

                    if args.duration > 0:
                        process_elapsed_s = (
                            active_elapsed_s(monotonic_ns, recording_start_mono_ns)
                            if recording_start_mono_ns is not None
                            else (monotonic_ns - process_start_mono_ns) / 1_000_000_000.0
                        )
                        if process_elapsed_s >= args.duration:
                            break
            except KeyboardInterrupt:
                print("[beat] interrupted by user", file=sys.stderr)

    metadata = {
        "session_id": session_id,
        "mode": "beat_logger",
        "started_at": started_at,
        "recording_started_at": recording_started_at,
        "stopped_at": iso_now(),
        "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
        "samples_polled": sample_count,
        "beats_written": beat_count,
        "activity_channel": asdict(activity_channel),
        "calibration": calibration,
        "output_csv": str(output_path),
        "archive_output_csv": str(archive_output) if archive_output is not None else None,
        "device": device_summary,
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


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parent

    try:
        if args.proof:
            state_path = proof_state_path_for(root)
            if try_finalize_proof(root, args):
                return 0
            if args.waveform_csv is not None and not state_path.exists():
                raise FileNotFoundError(
                    f"pending proof not found: {state_path}. "
                    f"Run `python realtime_capture.py --proof` first."
                )
            if state_path.exists():
                raise RuntimeError(
                    f"pending proof exists: {state_path}. "
                    f"Copy the official waveform.csv into {imports_root_for(root)} and rerun `python realtime_capture.py --proof`."
                )
            return run_proof_capture(root, args)
        return run_beat_logger(root, args)
    except TUSBAdapioError as exc:
        print(f"USB error: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
