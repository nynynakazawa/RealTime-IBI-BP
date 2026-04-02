#!/usr/bin/env python3
"""
被験者ごとにRTBP、SINBP(M)、SINBP(D)のMAPEを計算し、
改善が一貫しているかを分析するスクリプト
"""

import numpy as np
import pandas as pd
from pathlib import Path

def mape(y_true, y_pred):
    """Mean Absolute Percentage Error"""
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    mask = y_true > 0
    if np.sum(mask) == 0:
        return np.inf
    return np.mean(np.abs((y_pred[mask] - y_true[mask]) / y_true[mask])) * 100.0

def analyze_by_subject(data_csv_path, target='SBP'):
    """
    被験者ごとに各手法のMAPEを計算
    
    Parameters:
    -----------
    data_csv_path : str
        データCSVファイルのパス
    target : str
        評価対象（'SBP' または 'DBP'）
    """
    # データ読み込み
    print(f"データを読み込み中: {data_csv_path}")
    df = pd.read_csv(data_csv_path)
    
    # 参照値のカラム名
    ref_col = f"ref_{target}"
    if ref_col not in df.columns:
        print(f"エラー: {ref_col} カラムが見つかりません")
        return None
    
    # 参照値が欠損している行を除外
    df_valid = df.dropna(subset=[ref_col]).copy()
    print(f"有効なサンプル数: {len(df_valid)}")
    
    # 被験者IDの確認
    if 'subject_id' not in df_valid.columns:
        print("エラー: subject_id カラムが見つかりません")
        return None
    
    subjects = df_valid['subject_id'].unique()
    print(f"被験者数: {len(subjects)}")
    print(f"被験者ID: {sorted(subjects)}")
    
    # 各手法の推定値カラム
    methods = {
        'RealTimeBP': f'M1_{target}',
        'SinBP_D': f'M2_{target}',
        'SinBP_M': f'M3_{target}'
    }
    
    # 被験者ごとの結果を格納
    results = []
    
    for subject_id in sorted(subjects):
        subject_data = df_valid[df_valid['subject_id'] == subject_id].copy()
        
        if len(subject_data) == 0:
            continue
        
        subject_result = {'subject_id': subject_id, 'n_samples': len(subject_data)}
        
        # 各手法のMAPEを計算
        for method_name, pred_col in methods.items():
            if pred_col not in subject_data.columns:
                print(f"警告: {subject_id} に {pred_col} が存在しません")
                subject_result[f'{method_name}_MAPE'] = np.nan
                continue
            
            y_true = subject_data[ref_col].values
            y_pred = subject_data[pred_col].values
            
            # 欠損値を除外
            mask = ~(np.isnan(y_true) | np.isnan(y_pred))
            if mask.sum() == 0:
                subject_result[f'{method_name}_MAPE'] = np.nan
                continue
            
            y_true_valid = y_true[mask]
            y_pred_valid = y_pred[mask]
            
            mape_val = mape(y_true_valid, y_pred_valid)
            subject_result[f'{method_name}_MAPE'] = mape_val
        
        results.append(subject_result)
    
    # DataFrameに変換
    results_df = pd.DataFrame(results)
    
    # 改善の有無を判定
    if 'RealTimeBP_MAPE' in results_df.columns and 'SinBP_M_MAPE' in results_df.columns:
        results_df['SinBP_M_improvement'] = results_df['RealTimeBP_MAPE'] - results_df['SinBP_M_MAPE']
        results_df['SinBP_M_better'] = results_df['SinBP_M_improvement'] > 0
    
    if 'RealTimeBP_MAPE' in results_df.columns and 'SinBP_D_MAPE' in results_df.columns:
        results_df['SinBP_D_improvement'] = results_df['RealTimeBP_MAPE'] - results_df['SinBP_D_MAPE']
        results_df['SinBP_D_better'] = results_df['SinBP_D_improvement'] > 0
    
    return results_df

def main():
    data_csv = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/BP_Analysis/prepared_training_data.csv")
    
    print("=" * 60)
    print("SBPの分析")
    print("=" * 60)
    results_sbp = analyze_by_subject(data_csv, target='SBP')
    
    if results_sbp is not None:
        print("\n被験者ごとのMAPE (SBP):")
        print(results_sbp.to_string(index=False))
        
        print("\n" + "=" * 60)
        print("SBP改善の統計:")
        print("=" * 60)
        if 'SinBP_M_better' in results_sbp.columns:
            m_improved = results_sbp['SinBP_M_better'].sum()
            m_total = results_sbp['SinBP_M_better'].notna().sum()
            print(f"SinBP(M)がRTBPより良い被験者: {m_improved}/{m_total} ({m_improved/m_total*100:.1f}%)")
        
        if 'SinBP_D_better' in results_sbp.columns:
            d_improved = results_sbp['SinBP_D_better'].sum()
            d_total = results_sbp['SinBP_D_better'].notna().sum()
            print(f"SinBP(D)がRTBPより良い被験者: {d_improved}/{d_total} ({d_improved/d_total*100:.1f}%)")
        
        # 改善量の統計
        if 'SinBP_M_improvement' in results_sbp.columns:
            valid_improvements = results_sbp['SinBP_M_improvement'].dropna()
            if len(valid_improvements) > 0:
                print(f"\nSinBP(M)の改善量 (MAPE減少):")
                print(f"  平均: {valid_improvements.mean():.2f}%")
                print(f"  中央値: {valid_improvements.median():.2f}%")
                print(f"  最小: {valid_improvements.min():.2f}%")
                print(f"  最大: {valid_improvements.max():.2f}%")
        
        if 'SinBP_D_improvement' in results_sbp.columns:
            valid_improvements = results_sbp['SinBP_D_improvement'].dropna()
            if len(valid_improvements) > 0:
                print(f"\nSinBP(D)の改善量 (MAPE減少):")
                print(f"  平均: {valid_improvements.mean():.2f}%")
                print(f"  中央値: {valid_improvements.median():.2f}%")
                print(f"  最小: {valid_improvements.min():.2f}%")
                print(f"  最大: {valid_improvements.max():.2f}%")
    
    print("\n" + "=" * 60)
    print("DBPの分析")
    print("=" * 60)
    results_dbp = analyze_by_subject(data_csv, target='DBP')
    
    if results_dbp is not None:
        print("\n被験者ごとのMAPE (DBP):")
        print(results_dbp.to_string(index=False))
        
        print("\n" + "=" * 60)
        print("DBP改善の統計:")
        print("=" * 60)
        if 'SinBP_M_better' in results_dbp.columns:
            m_improved = results_dbp['SinBP_M_better'].sum()
            m_total = results_dbp['SinBP_M_better'].notna().sum()
            print(f"SinBP(M)がRTBPより良い被験者: {m_improved}/{m_total} ({m_improved/m_total*100:.1f}%)")
        
        if 'SinBP_D_better' in results_dbp.columns:
            d_improved = results_dbp['SinBP_D_better'].sum()
            d_total = results_dbp['SinBP_D_better'].notna().sum()
            print(f"SinBP(D)がRTBPより良い被験者: {d_improved}/{d_total} ({d_improved/d_total*100:.1f}%)")
        
        # 改善量の統計
        if 'SinBP_M_improvement' in results_dbp.columns:
            valid_improvements = results_dbp['SinBP_M_improvement'].dropna()
            if len(valid_improvements) > 0:
                print(f"\nSinBP(M)の改善量 (MAPE減少):")
                print(f"  平均: {valid_improvements.mean():.2f}%")
                print(f"  中央値: {valid_improvements.median():.2f}%")
                print(f"  最小: {valid_improvements.min():.2f}%")
                print(f"  最大: {valid_improvements.max():.2f}%")
        
        if 'SinBP_D_improvement' in results_dbp.columns:
            valid_improvements = results_dbp['SinBP_D_improvement'].dropna()
            if len(valid_improvements) > 0:
                print(f"\nSinBP(D)の改善量 (MAPE減少):")
                print(f"  平均: {valid_improvements.mean():.2f}%")
                print(f"  中央値: {valid_improvements.median():.2f}%")
                print(f"  最小: {valid_improvements.min():.2f}%")
                print(f"  最大: {valid_improvements.max():.2f}%")
    
    # 結果をCSVに保存
    if results_sbp is not None:
        output_sbp = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/BP_Analysis/subject_analysis_SBP.csv")
        results_sbp.to_csv(output_sbp, index=False)
        print(f"\nSBPの結果を保存: {output_sbp}")
    
    if results_dbp is not None:
        output_dbp = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/Analysis/BP_Analysis/subject_analysis_DBP.csv")
        results_dbp.to_csv(output_dbp, index=False)
        print(f"DBPの結果を保存: {output_dbp}")

if __name__ == "__main__":
    main()
