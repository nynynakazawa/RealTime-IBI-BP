#!/usr/bin/env python3
"""
血圧推定モデル向けの前処理〜学習パイプラインを一括実行するスクリプト

主な処理:
1. Smartphone/Training_Data 内の各 CSV を対象に、対応する CNAP beats データ
   (Analysis/Data/pdp/beats) を読み込み、最後の 60 秒分に正規化した上で
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


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_SMARTPHONE_DIR = BASE_DIR.parent / "Data" / "Smartphone" / "Training_Data"
DEFAULT_BEATS_DIR = BASE_DIR.parent / "Data" / "pdp" / "beats"
DEFAULT_OUTPUT_CSV = BASE_DIR / "prepared_training_data.csv"
DEFAULT_RESULTS_DIR = BASE_DIR / "results"
TRAIN_SCRIPT = BASE_DIR / "train_bp_models.py"


def extract_session_key(name: str) -> Optional[str]:
    """
    ファイル名から IT/NY + 数字のセッションキーを抽出する (例: IT1, NY3)
    """
    stem = Path(name).stem
    match = re.search(r"(IT|NY)\s*[-_]?\s*(\d+)", stem, flags=re.IGNORECASE)
    if not match:
        return None
    return f"{match.group(1).upper()}{match.group(2)}"


def load_and_trim_beats(beats_path: Path) -> pd.DataFrame:
    """
    CNAP beats ファイルを読み込み、最後の60秒のみ残して 0-60 秒に再マッピングする
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
    df = df.dropna(subset=["SBP", "DBP"])
    return df


def interpolate_reference(android_times: np.ndarray, beats_df: pd.DataFrame) -> pd.DataFrame:
    """
    Android 側の経過時間 (秒) に合わせて beats データの SBP/DBP を線形補間
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
        ]
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
    parser.add_argument("--n-splits", type=int, default=5, help="train_bp_models.py に渡す分割数")
    parser.add_argument("--estimator", choices=["ols", "ridge", "lasso", "enet", "huber", "nonneg_ols"],
                        default="ridge", help="使用する推定器")
    parser.add_argument("--skip-training", action="store_true",
                        help="参照値の付与とCSV生成のみ行い、学習はスキップする")

    args = parser.parse_args()

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
        )
        print(f"\n★ 学習結果を {results_dir} に保存しました")
    else:
        print("\n★ --skip-training が指定されたため学習はスキップしました")


if __name__ == "__main__":
    main()


