#!/usr/bin/env python3
"""
スマートフォン波形 (Green / SinWave) と指先リファレンス波形を比較し、
MAPE や MAE などの指標を算出するスクリプト。

主な処理:
    1. Finger データ (409.6 Hz) を読み込み、時間軸を生成して異常値を除去
    2. アンチエイリアシングフィルタ（カットオフ15 Hz）を適用して折り返し雑音を除去
    3. Smartphone Wave_Data (30 Hz) を読み込み、同様に異常値を除去
    4. 指先波形をスマートフォン側の時間軸上に線形補間して整列
    5. 2 系列の振幅レンジを共通の min-max でスケーリング
    6. MAPE / MAE / RMSE / Bias / 相関係数などを算出し、CSV / JSON に保存

使用例:
    python evaluate_waveforms.py \
        --finger-dir /abs/path/Analysis/Data/Finger \
        --smartphone-dir /abs/path/Analysis/Data/Smartphone/Wave_Data \
        --output-dir /abs/path/Analysis/Waveform_Analysis/results
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd
import matplotlib
from scipy import signal

matplotlib.use("Agg")
import matplotlib.pyplot as plt


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_FINGER_DIR = BASE_DIR.parent / "Data" / "Finger"
DEFAULT_SMARTPHONE_DIR = BASE_DIR.parent / "Data" / "Smartphone" / "Wave_Data"
DEFAULT_OUTPUT_DIR = BASE_DIR / "results"

FINGER_SAMPLING_HZ = 409.6
SMARTPHONE_SAMPLING_HZ = 30.0  # 参考値（ファイルには経過時間が記載）
ANTIALIAS_CUTOFF_HZ = 15.0  # アンチエイリアシングフィルタのカットオフ周波数（ナイキスト周波数）

CHANNELS = ("Green", "SinWave")
NORMALIZED_MIN = 0.0
NORMALIZED_MAX = 10.0
MAPE_DENOM_BASE = NORMALIZED_MAX - NORMALIZED_MIN  # 正規化スケール幅 (10)


@dataclass(frozen=True)
class WaveformPaths:
    key: str
    finger: Path
    smartphone: Path


def load_finger_wave(path: Path) -> pd.DataFrame:
    """
    指先波形データを読み込む。
    ファイルはタブ区切り (もしくは空白区切り) で 4 列想定。
    先頭 2 列を Green / SinWave の参照波形として扱う。
    """
    df = pd.read_csv(
        path,
        sep=r"[\s\t]+",
        header=None,
        engine="python",
        names=["Green_ref", "SinWave_ref", "c3", "c4"],
    )
    df = df[["Green_ref", "SinWave_ref"]].copy()
    df["time_s"] = np.arange(len(df)) / FINGER_SAMPLING_HZ
    return df


def load_smartphone_wave(path: Path) -> pd.DataFrame:
    """
    スマートフォン側の Wave_Data CSV を読み込む。
    """
    df = pd.read_csv(path)
    df.columns = [col.strip() for col in df.columns]

    time_col = "経過時間_秒"
    if time_col not in df.columns:
        raise ValueError(f"{time_col} カラムが存在しません: {path}")

    df[time_col] = pd.to_numeric(df[time_col], errors="coerce")
    df = df.dropna(subset=[time_col]).reset_index(drop=True)

    for ch in CHANNELS:
        if ch not in df.columns:
            raise ValueError(f"{ch} カラムが存在しません: {path}")
        df[ch] = pd.to_numeric(df[ch], errors="coerce")

    return df


def apply_antialiasing_filter(
    values: np.ndarray,
    sampling_rate: float,
    cutoff_freq: float = ANTIALIAS_CUTOFF_HZ,
    filter_order: int = 4,
) -> np.ndarray:
    """
    アンチエイリアシングフィルタ（ローパスフィルタ）を適用する。
    ダウンサンプリング前に高周波成分を除去して折り返し雑音を防ぐ。
    
    Parameters:
    -----------
    values : np.ndarray
        入力信号
    sampling_rate : float
        サンプリング周波数（Hz）
    cutoff_freq : float
        カットオフ周波数（Hz、デフォルト: 15.0 Hz）
    filter_order : int
        フィルタ次数（デフォルト: 4）
    
    Returns:
    --------
    np.ndarray
        フィルタ適用後の信号
    """
    arr = np.asarray(values, dtype=float).copy()
    valid_mask = np.isfinite(arr)
    
    if valid_mask.sum() < filter_order + 1:
        # サンプル数が少なすぎる場合はフィルタを適用できない
        return arr
    
    # NaNを一時的に補間してフィルタを適用
    if not valid_mask.all():
        series = pd.Series(arr)
        series = series.interpolate(method="linear", limit_direction="both")
        arr = series.to_numpy()
    
    try:
        # Butterworthローパスフィルタを設計
        nyquist = sampling_rate / 2.0
        if cutoff_freq >= nyquist:
            # カットオフ周波数がナイキスト周波数以上の場合、フィルタを適用しない
            return arr
        
        # 正規化カットオフ周波数（0-1の範囲）
        normalized_cutoff = cutoff_freq / nyquist
        
        # フィルタ係数を計算
        b, a = signal.butter(filter_order, normalized_cutoff, btype="low", analog=False)
        
        # フィルタを適用（前後方向で適用して位相歪みを最小化）
        filtered = signal.filtfilt(b, a, arr)
        
        # 元々NaNだった位置をNaNに戻す
        if not valid_mask.all():
            filtered[~valid_mask] = np.nan
        
        return filtered
    except Exception as e:
        # フィルタ適用に失敗した場合は元のデータを返す
        print(f"Warning: アンチエイリアシングフィルタの適用に失敗しました: {e}")
        return arr


def remove_outliers(values: np.ndarray, z_threshold: float = 3.5) -> np.ndarray:
    """
    修正 Z スコア (MAD ベース) を用いた外れ値除去。
    外れ値は NaN に置き換えた後、線形補間で補完する。
    """
    series = pd.Series(values, dtype="float64")
    if series.isna().all():
        return series.to_numpy()

    median = series.median()
    mad = np.median(np.abs(series - median))
    if mad == 0:
        return series.to_numpy()

    modified_z = 0.6745 * (series - median) / mad
    series.loc[np.abs(modified_z) > z_threshold] = np.nan
    series = series.interpolate(method="linear", limit_direction="both")
    return series.to_numpy()


def normalize_to_range(
    values: np.ndarray,
    lower: float = NORMALIZED_MIN,
    upper: float = NORMALIZED_MAX,
) -> np.ndarray:
    """
    入力配列を指定レンジ [lower, upper] に線形正規化する。
    有効値が1種類のみの場合は中央値 (lower + upper)/2 に揃える。
    """
    arr = np.asarray(values, dtype=float).copy()
    mask = np.isfinite(arr)
    if mask.sum() == 0:
        return arr

    min_val = float(arr[mask].min())
    max_val = float(arr[mask].max())
    if np.isclose(max_val, min_val):
        arr[mask] = lower + (upper - lower) / 2.0
        return arr

    scale = (upper - lower) / (max_val - min_val)
    arr[mask] = (arr[mask] - min_val) * scale + lower
    return arr


def align_scale_to_reference(ref: np.ndarray, target: np.ndarray) -> Tuple[np.ndarray, float, float]:
    """
    target 波形を ref 波形の振幅レンジに写像する。
    - ref / target の有限値レンジを計算し、target の最小値/最大値が ref の最小値/最大値に対応するよう線形変換。
    - target のレンジが 0 もしくは有限値が少ない場合は平均値合わせにフォールバック。
    """
    ref_mask = np.isfinite(ref)
    target_mask = np.isfinite(target)
    if ref_mask.sum() < 2 or target_mask.sum() < 2:
        return target, 1.0, 0.0

    ref_valid = ref[ref_mask]
    target_valid = target[target_mask]

    ref_min, ref_max = float(np.min(ref_valid)), float(np.max(ref_valid))
    target_min, target_max = float(np.min(target_valid)), float(np.max(target_valid))

    ref_range = ref_max - ref_min
    target_range = target_max - target_min

    aligned = target.copy()

    if np.isclose(ref_range, 0.0) or np.isclose(target_range, 0.0):
        # どちらかが定数なら平均を揃える
        ref_mean = float(np.mean(ref_valid))
        target_mean = float(np.mean(target_valid))
        offset = ref_mean - target_mean
        aligned = target + offset
        return aligned, 1.0, offset

    scale = ref_range / target_range
    offset = ref_min - scale * target_min
    aligned = target * scale + offset
    return aligned, float(scale), float(offset)


def interpolate_reference(
    ref_time: np.ndarray,
    ref_values: np.ndarray,
    target_time: np.ndarray,
) -> np.ndarray:
    """
    指先波形をスマートフォンの時間軸に線形補間する。
    """
    valid_mask = np.isfinite(ref_values)
    if valid_mask.sum() < 2:
        return np.full_like(target_time, np.nan, dtype=float)

    ref_time_valid = ref_time[valid_mask]
    ref_values_valid = ref_values[valid_mask]

    interp_values = np.interp(target_time, ref_time_valid, ref_values_valid)
    outside = (target_time < ref_time_valid.min()) | (target_time > ref_time_valid.max())
    interp_values[outside] = np.nan
    return interp_values


def compute_metrics(reference: np.ndarray, estimate: np.ndarray) -> Dict[str, float]:
    """
    共通の有限サンプルに対して各種指標を算出する。
    """
    mask = np.isfinite(reference) & np.isfinite(estimate)
    n_samples = int(mask.sum())
    total = len(reference)

    if n_samples == 0:
        return {
            "n_samples": 0,
            "coverage": 0.0,
            "mape": np.nan,
            "mae": np.nan,
            "rmse": np.nan,
            "bias": np.nan,
            "corr": np.nan,
        }

    ref = reference[mask]
    est = estimate[mask]
    diff = est - ref

    mae = float(np.mean(np.abs(diff)))
    rmse = float(np.sqrt(np.mean(diff ** 2)))
    bias = float(np.mean(diff))

    denom = MAPE_DENOM_BASE if MAPE_DENOM_BASE > 0 else 1.0
    mape = float(np.mean(np.abs(diff)) / denom * 100.0)

    if ref.std() == 0 or est.std() == 0:
        corr = np.nan
    else:
        corr = float(np.corrcoef(ref, est)[0, 1])

    return {
        "n_samples": n_samples,
        "coverage": n_samples / total if total else 0.0,
        "mape": mape,
        "mae": mae,
        "rmse": rmse,
        "bias": bias,
        "corr": corr,
    }


def save_scatter_plot(
    reference: np.ndarray,
    estimate: np.ndarray,
    channel: str,
    output_path: Path,
) -> None:
    mask = np.isfinite(reference) & np.isfinite(estimate)
    if mask.sum() == 0:
        return

    ref = reference[mask]
    est = estimate[mask]
    min_lim = float(min(ref.min(), est.min()))
    max_lim = float(max(ref.max(), est.max()))

    fig, ax = plt.subplots(figsize=(5, 5), dpi=150)
    ax.scatter(ref, est, s=8, alpha=0.4, edgecolors="none")
    ax.plot([min_lim, max_lim], [min_lim, max_lim], "r--", linewidth=1, label="y=x")
    
    # 回帰直線の計算と描画（緑色）
    if np.std(ref) > 1e-10 and np.std(est) > 1e-10:
        slope, intercept = np.polyfit(ref, est, 1)
        reg_x = np.array([min_lim, max_lim])
        reg_y = slope * reg_x + intercept
        ax.plot(reg_x, reg_y, color="#ff7f0e", linewidth=1.5, label=f"Regression (y={slope:.3f}x+{intercept:.3f})")
        
        # 相関係数も表示
        corr = np.corrcoef(ref, est)[0, 1]
        ax.text(0.05, 0.95, f"r = {corr:.3f}", transform=ax.transAxes,
                verticalalignment="top", bbox=dict(boxstyle="round", facecolor="wheat", alpha=0.5))
    else:
        # 定数の場合は平均値で水平線
        mean_est = float(np.mean(est))
        ax.axhline(mean_est, color="#ff7f0e", linewidth=1.5, linestyle="-", label=f"Mean = {mean_est:.3f}")
    
    ax.set_xlabel("Reference")
    ax.set_ylabel("Estimate (aligned)")
    ax.set_title(f"{channel} Reference vs Estimate")
    ax.set_xlim(min_lim, max_lim)
    ax.set_ylim(min_lim, max_lim)
    ax.grid(True, linestyle="--", alpha=0.3)
    ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout()
    fig.savefig(output_path, format="svg")
    # Save as PNG as well
    png_path = output_path.with_suffix(".png")
    fig.savefig(png_path, format="png", dpi=300)
    plt.close(fig)


def save_bland_altman_plot(
    reference: np.ndarray,
    estimate: np.ndarray,
    channel: str,
    output_path: Path,
) -> None:
    mask = np.isfinite(reference) & np.isfinite(estimate)
    if mask.sum() == 0:
        return

    ref = reference[mask]
    est = estimate[mask]
    mean_val = (ref + est) / 2.0
    diff = est - ref
    bias = float(np.mean(diff))
    sd = float(np.std(diff))

    fig, ax = plt.subplots(figsize=(6, 4), dpi=150)
    ax.scatter(mean_val, diff, s=8, alpha=0.4, edgecolors="none")
    ax.axhline(bias, color="r", linestyle="-", linewidth=1, label="Mean diff")
    ax.axhline(bias + 1.96 * sd, color="gray", linestyle="--", linewidth=1, label="±1.96 SD")
    ax.axhline(bias - 1.96 * sd, color="gray", linestyle="--", linewidth=1)
    ax.set_xlabel("(Reference + Estimate) / 2")
    ax.set_ylabel("Estimate - Reference")
    ax.set_title(f"{channel} Bland-Altman")
    ax.grid(True, linestyle="--", alpha=0.3)
    ax.legend(loc="best", fontsize=8)
    fig.tight_layout()
    fig.savefig(output_path, format="svg")
    # Save as PNG as well
    png_path = output_path.with_suffix(".png")
    fig.savefig(png_path, format="png", dpi=300)
    plt.close(fig)


def extract_key(name: str) -> str:
    """
    ファイル名から基準キー (例: W1, W3) を抽出する。
    ハイフン / アンダースコアで区切られた先頭トークンを使用。
    """
    stem = Path(name).stem
    for sep in ("-", "_", " "):
        if sep in stem:
            return stem.split(sep, 1)[0].upper()
    return stem.upper()


def build_pairs(finger_dir: Path, smartphone_dir: Path) -> List[WaveformPaths]:
    """
    Finger と Smartphone の各ファイルをキーで突き合わせる。
    """
    smartphone_map: Dict[str, Path] = {}
    for wave_path in smartphone_dir.glob("*_Wave_Data.csv"):
        smartphone_map[extract_key(wave_path.name)] = wave_path

    pairs: List[WaveformPaths] = []
    for finger_path in finger_dir.glob("*.txt"):
        key = extract_key(finger_path.name)
        phone_path = smartphone_map.get(key)
        if phone_path is None:
            print(f"⚠ {finger_path.name} に対応する Wave_Data が見つかりません (key={key})")
            continue
        pairs.append(WaveformPaths(key=key, finger=finger_path, smartphone=phone_path))

    return pairs


def evaluate_pair(paths: WaveformPaths) -> Tuple[Dict[str, Dict[str, float]], Dict[str, Dict[str, np.ndarray]]]:
    """
    1 セッション分の波形を評価し、チャンネル別の指標を返す。
    """
    print(f"\n=== {paths.key} ===")
    finger_df = load_finger_wave(paths.finger)
    phone_df = load_smartphone_wave(paths.smartphone)

    # 指先データの前処理：外れ値除去 → 正規化 → アンチエイリアシングフィルタ
    finger_df["Green_ref"] = normalize_to_range(
        remove_outliers(finger_df["Green_ref"].to_numpy())
    )
    finger_df["SinWave_ref"] = normalize_to_range(
        remove_outliers(finger_df["SinWave_ref"].to_numpy())
    )
    
    # アンチエイリアシングフィルタを適用（409.6 Hz → 30 Hzへのダウンサンプリング前に15 Hz以上の成分を除去）
    finger_df["Green_ref"] = apply_antialiasing_filter(
        finger_df["Green_ref"].to_numpy(),
        sampling_rate=FINGER_SAMPLING_HZ,
        cutoff_freq=ANTIALIAS_CUTOFF_HZ,
    )
    finger_df["SinWave_ref"] = apply_antialiasing_filter(
        finger_df["SinWave_ref"].to_numpy(),
        sampling_rate=FINGER_SAMPLING_HZ,
        cutoff_freq=ANTIALIAS_CUTOFF_HZ,
    )

    for ch in CHANNELS:
        phone_df[ch] = normalize_to_range(
            remove_outliers(phone_df[ch].to_numpy())
        )

    phone_time = phone_df["経過時間_秒"].to_numpy()
    results: Dict[str, Dict[str, float]] = {}
    aligned_series: Dict[str, Dict[str, np.ndarray]] = {}

    for ch in CHANNELS:
        ref_values = interpolate_reference(
            finger_df["time_s"].to_numpy(),
            finger_df[f"{ch}_ref"].to_numpy(),
            phone_time,
        )
        phone_values = phone_df[ch].to_numpy()
        phone_aligned, scale, offset = align_scale_to_reference(ref_values, phone_values)
        metrics = compute_metrics(ref_values, phone_aligned)
        results[ch] = {**metrics, "scale": scale, "offset": offset}
        aligned_series[ch] = {
            "ref": ref_values,
            "estimate": phone_aligned,
        }

        print(
            f"{ch}: "
            f"n={metrics['n_samples']}, "
            f"MAPE={metrics['mape']:.2f}% , "
            f"MAE={metrics['mae']:.4f}, "
            f"RMSE={metrics['rmse']:.4f}, "
            f"Bias={metrics['bias']:.4f}, "
            f"Corr={metrics['corr']:.3f}, "
            f"scale={scale:.3f}, offset={offset:.3f}"
        )

    return results, aligned_series


def main() -> None:
    parser = argparse.ArgumentParser(description="指先リファレンスとスマホ波形の比較評価")
    parser.add_argument("--finger-dir", type=Path, default=DEFAULT_FINGER_DIR,
                        help="指先 wav データ (txt) を格納したディレクトリ")
    parser.add_argument("--smartphone-dir", type=Path, default=DEFAULT_SMARTPHONE_DIR,
                        help="スマートフォン Wave_Data CSV を格納したディレクトリ")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR,
                        help="結果を保存するディレクトリ")

    args = parser.parse_args()

    finger_dir = args.finger_dir.resolve()
    smartphone_dir = args.smartphone_dir.resolve()
    output_dir = args.output_dir.resolve()

    if not finger_dir.exists():
        raise FileNotFoundError(f"Finger ディレクトリが存在しません: {finger_dir}")
    if not smartphone_dir.exists():
        raise FileNotFoundError(f"Smartphone Wave_Data ディレクトリが存在しません: {smartphone_dir}")

    pairs = build_pairs(finger_dir, smartphone_dir)
    if not pairs:
        raise RuntimeError("評価対象となるペアが見つかりませんでした。")

    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    plots_dir = output_dir / f"plots_{timestamp}"
    plots_dir.mkdir(parents=True, exist_ok=True)

    detail_dict: Dict[str, Dict[str, Dict[str, float]]] = {}
    aggregated_refs: Dict[str, List[np.ndarray]] = {ch: [] for ch in CHANNELS}
    aggregated_estimates: Dict[str, List[np.ndarray]] = {ch: [] for ch in CHANNELS}

    for paths in pairs:
        metrics, aligned = evaluate_pair(paths)
        detail_dict[paths.key] = metrics
        for ch in CHANNELS:
            aggregated_refs[ch].append(aligned[ch]["ref"])
            aggregated_estimates[ch].append(aligned[ch]["estimate"])

    summary_rows: List[Dict[str, object]] = []
    for ch in CHANNELS:
        if not aggregated_refs[ch]:
            continue
        ref_concat = np.concatenate(aggregated_refs[ch])
        est_concat = np.concatenate(aggregated_estimates[ch])
        metrics = compute_metrics(ref_concat, est_concat)
        summary_rows.append({"channel": ch, **metrics})
        print(
            f"\n### Aggregated {ch}: "
            f"n={metrics['n_samples']}, "
            f"MAPE={metrics['mape']:.2f}%, "
            f"MAE={metrics['mae']:.4f}, "
            f"RMSE={metrics['rmse']:.4f}, "
            f"Bias={metrics['bias']:.4f}, "
            f"Corr={metrics['corr']:.3f}"
        )
        save_scatter_plot(
            ref_concat,
            est_concat,
            ch,
            plots_dir / f"{ch}_scatter.svg",
        )
        save_bland_altman_plot(
            ref_concat,
            est_concat,
            ch,
            plots_dir / f"{ch}_bland_altman.svg",
        )

    summary_df = pd.DataFrame(summary_rows)
    summary_csv = output_dir / f"waveform_metrics_{timestamp}.csv"
    summary_df.to_csv(summary_csv, index=False)
    print(f"\n★ サマリーを保存しました: {summary_csv}")

    detail_json = output_dir / f"waveform_metrics_{timestamp}.json"
    with open(detail_json, "w", encoding="utf-8") as f:
        json.dump(detail_dict, f, ensure_ascii=False, indent=2)
    print(f"★ 詳細結果を保存しました: {detail_json}")


if __name__ == "__main__":
    main()

