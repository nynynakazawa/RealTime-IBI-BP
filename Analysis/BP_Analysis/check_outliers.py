#!/usr/bin/env python3
"""
beatsデータとprepared_training_data.csvの参照値に異常値が含まれていないか確認するスクリプト
"""

import pandas as pd
import numpy as np
from pathlib import Path

def check_beats_files(beats_dir):
    """beatsファイルの異常値をチェック"""
    beats_dir = Path(beats_dir)
    print("=" * 60)
    print("beatsファイルの異常値チェック")
    print("=" * 60)
    
    all_sbp = []
    all_dbp = []
    all_hr = []
    
    for beats_file in sorted(beats_dir.glob("*.csv")):
        try:
            df = pd.read_csv(beats_file, sep=";", engine="python", dtype=str)
            df.columns = [col.strip().strip('"') for col in df.columns]
            
            if "Beat Sys [mmHg]" not in df.columns or "Beat Dia [mmHg]" not in df.columns:
                continue
            
            df["Beat Sys [mmHg]"] = pd.to_numeric(df["Beat Sys [mmHg]"], errors="coerce")
            df["Beat Dia [mmHg]"] = pd.to_numeric(df["Beat Dia [mmHg]"], errors="coerce")
            if "Beat HR [bpm]" in df.columns:
                df["Beat HR [bpm]"] = pd.to_numeric(df["Beat HR [bpm]"], errors="coerce")
            
            sbp_values = df["Beat Sys [mmHg]"].dropna()
            dbp_values = df["Beat Dia [mmHg]"].dropna()
            hr_values = df["Beat HR [bpm]"].dropna() if "Beat HR [bpm]" in df.columns else pd.Series()
            
            # 異常値のチェック
            low_sbp = sbp_values[sbp_values < 60]
            high_sbp = sbp_values[sbp_values > 200]
            low_dbp = dbp_values[dbp_values < 40]
            high_dbp = dbp_values[dbp_values > 150]
            negative_hr = hr_values[hr_values < 0] if len(hr_values) > 0 else pd.Series()
            
            if len(low_sbp) > 0 or len(high_sbp) > 0 or len(low_dbp) > 0 or len(high_dbp) > 0 or len(negative_hr) > 0:
                print(f"\n{beats_file.name}:")
                if len(sbp_values) > 0:
                    print(f"  SBP: min={sbp_values.min():.1f}, max={sbp_values.max():.1f}, mean={sbp_values.mean():.1f}, count={len(sbp_values)}")
                    if len(low_sbp) > 0:
                        print(f"    ⚠ 低いSBP (<60): {sorted(low_sbp.unique())[:10]}")
                    if len(high_sbp) > 0:
                        print(f"    ⚠ 高いSBP (>200): {sorted(high_sbp.unique())}")
                if len(dbp_values) > 0:
                    print(f"  DBP: min={dbp_values.min():.1f}, max={dbp_values.max():.1f}, mean={dbp_values.mean():.1f}, count={len(dbp_values)}")
                    if len(low_dbp) > 0:
                        print(f"    ⚠ 低いDBP (<40): {sorted(low_dbp.unique())[:10]}")
                    if len(high_dbp) > 0:
                        print(f"    ⚠ 高いDBP (>150): {sorted(high_dbp.unique())}")
                if len(hr_values) > 0:
                    print(f"  HR: min={hr_values.min():.1f}, max={hr_values.max():.1f}, mean={hr_values.mean():.1f}, count={len(hr_values)}")
                    if len(negative_hr) > 0:
                        print(f"    ⚠ 負のHR: {sorted(negative_hr.unique())}")
            
            all_sbp.extend(sbp_values.tolist())
            all_dbp.extend(dbp_values.tolist())
            if len(hr_values) > 0:
                all_hr.extend(hr_values.tolist())
        except Exception as e:
            print(f"エラー: {beats_file.name}: {e}")
    
    print("\n" + "=" * 60)
    print("全体統計 (beatsファイル)")
    print("=" * 60)
    if len(all_sbp) > 0:
        print(f"SBP: min={min(all_sbp):.1f}, max={max(all_sbp):.1f}, mean={np.mean(all_sbp):.1f}, std={np.std(all_sbp):.1f}, count={len(all_sbp)}")
        print(f"  低い値 (<60): {sum(1 for x in all_sbp if x < 60)}個")
        print(f"  高い値 (>200): {sum(1 for x in all_sbp if x > 200)}個")
    if len(all_dbp) > 0:
        print(f"DBP: min={min(all_dbp):.1f}, max={max(all_dbp):.1f}, mean={np.mean(all_dbp):.1f}, std={np.std(all_dbp):.1f}, count={len(all_dbp)}")
        print(f"  低い値 (<40): {sum(1 for x in all_dbp if x < 40)}個")
        print(f"  高い値 (>150): {sum(1 for x in all_dbp if x > 150)}個")

def check_prepared_data(prepared_csv):
    """prepared_training_data.csvの参照値の異常値をチェック"""
    print("\n" + "=" * 60)
    print("prepared_training_data.csvの参照値チェック")
    print("=" * 60)
    
    df = pd.read_csv(prepared_csv)
    
    ref_sbp = df["ref_SBP"].dropna()
    ref_dbp = df["ref_DBP"].dropna()
    
    print(f"\n有効な参照値のサンプル数:")
    print(f"  ref_SBP: {len(ref_sbp)}/{len(df)}")
    print(f"  ref_DBP: {len(ref_dbp)}/{len(df)}")
    
    print(f"\nref_SBPの統計:")
    print(f"  min={ref_sbp.min():.1f}, max={ref_sbp.max():.1f}, mean={ref_sbp.mean():.1f}, std={ref_sbp.std():.1f}")
    low_sbp = ref_sbp[ref_sbp < 60]
    high_sbp = ref_sbp[ref_sbp > 200]
    if len(low_sbp) > 0:
        print(f"  ⚠ 低い値 (<60): {len(low_sbp)}個")
        print(f"     値: {sorted(low_sbp.unique())[:20]}")
    if len(high_sbp) > 0:
        print(f"  ⚠ 高い値 (>200): {len(high_sbp)}個")
        print(f"     値: {sorted(high_sbp.unique())}")
    
    print(f"\nref_DBPの統計:")
    print(f"  min={ref_dbp.min():.1f}, max={ref_dbp.max():.1f}, mean={ref_dbp.mean():.1f}, std={ref_dbp.std():.1f}")
    low_dbp = ref_dbp[ref_dbp < 40]
    high_dbp = ref_dbp[ref_dbp > 150]
    if len(low_dbp) > 0:
        print(f"  ⚠ 低い値 (<40): {len(low_dbp)}個")
        print(f"     値: {sorted(low_dbp.unique())[:20]}")
    if len(high_dbp) > 0:
        print(f"  ⚠ 高い値 (>150): {len(high_dbp)}個")
        print(f"     値: {sorted(high_dbp.unique())}")
    
    # 被験者ごとの統計
    print("\n" + "=" * 60)
    print("被験者ごとの参照値統計")
    print("=" * 60)
    for subject_id in sorted(df["subject_id"].unique()):
        subject_data = df[df["subject_id"] == subject_id]
        sbp_subj = subject_data["ref_SBP"].dropna()
        dbp_subj = subject_data["ref_DBP"].dropna()
        
        if len(sbp_subj) > 0:
            low_sbp_subj = sbp_subj[sbp_subj < 60]
            print(f"\n{subject_id}:")
            print(f"  ref_SBP: min={sbp_subj.min():.1f}, max={sbp_subj.max():.1f}, mean={sbp_subj.mean():.1f}, count={len(sbp_subj)}")
            if len(low_sbp_subj) > 0:
                print(f"    ⚠ 低い値 (<60): {sorted(low_sbp_subj.unique())[:10]}")
        if len(dbp_subj) > 0:
            low_dbp_subj = dbp_subj[dbp_subj < 40]
            print(f"  ref_DBP: min={dbp_subj.min():.1f}, max={dbp_subj.max():.1f}, mean={dbp_subj.mean():.1f}, count={len(dbp_subj)}")
            if len(low_dbp_subj) > 0:
                print(f"    ⚠ 低い値 (<40): {sorted(low_dbp_subj.unique())[:10]}")

def main():
    beats_dir = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/Data/pdp/beats")
    prepared_csv = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/BP_Analysis/prepared_training_data.csv")
    
    check_beats_files(beats_dir)
    check_prepared_data(prepared_csv)

if __name__ == "__main__":
    main()
