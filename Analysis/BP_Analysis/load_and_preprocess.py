"""
学習用CSVデータの読み込み・前処理スクリプト

連続血圧計のデータとAndroidアプリから出力されたCSVを統合し、
学習用のデータセットを準備します。

使用方法:
    python load_and_preprocess.py --android_csv <Android CSV> --reference_csv <連続血圧計CSV> --output <出力CSV>
"""

import pandas as pd
import numpy as np
import argparse
from datetime import datetime

def load_android_csv(csv_path):
    """
    Androidアプリから出力された学習用CSVを読み込む
    
    CSVの構造:
        timestamp, subject_id, ref_SBP, ref_DBP,
        M1_*, M2_*, M3_*, Timestamp_Formatted
    """
    df = pd.read_csv(csv_path)
    
    # timestampを数値型に変換
    if 'timestamp' in df.columns:
        df['timestamp'] = pd.to_numeric(df['timestamp'], errors='coerce')
    
    return df

def load_reference_csv(csv_path, timestamp_col='timestamp', sbp_col='SBP', dbp_col='DBP'):
    """
    連続血圧計のCSVを読み込む
    
    Parameters:
    -----------
    csv_path : str
        CSVファイルのパス
    timestamp_col : str
        タイムスタンプのカラム名
    sbp_col : str
        SBPのカラム名
    dbp_col : str
        DBPのカラム名
    """
    df = pd.read_csv(csv_path)
    
    # タイムスタンプを数値型に変換（必要に応じて）
    if timestamp_col in df.columns:
        # 文字列形式のタイムスタンプの場合は変換
        if df[timestamp_col].dtype == 'object':
            try:
                # ISO8601形式やその他の形式を試す
                df[timestamp_col] = pd.to_datetime(df[timestamp_col]).astype('int64') // 10**6  # msに変換
            except:
                # 数値として解釈を試す
                df[timestamp_col] = pd.to_numeric(df[timestamp_col], errors='coerce')
        else:
            df[timestamp_col] = pd.to_numeric(df[timestamp_col], errors='coerce')
    
    return df

def merge_data(android_df, reference_df, timestamp_col='timestamp', 
                sbp_col='SBP', dbp_col='DBP', time_window_ms=1000):
    """
    Androidデータと連続血圧計データを時刻でマージ
    
    Parameters:
    -----------
    android_df : pd.DataFrame
        Androidアプリのデータ
    reference_df : pd.DataFrame
        連続血圧計のデータ
    timestamp_col : str
        タイムスタンプのカラム名
    sbp_col : str
        SBPのカラム名
    dbp_col : str
        DBPのカラム名
    time_window_ms : int
        マッチングする時間窓（ミリ秒）
    """
    merged_df = android_df.copy()
    
    # 参照値のカラムを初期化
    merged_df['ref_SBP'] = np.nan
    merged_df['ref_DBP'] = np.nan
    
    # 各Androidデータポイントに対して最も近い参照値を探す
    for idx, row in android_df.iterrows():
        android_time = row[timestamp_col]
        
        # 時間窓内の参照データを検索
        time_diff = np.abs(reference_df[timestamp_col] - android_time)
        closest_idx = time_diff.idxmin()
        
        if time_diff[closest_idx] <= time_window_ms:
            merged_df.at[idx, 'ref_SBP'] = reference_df.at[closest_idx, sbp_col]
            merged_df.at[idx, 'ref_DBP'] = reference_df.at[closest_idx, dbp_col]
    
    return merged_df

def preprocess_data(df):
    """
    データの前処理
    
    - 異常値の除去
    - 欠損値の処理
    - 外れ値のWinsorize
    """
    df_processed = df.copy()
    
    # 血圧値の範囲チェック（生理学的に妥当な範囲）
    if 'ref_SBP' in df_processed.columns:
        df_processed = df_processed[(df_processed['ref_SBP'] >= 60) & (df_processed['ref_SBP'] <= 200)]
    if 'ref_DBP' in df_processed.columns:
        df_processed = df_processed[(df_processed['ref_DBP'] >= 40) & (df_processed['ref_DBP'] <= 150)]
    
    # 特徴量の異常値チェック（±3σ範囲外を除外）
    feature_cols = [col for col in df_processed.columns if col.startswith('M1_') or 
                    col.startswith('M2_') or col.startswith('M3_')]
    
    for col in feature_cols:
        if df_processed[col].dtype in [np.float64, np.int64]:
            mean = df_processed[col].mean()
            std = df_processed[col].std()
            if std > 0:
                df_processed = df_processed[
                    (df_processed[col] >= mean - 3*std) & 
                    (df_processed[col] <= mean + 3*std)
                ]
    
    return df_processed

def main():
    parser = argparse.ArgumentParser(description="学習用データの読み込み・前処理")
    parser.add_argument("--android_csv", type=str, required=True, 
                        help="Androidアプリから出力されたCSVファイルのパス")
    parser.add_argument("--reference_csv", type=str, 
                        help="連続血圧計のCSVファイルのパス（オプション）")
    parser.add_argument("--output", type=str, default="preprocessed_data.csv",
                        help="出力CSVファイルのパス")
    parser.add_argument("--time_window", type=int, default=1000,
                        help="マッチングする時間窓（ミリ秒）")
    parser.add_argument("--reference_timestamp_col", type=str, default="timestamp",
                        help="参照データのタイムスタンプカラム名")
    parser.add_argument("--reference_sbp_col", type=str, default="SBP",
                        help="参照データのSBPカラム名")
    parser.add_argument("--reference_dbp_col", type=str, default="DBP",
                        help="参照データのDBPカラム名")
    
    args = parser.parse_args()

    # Androidデータの読み込み
    print(f"Loading Android data from {args.android_csv}...")
    android_df = load_android_csv(args.android_csv)
    print(f"Loaded {len(android_df)} samples from Android app")

    # 連続血圧計データの読み込み（オプション）
    if args.reference_csv:
        print(f"Loading reference data from {args.reference_csv}...")
        reference_df = load_reference_csv(
            args.reference_csv, 
            args.reference_timestamp_col,
            args.reference_sbp_col,
            args.reference_dbp_col
        )
        print(f"Loaded {len(reference_df)} samples from reference device")
        
        # データのマージ
        print("Merging data...")
        merged_df = merge_data(
            android_df, reference_df,
            timestamp_col='timestamp',
            sbp_col=args.reference_sbp_col,
            dbp_col=args.reference_dbp_col,
            time_window_ms=args.time_window
        )
        
        # 参照値がマッチしたサンプル数
        matched_sbp = merged_df['ref_SBP'].notna().sum()
        matched_dbp = merged_df['ref_DBP'].notna().sum()
        print(f"Matched {matched_sbp} SBP samples and {matched_dbp} DBP samples")
    else:
        print("No reference CSV provided. Using Android data as-is.")
        merged_df = android_df

    # 前処理
    print("Preprocessing data...")
    processed_df = preprocess_data(merged_df)
    print(f"After preprocessing: {len(processed_df)} samples")

    # 保存
    processed_df.to_csv(args.output, index=False)
    print(f"Preprocessed data saved to {args.output}")

    # 統計情報の表示
    print("\n=== Data Statistics ===")
    print(f"Total samples: {len(processed_df)}")
    if 'ref_SBP' in processed_df.columns:
        valid_sbp = processed_df['ref_SBP'].notna().sum()
        print(f"Valid SBP samples: {valid_sbp}")
        if valid_sbp > 0:
            print(f"SBP range: {processed_df['ref_SBP'].min():.1f} - {processed_df['ref_SBP'].max():.1f}")
    if 'ref_DBP' in processed_df.columns:
        valid_dbp = processed_df['ref_DBP'].notna().sum()
        print(f"Valid DBP samples: {valid_dbp}")
        if valid_dbp > 0:
            print(f"DBP range: {processed_df['ref_DBP'].min():.1f} - {processed_df['ref_DBP'].max():.1f}")

if __name__ == "__main__":
    main()

