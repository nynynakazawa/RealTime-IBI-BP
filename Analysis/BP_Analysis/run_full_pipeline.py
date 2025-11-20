#!/usr/bin/env python3
"""
血圧推定モデル向けの前処理〜学習パイプラインを一括実行するスクリプト

主な処理:
1. Smartphone/Training_Data 内の各 CSV を対象に、対応する CNAP beats データ
   (Analysis/Data/pdp/beats) を読み込み、最後の 60 秒分に正規化した上で
   アンチエイリアシングフィルタ（カットオフ15 Hz）を適用して折り返し雑音を除去し、
   ref_SBP / ref_DBP をタイムスタンプ同期で補完する
2. 加工済み CSV を結合して学習用データセットを作成
3. train_bp_models.py を呼び出して SBP / DBP の学習・評価を実行

使用例:
    python run_full_pipeline.py \
        --smartphone-dir /abs/path/Analysis/Data/Smartphone/Training_Data \
        --beats-dir /abs/path/Analysis/Data/pdp/beats \
        --output-csv /abs/path/Analysis/BP_Analysis/prepared_training_data.csv \
        --results-dir /abs/path/Analysis/BP_Analysis/results
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np
import pandas as pd
from scipy import signal


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_SMARTPHONE_DIR = BASE_DIR.parent / "Data" / "Smartphone" / "Training_Data"
DEFAULT_BEATS_DIR = BASE_DIR.parent / "Data" / "pdp" / "beats"
DEFAULT_OUTPUT_CSV = BASE_DIR / "prepared_training_data.csv"
DEFAULT_RESULTS_DIR = BASE_DIR / "results"
TRAIN_SCRIPT = BASE_DIR / "train_bp_models.py"

# サンプリング周波数の設定
ANDROID_SAMPLING_HZ = 30.0  # Androidアプリのサンプリング周波数（推定値）
ANTIALIAS_CUTOFF_HZ = 15.0  # アンチエイリアシングフィルタのカットオフ周波数（ナイキスト周波数）
RESAMPLE_RATE_HZ = 30.0  # beatsデータをリサンプリングする周波数


def extract_session_key(name: str) -> Optional[str]:
    """
    ファイル名から IT/NY + 数字のセッションキーを抽出する (例: IT1, NY3)
    """
    stem = Path(name).stem
    match = re.search(r"(IT|NY)\s*[-_]?\s*(\d+)", stem, flags=re.IGNORECASE)
    if not match:
        return None
    return f"{match.group(1).upper()}{match.group(2)}"


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
        print(f"    Warning: アンチエイリアシングフィルタの適用に失敗しました: {e}")
        return arr


def remove_outliers_from_beats(df: pd.DataFrame) -> pd.DataFrame:
    """
    連続血圧計データ（beats）から外れ値を除去
    
    Parameters:
    -----------
    df : pd.DataFrame
        adjusted_time_s, SBP, DBP を含むデータフレーム
    
    Returns:
    --------
    pd.DataFrame
        外れ値を除去したデータフレーム
    """
    df_clean = df.copy()
    initial_count = len(df_clean)
    
    # 注: 0の値は load_and_trim_beats 関数で既にNaNに変換されている
    
    # 1. 生理学的範囲チェック
    # SBP: 60-200 mmHg
    sbp_valid = (df_clean["SBP"] >= 60) & (df_clean["SBP"] <= 200)
    # DBP: 40-150 mmHg
    dbp_valid = (df_clean["DBP"] >= 40) & (df_clean["DBP"] <= 150)
    valid_range = sbp_valid & dbp_valid
    
    removed_physio = (~valid_range).sum()
    df_clean = df_clean[valid_range].copy()
    
    if removed_physio > 0:
        print(f"    beats: 生理学的範囲外を除去: {removed_physio} サンプル")
    
    # 2. 統計的外れ値検出（±3σ）
    if len(df_clean) >= 3:
        for col in ["SBP", "DBP"]:
            mean_val = df_clean[col].mean()
            std_val = df_clean[col].std()
            
            if std_val > 0:
                z_scores = np.abs((df_clean[col] - mean_val) / std_val)
                outlier_mask = z_scores > 3.0
                removed_stats = outlier_mask.sum()
                
                if removed_stats > 0:
                    df_clean.loc[outlier_mask, col] = np.nan
                    print(f"    beats: {col}の統計的外れ値（±3σ）を除去: {removed_stats} サンプル")
        
        df_clean = df_clean.dropna(subset=["SBP", "DBP"])
    
    # 3. 時系列的外れ値検出（前後の値との差が大きい）
    if len(df_clean) >= 3 and "adjusted_time_s" in df_clean.columns:
        df_clean = df_clean.sort_values("adjusted_time_s").reset_index(drop=True)
        removed_temporal = 0
        
        for col in ["SBP", "DBP"]:
            values = df_clean[col].values
            std_val = values.std()
            
            if std_val == 0:
                continue
            
            for i in range(1, len(df_clean) - 1):
                prev_val = values[i-1]
                curr_val = values[i]
                next_val = values[i+1]
                
                neighbor_mean = (prev_val + next_val) / 2.0
                diff = abs(curr_val - neighbor_mean)
                
                if diff > 3.0 * std_val:
                    df_clean.loc[df_clean.index[i], col] = np.nan
                    removed_temporal += 1
        
        if removed_temporal > 0:
            print(f"    beats: 時系列的外れ値（前後との差>3σ）を除去: {removed_temporal} サンプル")
            df_clean = df_clean.dropna(subset=["SBP", "DBP"])
    
    final_count = len(df_clean)
    removed_total = initial_count - final_count
    if removed_total > 0:
        print(f"    beats: 外れ値除去: {initial_count} → {final_count} サンプル ({removed_total} サンプル除去)")
    
    return df_clean

def load_and_trim_beats(beats_path: Path) -> pd.DataFrame:
    """
    CNAP beats ファイルを読み込み、最後の60秒のみ残して 0-60 秒に再マッピングし、外れ値を除去する
    """
    df = pd.read_csv(beats_path, sep=";", engine="python", dtype=str)
    df.columns = [col.strip().strip('"') for col in df.columns]
    if "Time [s]" not in df.columns:
        raise ValueError(f"'Time [s]' column not found in {beats_path}")

    df = df.dropna(subset=["Time [s]"]).copy()
    if df.empty:
        return pd.DataFrame(columns=["adjusted_time_s", "SBP", "DBP"])

    df["Time [s]"] = pd.to_numeric(df["Time [s]"], errors="coerce")
    df = df.dropna(subset=["Time [s]"])
    if df.empty:
        return pd.DataFrame(columns=["adjusted_time_s", "SBP", "DBP"])

    df = df.sort_values("Time [s]").reset_index(drop=True)
    max_time = df["Time [s]"].max()
    start_time = max_time - 60.0
    df = df[df["Time [s]"] >= start_time].copy()
    df["adjusted_time_s"] = df["Time [s]"] - start_time
    df.loc[df["adjusted_time_s"] < 0, "adjusted_time_s"] = 0.0
    df.loc[df["adjusted_time_s"] > 60, "adjusted_time_s"] = 60.0

    column_map = {
        "Beat Sys [mmHg]": "SBP",
        "Beat Dia [mmHg]": "DBP",
    }
    missing_cols = [col for col in column_map if col not in df.columns]
    if missing_cols:
        raise ValueError(f"Missing columns {missing_cols} in {beats_path}")

    df.rename(columns=column_map, inplace=True)
    df = df[["adjusted_time_s", "SBP", "DBP"]]
    df["SBP"] = pd.to_numeric(df["SBP"], errors="coerce")
    df["DBP"] = pd.to_numeric(df["DBP"], errors="coerce")
    
    # 0の値をNaNに変換（明らかに計測できていない値）
    zero_count_sbp = (df["SBP"] == 0).sum()
    zero_count_dbp = (df["DBP"] == 0).sum()
    if zero_count_sbp > 0 or zero_count_dbp > 0:
        print(f"    beats: 0の値を無効化: SBP={zero_count_sbp}, DBP={zero_count_dbp}")
        df.loc[df["SBP"] == 0, "SBP"] = np.nan
        df.loc[df["DBP"] == 0, "DBP"] = np.nan
    
    df = df.dropna(subset=["SBP", "DBP"])
    
    # 外れ値除去
    if len(df) > 0:
        df = remove_outliers_from_beats(df)
    
    return df


def interpolate_reference(android_times: np.ndarray, beats_df: pd.DataFrame) -> pd.DataFrame:
    """
    Android 側の経過時間 (秒) に合わせて beats データの SBP/DBP を線形補間
    補間前にアンチエイリアシングフィルタを適用して折り返し雑音を除去
    """
    if beats_df.empty:
        return pd.DataFrame({
            "ref_SBP": np.full_like(android_times, np.nan, dtype=float),
            "ref_DBP": np.full_like(android_times, np.nan, dtype=float),
        })

    ref = pd.DataFrame(index=np.arange(len(android_times)))
    beat_times = beats_df["adjusted_time_s"].to_numpy()
    sbp_values = beats_df["SBP"].to_numpy()
    dbp_values = beats_df["DBP"].to_numpy()

    # beatsデータを規則的なサンプリング（30Hz）にリサンプリング
    if len(beat_times) > 1:
        time_min = beat_times.min()
        time_max = beat_times.max()
        time_range = time_max - time_min
        
        if time_range > 0:
            # 規則的な時間軸を生成（30Hz）
            resampled_times = np.arange(time_min, time_max + 1.0 / RESAMPLE_RATE_HZ, 1.0 / RESAMPLE_RATE_HZ)
            
            # SBP/DBPを規則的なサンプリングにリサンプリング
            valid_sbp = np.isfinite(sbp_values)
            valid_dbp = np.isfinite(dbp_values)
            
            if valid_sbp.sum() >= 2:
                resampled_sbp = np.interp(resampled_times, beat_times[valid_sbp], sbp_values[valid_sbp])
            else:
                resampled_sbp = np.full_like(resampled_times, np.nan)
            
            if valid_dbp.sum() >= 2:
                resampled_dbp = np.interp(resampled_times, beat_times[valid_dbp], dbp_values[valid_dbp])
            else:
                resampled_dbp = np.full_like(resampled_times, np.nan)
            
            # アンチエイリアシングフィルタを適用（カットオフ15Hz）
            resampled_sbp = apply_antialiasing_filter(
                resampled_sbp,
                sampling_rate=RESAMPLE_RATE_HZ,
                cutoff_freq=ANTIALIAS_CUTOFF_HZ,
            )
            resampled_dbp = apply_antialiasing_filter(
                resampled_dbp,
                sampling_rate=RESAMPLE_RATE_HZ,
                cutoff_freq=ANTIALIAS_CUTOFF_HZ,
            )
            
            # Androidデータの時間軸に補間
            ref["ref_SBP"] = np.nan
            ref["ref_DBP"] = np.nan
            
            valid_mask = (
                np.isfinite(android_times)
                & (android_times >= resampled_times.min())
                & (android_times <= resampled_times.max())
            )
            
            if valid_mask.any():
                ref.loc[valid_mask, "ref_SBP"] = np.interp(
                    android_times[valid_mask],
                    resampled_times,
                    resampled_sbp,
                )
                ref.loc[valid_mask, "ref_DBP"] = np.interp(
                    android_times[valid_mask],
                    resampled_times,
                    resampled_dbp,
                )
        else:
            # 時間範囲が0の場合は元のデータをそのまま使用
            ref["ref_SBP"] = np.nan
            ref["ref_DBP"] = np.nan
            
            valid_mask = (
                np.isfinite(android_times)
                & (android_times >= beat_times.min())
                & (android_times <= beat_times.max())
            )
            
            if valid_mask.any():
                ref.loc[valid_mask, "ref_SBP"] = np.interp(
                    android_times[valid_mask],
                    beat_times,
                    sbp_values,
                )
                ref.loc[valid_mask, "ref_DBP"] = np.interp(
                    android_times[valid_mask],
                    beat_times,
                    dbp_values,
                )
    else:
        # サンプル数が1以下の場合は補間できない
        ref["ref_SBP"] = np.nan
        ref["ref_DBP"] = np.nan

    return ref


def process_training_file(
    csv_path: Path,
    beats_map: Dict[str, Path],
) -> pd.DataFrame:
    """
    1件の Training_Data CSV に対して参照値を埋め込み、加工後の DataFrame を返す
    """
    print(f"\nProcessing Android data: {csv_path.name}")
    df = pd.read_csv(csv_path)
    df.columns = [col.strip() for col in df.columns]

    if "経過時間_秒" not in df.columns:
        raise ValueError(f"'経過時間_秒' column missing in {csv_path}")

    df["経過時間_秒"] = pd.to_numeric(df["経過時間_秒"], errors="coerce")
    df = df.dropna(subset=["経過時間_秒"]).reset_index(drop=True)

    session_key = extract_session_key(csv_path.name)
    if session_key is None:
        raise ValueError(f"Failed to derive session key from {csv_path.name}")

    beats_path = beats_map.get(session_key)
    if beats_path is None:
        print(f"  ⚠ 対応する beats ファイルが見つかりません (key={session_key})")
        ref_df = interpolate_reference(df["経過時間_秒"].to_numpy(), pd.DataFrame())
    else:
        beats_df = load_and_trim_beats(beats_path)
        if beats_df.empty:
            print(f"  ⚠ beats データが空です: {beats_path.name}")
        else:
            print(f"  beats: {beats_path.name} (samples={len(beats_df)})")
        ref_df = interpolate_reference(df["経過時間_秒"].to_numpy(), beats_df)

    def drop_existing(prefix: str) -> None:
        drop_targets = [col for col in df.columns if col == prefix or col.startswith(f"{prefix}.")]
        if drop_targets:
            df.drop(columns=drop_targets, inplace=True)

    drop_existing("ref_SBP")
    drop_existing("ref_DBP")
    drop_existing("subject_id")
    df["ref_SBP"] = ref_df["ref_SBP"]
    df["ref_DBP"] = ref_df["ref_DBP"]

    # subject_id をセッションキーで置き換え
    df["subject_id"] = session_key

    # timestamp カラムがなければ ms 単位で生成
    if "timestamp" not in df.columns:
        df["timestamp"] = (df["経過時間_秒"] * 1000).round().astype("Int64")

    df.to_csv(csv_path, index=False)
    print(f"  → ref_SBP/ref_DBP を更新しました (有効サンプル: {df['ref_SBP'].notna().sum()})")
    df["source_file"] = csv_path.name
    return df


def collect_beats_files(beats_dir: Path) -> Dict[str, Path]:
    beats_map: Dict[str, Path] = {}
    for file_path in sorted(beats_dir.glob("*.csv")):
        key = extract_session_key(file_path.name)
        if key:
            beats_map[key] = file_path
    return beats_map


def run_training(
    data_csv: Path,
    results_dir: Path,
    split_strategy: str,
    n_splits: int,
    estimator: str,
    window_seconds: float,
    use_timeseries: bool = False,
) -> None:
    for target in ("SBP", "DBP"):
        cmd = [
            sys.executable,
            str(TRAIN_SCRIPT),
            "--data_csv",
            str(data_csv),
            "--output_dir",
            str(results_dir),
            "--split_strategy",
            split_strategy,
            "--n_splits",
            str(n_splits),
            "--target",
            target,
            "--estimator",
            estimator,
            "--window_seconds",
            str(window_seconds),
        ]
        # -t フラグが指定された場合は追加
        if use_timeseries:
            cmd.append("-t")
        print(f"\nRunning training for {target}: {' '.join(cmd)}")
        subprocess.run(cmd, check=True)


def main():
    parser = argparse.ArgumentParser(description="CNAP beats と Android データの自動統合 + 学習パイプライン")
    parser.add_argument("--smartphone-dir", type=Path, default=DEFAULT_SMARTPHONE_DIR,
                        help="Training_Data CSV を格納しているディレクトリ")
    parser.add_argument("--beats-dir", type=Path, default=DEFAULT_BEATS_DIR,
                        help="CNAP beats CSV を格納しているディレクトリ")
    parser.add_argument("--output-csv", type=Path, default=DEFAULT_OUTPUT_CSV,
                        help="結合後の出力CSVパス")
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR,
                        help="学習結果の出力ディレクトリ")
    parser.add_argument("--split-strategy", choices=["groupkfold", "timeseries"], default="groupkfold",
                        help="train_bp_models.py に渡すデータ分割戦略")
    parser.add_argument("-t", "--timeseries", action="store_true",
                        help="TimeSeriesSplitを使用する（--split-strategy timeseries と同等）")
    parser.add_argument("--n-splits", type=int, default=5, help="train_bp_models.py に渡す分割数")
    parser.add_argument("--estimator", choices=["ols", "ridge", "lasso", "enet", "huber", "nonneg_ols"],
                        default="ridge", help="使用する推定器")
    parser.add_argument("--window-seconds", type=float, default=0.0,
                        help="train_bp_models.py へ渡す時間窓幅（秒）。0以下で無効化（デフォルトはリアルタイム評価）")
    parser.add_argument("--skip-training", action="store_true",
                        help="参照値の付与とCSV生成のみ行い、学習はスキップする")

    args = parser.parse_args()
    
    # -t フラグが指定された場合は split_strategy を timeseries に設定
    if args.timeseries:
        args.split_strategy = "timeseries"

    smartphone_dir = args.smartphone_dir.resolve()
    beats_dir = args.beats_dir.resolve()
    output_csv = args.output_csv.resolve()
    results_dir = args.results_dir.resolve()

    if not smartphone_dir.exists():
        raise FileNotFoundError(f"スマートフォンデータディレクトリが存在しません: {smartphone_dir}")
    if not beats_dir.exists():
        raise FileNotFoundError(f"beats ディレクトリが存在しません: {beats_dir}")

    beats_map = collect_beats_files(beats_dir)
    if not beats_map:
        raise RuntimeError(f"beats ディレクトリに対象ファイルが見つかりません: {beats_dir}")

    processed_frames: List[pd.DataFrame] = []
    for csv_path in sorted(smartphone_dir.glob("*_Training_Data.csv")):
        processed_frames.append(process_training_file(csv_path, beats_map))

    if not processed_frames:
        raise RuntimeError(f"{smartphone_dir} に Training_Data CSV が見つかりません")

    combined_df = pd.concat(processed_frames, ignore_index=True)
    output_csv.parent.mkdir(parents=True, exist_ok=True)
    combined_df.to_csv(output_csv, index=False)
    print(f"\n★ 結合済みデータを出力しました: {output_csv} (行数: {len(combined_df)})")

    if not args.skip_training:
        try:
            import sklearn  # type: ignore  # noqa: F401
        except ImportError as exc:  # pragma: no cover - ランタイム依存確認
            raise RuntimeError(
                "scikit-learn がインストールされていないため学習処理を実行できません。\n"
                "pip install scikit-learn で依存関係を追加してください。"
            ) from exc
        results_dir.mkdir(parents=True, exist_ok=True)
        run_training(
            data_csv=output_csv,
            results_dir=results_dir,
            split_strategy=args.split_strategy,
            n_splits=args.n_splits,
            estimator=args.estimator,
            window_seconds=args.window_seconds,
            use_timeseries=args.timeseries,
        )
        print(f"\n★ 学習結果を {results_dir} に保存しました")
    else:
        print("\n★ --skip-training が指定されたため学習はスキップしました")


if __name__ == "__main__":
    main()


