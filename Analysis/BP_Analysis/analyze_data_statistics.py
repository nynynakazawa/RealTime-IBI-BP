#!/usr/bin/env python3
"""
学習・評価に使用した拍数と外れ値除去の統計を分析するスクリプト
"""

import pandas as pd
import numpy as np
from pathlib import Path

def count_beats_before_processing(beats_dir):
    """beatsファイルから元の拍数をカウント"""
    beats_dir = Path(beats_dir)
    total_beats = 0
    beats_by_file = {}
    
    for beats_file in sorted(beats_dir.glob("*_beats.csv")):
        try:
            df = pd.read_csv(beats_file, sep=";", engine="python", dtype=str)
            df.columns = [col.strip().strip('"') for col in df.columns]
            
            if "Beat Sys [mmHg]" not in df.columns or "Beat Dia [mmHg]" not in df.columns:
                continue
            
            df["Beat Sys [mmHg]"] = pd.to_numeric(df["Beat Sys [mmHg]"], errors="coerce")
            df["Beat Dia [mmHg]"] = pd.to_numeric(df["Beat Dia [mmHg]"], errors="coerce")
            
            # 0の値を除外
            valid_sbp = df["Beat Sys [mmHg]"].notna() & (df["Beat Sys [mmHg]"] != 0)
            valid_dbp = df["Beat Dia [mmHg]"].notna() & (df["Beat Dia [mmHg]"] != 0)
            valid_beats = (valid_sbp & valid_dbp).sum()
            
            total_beats += valid_beats
            beats_by_file[beats_file.name] = valid_beats
        except Exception as e:
            print(f"エラー: {beats_file.name}: {e}")
    
    return total_beats, beats_by_file

def count_outliers_removed(beats_dir):
    """外れ値除去の統計を計算"""
    beats_dir = Path(beats_dir)
    total_initial = 0
    total_after_physio = 0
    total_after_stats = 0
    total_after_temporal = 0
    total_final = 0
    
    details = []
    
    for beats_file in sorted(beats_dir.glob("*_beats.csv")):
        try:
            df = pd.read_csv(beats_file, sep=";", engine="python", dtype=str)
            df.columns = [col.strip().strip('"') for col in df.columns]
            
            if "Beat Sys [mmHg]" not in df.columns or "Beat Dia [mmHg]" not in df.columns:
                continue
            
            if "Time [s]" not in df.columns:
                continue
            
            df = df.dropna(subset=["Time [s]"]).copy()
            df["Time [s]"] = pd.to_numeric(df["Time [s]"], errors="coerce")
            df = df.dropna(subset=["Time [s]"])
            df = df.sort_values("Time [s]").reset_index(drop=True)
            
            # 最後の60秒のみ
            max_time = df["Time [s]"].max()
            start_time = max_time - 60.0
            df = df[df["Time [s]"] >= start_time].copy()
            
            df["Beat Sys [mmHg]"] = pd.to_numeric(df["Beat Sys [mmHg]"], errors="coerce")
            df["Beat Dia [mmHg]"] = pd.to_numeric(df["Beat Dia [mmHg]"], errors="coerce")
            
            # 0の値をNaNに変換
            df.loc[df["Beat Sys [mmHg]"] == 0, "Beat Sys [mmHg]"] = np.nan
            df.loc[df["Beat Dia [mmHg]"] == 0, "Beat Dia [mmHg]"] = np.nan
            
            initial_count = len(df)
            total_initial += initial_count
            
            # 生理学的範囲チェック
            sbp_valid = (df["Beat Sys [mmHg]"] >= 60) & (df["Beat Sys [mmHg]"] <= 200)
            dbp_valid = (df["Beat Dia [mmHg]"] >= 40) & (df["Beat Dia [mmHg]"] <= 150)
            valid_range = sbp_valid & dbp_valid
            removed_physio = (~valid_range).sum()
            df_after_physio = df[valid_range].copy()
            total_after_physio += len(df_after_physio)
            
            # 統計的外れ値（±3.5σ）
            removed_stats = 0
            if len(df_after_physio) >= 3:
                for col in ["Beat Sys [mmHg]", "Beat Dia [mmHg]"]:
                    mean_val = df_after_physio[col].mean()
                    std_val = df_after_physio[col].std()
                    if std_val > 0:
                        z_scores = np.abs((df_after_physio[col] - mean_val) / std_val)
                        outlier_mask = z_scores > 3.5
                        removed_stats += outlier_mask.sum()
                        df_after_physio.loc[outlier_mask, col] = np.nan
                df_after_physio = df_after_physio.dropna(subset=["Beat Sys [mmHg]", "Beat Dia [mmHg]"])
            total_after_stats += len(df_after_physio)
            
            # 時系列的外れ値
            removed_temporal = 0
            if len(df_after_physio) >= 3:
                df_after_physio = df_after_physio.sort_values("Time [s]").reset_index(drop=True)
                for col in ["Beat Sys [mmHg]", "Beat Dia [mmHg]"]:
                    values = df_after_physio[col].values
                    std_val = values.std()
                    if std_val == 0:
                        continue
                    for i in range(1, len(df_after_physio) - 1):
                        prev_val = values[i-1]
                        curr_val = values[i]
                        next_val = values[i+1]
                        neighbor_mean = (prev_val + next_val) / 2.0
                        diff = abs(curr_val - neighbor_mean)
                        if diff > 3.5 * std_val:
                            df_after_physio.loc[df_after_physio.index[i], col] = np.nan
                            removed_temporal += 1
                df_after_physio = df_after_physio.dropna(subset=["Beat Sys [mmHg]", "Beat Dia [mmHg]"])
            total_after_temporal += len(df_after_physio)
            
            final_count = len(df_after_physio)
            total_final += final_count
            
            if initial_count != final_count:
                details.append({
                    'file': beats_file.name,
                    'initial': initial_count,
                    'after_physio': len(df[valid_range]),
                    'after_stats': len(df_after_physio) + removed_temporal,
                    'final': final_count,
                    'removed_physio': removed_physio,
                    'removed_stats': removed_stats,
                    'removed_temporal': removed_temporal,
                    'total_removed': initial_count - final_count
                })
        except Exception as e:
            print(f"エラー: {beats_file.name}: {e}")
    
    return {
        'total_initial': total_initial,
        'total_after_physio': total_after_physio,
        'total_after_stats': total_after_stats,
        'total_after_temporal': total_after_temporal,
        'total_final': total_final,
        'details': details
    }

def main():
    beats_dir = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/pdp/beats")
    prepared_csv = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/BP_Analysis/prepared_training_data.csv")
    
    print("=" * 60)
    print("beatsファイルの元の拍数")
    print("=" * 60)
    total_beats, beats_by_file = count_beats_before_processing(beats_dir)
    print(f"総拍数（0を除く）: {total_beats}")
    
    print("\n" + "=" * 60)
    print("外れ値除去の統計")
    print("=" * 60)
    outlier_stats = count_outliers_removed(beats_dir)
    
    print(f"\n処理前の総拍数: {outlier_stats['total_initial']}")
    print(f"生理学的範囲チェック後: {outlier_stats['total_after_physio']} (除去: {outlier_stats['total_initial'] - outlier_stats['total_after_physio']})")
    print(f"統計的外れ値除去後: {outlier_stats['total_after_stats']} (追加除去: {outlier_stats['total_after_physio'] - outlier_stats['total_after_stats']})")
    print(f"時系列的外れ値除去後: {outlier_stats['total_after_temporal']} (追加除去: {outlier_stats['total_after_stats'] - outlier_stats['total_after_temporal']})")
    print(f"最終的な拍数: {outlier_stats['total_final']}")
    print(f"総除去数: {outlier_stats['total_initial'] - outlier_stats['total_final']}")
    print(f"除去率: {(outlier_stats['total_initial'] - outlier_stats['total_final']) / outlier_stats['total_initial'] * 100:.1f}%")
    
    print("\n" + "=" * 60)
    print("prepared_training_data.csvの統計")
    print("=" * 60)
    df = pd.read_csv(prepared_csv)
    print(f"総行数: {len(df)}")
    print(f"ref_SBPが有効な行数: {df['ref_SBP'].notna().sum()}")
    print(f"ref_DBPが有効な行数: {df['ref_DBP'].notna().sum()}")
    
    # 学習・評価に使用されたサンプル数
    valid_samples = df.dropna(subset=['ref_SBP', 'ref_DBP'])
    print(f"\n学習・評価に使用されたサンプル数（ref_SBPとref_DBPの両方が有効）: {len(valid_samples)}")
    
    print("\n" + "=" * 60)
    print("外れ値除去の詳細（除去があったファイルのみ）")
    print("=" * 60)
    for detail in outlier_stats['details']:
        print(f"\n{detail['file']}:")
        print(f"  処理前: {detail['initial']}拍")
        print(f"  生理学的範囲外除去: {detail['removed_physio']}拍")
        print(f"  統計的外れ値除去: {detail['removed_stats']}拍")
        print(f"  時系列的外れ値除去: {detail['removed_temporal']}拍")
        print(f"  最終: {detail['final']}拍 (除去: {detail['total_removed']}拍)")

if __name__ == "__main__":
    main()
