#!/usr/bin/env python3
from __future__ import annotations

import argparse
from collections import deque
import csv
import json
from dataclasses import asdict, dataclass
from datetime import datetime
import os
from pathlib import Path
import shutil
import statistics
import sys
import time
from typing import Any


DEFAULT_SAMPLE_RATE_HZ = 100.0
DEFAULT_ACTIVITY_WINDOW_S = 8.0
DEFAULT_ACTIVITY_MIN_STD_RAW = 3.0
DEFAULT_ACTIVITY_MIN_RANGE_RAW = 8.0
DEFAULT_INACTIVE_HOLD_S = 1.5
DEFAULT_MIN_BEAT_INTERVAL_S = 0.4
DEFAULT_MAX_BEAT_INTERVAL_S = 2.0

DEFAULT_BP_WAVE_INDEX = 2
DEFAULT_MAP_INDEX = 1
DEFAULT_CO_INDEX = 0

BP_SCALE_MMHG_PER_COUNT = 500.0 / 1023.0
CO_SCALE_LPM_PER_COUNT = 99.0 / 1023.0
CO_OFFSET_LPM = 1.0

TUSBAdapio = None
TUSBAdapioError = RuntimeError


@dataclass(frozen=True)
class ChannelSpec:
    index: int
    label: str
    unit: str
    scale: float
    offset: float = 0.0

    def convert(self, raw_value: int) -> float:
        return raw_value * self.scale + self.offset


def maybe_reexec_into_venv() -> None:
    root = Path(__file__).resolve().parent
    venv_python = root / ".venv" / "bin" / "python"
    if os.environ.get("CNAP_REALTIME_BOOTSTRAPPED") == "1":
        return
    if not venv_python.exists():
        return
    if Path(sys.executable).resolve() == venv_python.resolve():
        return
    env = os.environ.copy()
    env["CNAP_REALTIME_BOOTSTRAPPED"] = "1"
    os.execve(
        str(venv_python),
        [str(venv_python), str(Path(__file__).resolve()), *sys.argv[1:]],
        env,
    )


def ensure_tusb_runtime() -> None:
    global TUSBAdapio, TUSBAdapioError
    if TUSBAdapio is not None:
        return
    try:
        from tusb_adapio import TUSBAdapio as _TUSBAdapio, TUSBAdapioError as _TUSBAdapioError
    except ModuleNotFoundError as exc:
        if exc.name == "usb":
            maybe_reexec_into_venv()
            raise RuntimeError(
                "pyusb is not installed for this Python. "
                "Run `python3 -m venv .venv && . .venv/bin/activate && python -m pip install -r requirements.txt`."
            ) from exc
        raise
    TUSBAdapio = _TUSBAdapio
    TUSBAdapioError = _TUSBAdapioError


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "CNAP AUX realtime logger. "
            "Default wiring assumes BP waveform, MAP, CO are each divided by 1/2 "
            "before TUADAPIO input, with CNAP Analog Out Reference = -5V to 5V."
        ),
    )
    parser.add_argument("--session-id", default="")
    parser.add_argument("--duration", type=float, default=0.0)
    parser.add_argument("--status-interval", type=float, default=1.0)
    parser.add_argument("--notes", default="")
    parser.add_argument("--archive-root", type=Path, default=None)
    parser.add_argument("--no-archive", action="store_true")
    parser.add_argument("--bp-wave-index", type=int, default=DEFAULT_BP_WAVE_INDEX)
    parser.add_argument("--map-index", type=int, default=DEFAULT_MAP_INDEX)
    parser.add_argument("--co-index", type=int, default=DEFAULT_CO_INDEX)
    parser.add_argument("--activity-window-s", type=float, default=DEFAULT_ACTIVITY_WINDOW_S)
    parser.add_argument("--activity-min-std-raw", type=float, default=DEFAULT_ACTIVITY_MIN_STD_RAW)
    parser.add_argument("--activity-min-range-raw", type=float, default=DEFAULT_ACTIVITY_MIN_RANGE_RAW)
    parser.add_argument("--inactive-hold-s", type=float, default=DEFAULT_INACTIVE_HOLD_S)
    parser.add_argument("--min-beat-interval-s", type=float, default=DEFAULT_MIN_BEAT_INTERVAL_S)
    parser.add_argument("--max-beat-interval-s", type=float, default=DEFAULT_MAX_BEAT_INTERVAL_S)
    return parser


def repo_root_from(root: Path) -> Path:
    return root.parent


def default_archive_root(root: Path) -> Path:
    return repo_root_from(root) / "Analysis" / "Data" / "pdp" / "realtime_aux"


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


def iso_now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


def iso_from_epoch_ns(epoch_ns: int) -> str:
    return datetime.fromtimestamp(epoch_ns / 1_000_000_000.0).astimezone().isoformat(
        timespec="milliseconds"
    )


def active_elapsed_s(monotonic_ns: int, recording_start_mono_ns: int) -> float:
    return (monotonic_ns - recording_start_mono_ns) / 1_000_000_000.0


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


def waiting_message(raw_history: deque[int]) -> str:
    if raw_history:
        std_value = statistics.pstdev(raw_history)
        range_value = max(raw_history) - min(raw_history)
        return f"waiting for active waveform (std={std_value:.2f}, range={range_value:.2f})"
    return "waiting for active waveform"


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

    prev_prev = sample_history[-3]["bp_raw"]
    prev = sample_history[-2]["bp_raw"]
    current = sample_history[-1]["bp_raw"]
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


def format_optional_int(value: float | None) -> str:
    if value is None:
        return ""
    return str(int(round(value)))


def default_channels(args: argparse.Namespace) -> tuple[ChannelSpec, ChannelSpec, ChannelSpec]:
    bp_wave_channel = ChannelSpec(
        index=args.bp_wave_index,
        label="BP waveform",
        unit="mmHg",
        scale=BP_SCALE_MMHG_PER_COUNT,
    )
    map_channel = ChannelSpec(
        index=args.map_index,
        label="MAP",
        unit="mmHg",
        scale=BP_SCALE_MMHG_PER_COUNT,
    )
    co_channel = ChannelSpec(
        index=args.co_index,
        label="CO",
        unit="L/min",
        scale=CO_SCALE_LPM_PER_COUNT,
        offset=CO_OFFSET_LPM,
    )
    return bp_wave_channel, map_channel, co_channel


def probe_device() -> TUSBAdapio:
    ensure_tusb_runtime()
    device = TUSBAdapio()
    device.open()
    summary = device.summary()
    print(
        "[startup] AD converter detected: "
        f"product={summary.product} manufacturer={summary.manufacturer} "
        f"serial={summary.serial_number} bus={summary.bus} address={summary.address}"
    )
    return device


def run_logger(root: Path, args: argparse.Namespace, device: TUSBAdapio) -> int:
    bp_wave_channel, map_channel, co_channel = default_channels(args)
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
    inactive_candidate_start_ns: int | None = None
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
        "BP waveform Raw",
        "BP waveform [mmHg]",
        "MAP Raw",
        "MAP [mmHg]",
        "CO Raw",
        "CO [L/min]",
        "Beat Sys [mmHg]",
        "Beat Mean [mmHg]",
        "Beat Dia [mmHg]",
        "Beat HR [bpm]",
    ]

    device_summary = asdict(device.summary())
    metadata = {
        "session_id": session_id,
        "mode": "beat_logger",
        "started_at": started_at,
        "recording_started_at": recording_started_at,
        "sample_rate_hz": DEFAULT_SAMPLE_RATE_HZ,
        "bp_wave_channel": asdict(bp_wave_channel),
        "map_channel": asdict(map_channel),
        "co_channel": asdict(co_channel),
        "conversion_assumptions": {
            "analog_out_reference": "-5V to 5V",
            "divider_ratio": "10k / (10k + 10k) = 1/2",
            "bp_wave_and_map_formula": "mmHg = 500 * raw / 1023",
            "co_formula": "L/min = 1 + 99 * raw / 1023",
        },
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
            f"bp_wave=ch{bp_wave_channel.index} map=ch{map_channel.index} co=ch{co_channel.index}"
        )
        print(
            "[beat] formulas: "
            "BP/MAP[mmHg] = 500 * raw / 1023, "
            "CO[L/min] = 1 + 99 * raw / 1023"
        )

        try:
            while True:
                now = time.monotonic()
                if sample_count > 0 and now < next_deadline:
                    time.sleep(next_deadline - now)

                monotonic_ns = time.monotonic_ns()
                epoch_ns = time.time_ns()
                bp_raw, map_raw, co_raw = device.read_scan(
                    [bp_wave_channel.index, map_channel.index, co_channel.index]
                )
                bp_value = bp_wave_channel.convert(bp_raw)
                map_value = map_channel.convert(map_raw)
                co_value = co_channel.convert(co_raw)
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
                        "bp_raw": bp_raw,
                        "bp_value": bp_value,
                        "map_raw": map_raw,
                        "map_value": map_value,
                        "co_raw": co_raw,
                        "co_value": co_value,
                    }
                )
                raw_history.append(bp_raw)

                is_active = activity_window_is_valid(
                    list(raw_history),
                    min_std_raw=args.activity_min_std_raw,
                    min_range_raw=args.activity_min_range_raw,
                )
                if is_active and recording_start_mono_ns is None:
                    recording_start_mono_ns = monotonic_ns
                    recording_started_at = wall_time_iso
                    inactive_candidate_start_ns = None
                    sample_history.clear()
                    raw_history.clear()
                    sample_history.append(
                        {
                            "sample_index": sample_count,
                            "elapsed_s": 0.0,
                            "wall_time_iso": wall_time_iso,
                            "epoch_ns": epoch_ns,
                            "monotonic_ns": monotonic_ns,
                            "bp_raw": bp_raw,
                            "bp_value": bp_value,
                            "map_raw": map_raw,
                            "map_value": map_value,
                            "co_raw": co_raw,
                            "co_value": co_value,
                        }
                    )
                    raw_history.append(bp_raw)
                    last_peak_sample_index = None
                    last_peak_elapsed_s = None
                    beat_count = 0
                    print(f"[beat] active waveform detected; recording started at {recording_started_at}")
                if recording_start_mono_ns is not None:
                    if is_active:
                        inactive_candidate_start_ns = None
                    elif inactive_candidate_start_ns is None:
                        inactive_candidate_start_ns = monotonic_ns
                    elif (monotonic_ns - inactive_candidate_start_ns) / 1_000_000_000.0 >= args.inactive_hold_s:
                        recording_start_mono_ns = None
                        inactive_candidate_start_ns = None
                        sample_history.clear()
                        raw_history.clear()
                        sample_history.append(
                            {
                                "sample_index": sample_count,
                                "elapsed_s": 0.0,
                                "wall_time_iso": wall_time_iso,
                                "epoch_ns": epoch_ns,
                                "monotonic_ns": monotonic_ns,
                                "bp_raw": bp_raw,
                                "bp_value": bp_value,
                                "map_raw": map_raw,
                                "map_value": map_value,
                                "co_raw": co_raw,
                                "co_value": co_value,
                            }
                        )
                        raw_history.append(bp_raw)
                        last_peak_sample_index = None
                        last_peak_elapsed_s = None
                        print("[beat] waveform became inactive; waiting for active waveform")
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
                            bp_interval_values = [sample["bp_value"] for sample in interval_samples]
                            map_interval_values = [sample["map_value"] for sample in interval_samples]
                            co_interval_values = [sample["co_value"] for sample in interval_samples]
                            map_interval_raw = [sample["map_raw"] for sample in interval_samples]
                            co_interval_raw = [sample["co_raw"] for sample in interval_samples]

                            if bp_interval_values:
                                beat_count += 1
                                sys_value = max(bp_interval_values)
                                mean_value = statistics.fmean(bp_interval_values)
                                dia_value = min(bp_interval_values)
                                hr_value = 60.0 / interval_s if interval_s > 0 else None
                                map_value_for_row = (
                                    statistics.fmean(map_interval_values) if map_interval_values else None
                                )
                                co_value_for_row = (
                                    statistics.fmean(co_interval_values) if co_interval_values else None
                                )
                                row = {
                                    "開始時刻": started_at,
                                    "記録開始時刻": recording_started_at,
                                    "現在時刻": peak_sample["wall_time_iso"],
                                    "epoch_ns": peak_sample["epoch_ns"],
                                    "monotonic_ns": peak_sample["monotonic_ns"],
                                    "経過時間": f"{peak_sample['elapsed_s']:.9f}",
                                    "計測回数": beat_count,
                                    "BP waveform Raw": peak_sample["bp_raw"],
                                    "BP waveform [mmHg]": f"{peak_sample['bp_value']:.9f}",
                                    "MAP Raw": format_optional_int(
                                        statistics.fmean(map_interval_raw) if map_interval_raw else None
                                    ),
                                    "MAP [mmHg]": format_optional_value(map_value_for_row),
                                    "CO Raw": format_optional_int(
                                        statistics.fmean(co_interval_raw) if co_interval_raw else None
                                    ),
                                    "CO [L/min]": format_optional_value(co_value_for_row),
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
                                    f"BP={peak_sample['bp_value']:.3f} mmHg "
                                    f"MAP={row['MAP [mmHg]']} mmHg "
                                    f"CO={row['CO [L/min]']} L/min "
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
                            f"BP={bp_value:.3f} mmHg (raw={bp_raw}) "
                            f"MAP={map_value:.3f} mmHg (raw={map_raw}) "
                            f"CO={co_value:.3f} L/min (raw={co_raw})"
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
        "bp_wave_channel": asdict(bp_wave_channel),
        "map_channel": asdict(map_channel),
        "co_channel": asdict(co_channel),
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

    device: TUSBAdapio | None = None
    try:
        ensure_tusb_runtime()
        device = probe_device()
        return run_logger(root, args, device)
    except TUSBAdapioError as exc:
        print(f"[startup] AD converter not detected: {exc}", file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    finally:
        if device is not None:
            device.close()


if __name__ == "__main__":
    raise SystemExit(main())
