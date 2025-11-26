"""
3手法の血圧推定モデルの学習・評価スクリプト

このスクリプトは、連続血圧計の参照値と比較して3手法のMAPEを評価し、係数を再学習します。

使用方法:
    python train_bp_models.py --data_csv <CSVファイルパス> --output_dir <出力ディレクトリ>

CSVファイルの構造:
    - timestamp: タイムスタンプ（ms）
    - subject_id: 被験者ID
    - ref_SBP, ref_DBP: 連続血圧計の参照値
    - M1_*: Method1 (RealtimeBP) の特徴量と推定値
    - M2_*: Method2 (SinBP) の特徴量と推定値
    - M3_*: Method3 (Logic1/Logic2/Logic3) の特徴量
"""

import numpy as np
import pandas as pd
import argparse
import os
from pathlib import Path
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LinearRegression, RidgeCV, LassoCV, ElasticNetCV, HuberRegressor
from sklearn.model_selection import GroupKFold, TimeSeriesSplit
from sklearn.metrics import mean_absolute_error, mean_squared_error
import json
from datetime import datetime
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

def mape(y_true, y_pred):
    """Mean Absolute Percentage Error"""
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    # 0除算を避ける
    mask = y_true > 0
    if np.sum(mask) == 0:
        return np.inf
    return np.mean(np.abs((y_pred[mask] - y_true[mask]) / y_true[mask])) * 100.0


def save_scatter_plot(
    reference: np.ndarray,
    estimate: np.ndarray,
    method_name: str,
    target: str,
    output_path: Path,
) -> None:
    """
    論文用のScatter plotを生成・保存
    """
    mask = np.isfinite(reference) & np.isfinite(estimate)
    if mask.sum() == 0:
        print(f"  Warning: {method_name} {target}: No valid data for scatter plot")
        return

    ref = reference[mask]
    est = estimate[mask]
    
    # データの統計情報を出力（デバッグ用）
    print(f"  {method_name} {target} scatter plot:")
    print(f"    Reference: min={ref.min():.2f}, max={ref.max():.2f}, mean={ref.mean():.2f}, std={ref.std():.2f}")
    print(f"    Estimate: min={est.min():.2f}, max={est.max():.2f}, mean={est.mean():.2f}, std={est.std():.2f}")
    
    # 推定値が全て同じ値の場合の警告
    if np.std(est) < 1e-6:
        print(f"    WARNING: Estimate values are all the same (std={est.std():.2e})")
        print(f"    Estimate unique values: {np.unique(est)}")
    
    min_lim = float(min(ref.min(), est.min()))
    max_lim = float(max(ref.max(), est.max()))
    
    # 範囲が0の場合の処理
    if max_lim - min_lim < 1e-6:
        print(f"    WARNING: Data range is too small (range={max_lim - min_lim:.2e})")
        # 範囲を少し広げる
        center = (min_lim + max_lim) / 2.0
        min_lim = center - 10.0
        max_lim = center + 10.0

    fig, ax = plt.subplots(figsize=(6, 6), dpi=150)
    ax.scatter(ref, est, s=15, alpha=0.5, edgecolors="none")
    ax.plot([min_lim, max_lim], [min_lim, max_lim], "r--", linewidth=1.5, label="y=x")
    
    # 回帰直線の計算と描画
    if np.std(ref) > 1e-10 and np.std(est) > 1e-10:
        slope, intercept = np.polyfit(ref, est, 1)
        reg_x = np.array([min_lim, max_lim])
        reg_y = slope * reg_x + intercept
        ax.plot(reg_x, reg_y, color="#ff7f0e", linewidth=2, 
                label=f"Regression (y={slope:.3f}x+{intercept:.2f})")
        
        # 相関係数とMAE、RMSEを表示
        corr = np.corrcoef(ref, est)[0, 1]
        mae_val = mean_absolute_error(ref, est)
        rmse_val = np.sqrt(mean_squared_error(ref, est))
        
        stats_text = f"r = {corr:.3f}\nMAE = {mae_val:.2f} mmHg\nRMSE = {rmse_val:.2f} mmHg"
        ax.text(0.05, 0.95, stats_text, transform=ax.transAxes,
                verticalalignment="top", bbox=dict(boxstyle="round", facecolor="wheat", alpha=0.8),
                fontsize=10)
    else:
        # 推定値が定数の場合
        if np.std(est) < 1e-10:
            mean_est = float(np.mean(est))
            ax.axhline(mean_est, color="#ff7f0e", linewidth=2, linestyle="-", 
                      label=f"Constant estimate = {mean_est:.2f} mmHg")
            print(f"    WARNING: Estimate is constant, cannot compute regression")
    
    ax.set_xlabel(f"Reference {target} (mmHg)", fontsize=12)
    ax.set_ylabel(f"Estimated {target} (mmHg)", fontsize=12)
    ax.set_title(f"{method_name} - {target}", fontsize=14, fontweight="bold")
    ax.set_xlim(min_lim, max_lim)
    ax.set_ylim(min_lim, max_lim)
    ax.grid(True, linestyle="--", alpha=0.3)
    ax.legend(loc="upper left", fontsize=10)
    fig.tight_layout()
    fig.savefig(output_path, format="svg", bbox_inches="tight")
    # Save as PNG as well
    png_path = output_path.with_suffix(".png")
    fig.savefig(png_path, format="png", bbox_inches="tight", dpi=300)
    plt.close(fig)


def save_bland_altman_plot(
    reference: np.ndarray,
    estimate: np.ndarray,
    method_name: str,
    target: str,
    output_path: Path,
) -> None:
    """
    論文用のBland-Altman plotを生成・保存
    """
    mask = np.isfinite(reference) & np.isfinite(estimate)
    if mask.sum() == 0:
        return

    ref = reference[mask]
    est = estimate[mask]
    mean_val = (ref + est) / 2.0
    diff = est - ref
    bias = float(np.mean(diff))
    sd = float(np.std(diff))
    loa_upper = bias + 1.96 * sd
    loa_lower = bias - 1.96 * sd

    fig, ax = plt.subplots(figsize=(7, 5), dpi=150)
    ax.scatter(mean_val, diff, s=15, alpha=0.5, edgecolors="none")
    ax.axhline(bias, color="r", linestyle="-", linewidth=2, label=f"Mean bias = {bias:.2f} mmHg")
    ax.axhline(loa_upper, color="gray", linestyle="--", linewidth=1.5, 
               label=f"+1.96 SD = {loa_upper:.2f} mmHg")
    ax.axhline(loa_lower, color="gray", linestyle="--", linewidth=1.5,
               label=f"-1.96 SD = {loa_lower:.2f} mmHg")
    
    ax.set_xlabel(f"Mean of Reference and Estimated {target} (mmHg)", fontsize=12)
    ax.set_ylabel(f"Difference (Estimated - Reference) (mmHg)", fontsize=12)
    ax.set_title(f"{method_name} - {target} Bland-Altman Plot", fontsize=14, fontweight="bold")
    ax.grid(True, linestyle="--", alpha=0.3)
    ax.legend(loc="best", fontsize=10)
    fig.tight_layout()
    fig.savefig(output_path, format="svg", bbox_inches="tight")
    # Save as PNG as well
    png_path = output_path.with_suffix(".png")
    fig.savefig(png_path, format="png", bbox_inches="tight", dpi=300)
    plt.close(fig)


def save_comparison_barplot(
    results: dict,
    target: str,
    output_path: Path,
) -> None:
    """
    3手法の比較バーグラフを生成・保存（各メトリックを個別の正方形画像として保存）
    """
    methods = []
    mae_means = []
    mae_stds = []
    rmse_means = []
    rmse_stds = []
    mape_means = []
    mape_stds = []
    
    for method_name, res in results.items():
        methods.append(method_name)
        mae_means.append(res["mae_mean"])
        mae_stds.append(res["mae_std"])
        rmse_means.append(res["rmse_mean"])
        rmse_stds.append(res["rmse_std"])
        mape_means.append(res["mape_mean"])
        mape_stds.append(res["mape_std"])
    
    x = np.arange(len(methods))
    width = 0.6  # Wider bars for single plot
    
    # Get output directory and base name
    output_dir = output_path.parent
    base_name = output_path.stem  # e.g., "comparison_SBP_barplot"
    
    # Create 3 separate square plots
    # 1. MAE
    fig_mae, ax_mae = plt.subplots(1, 1, figsize=(6, 6), dpi=150)
    ax_mae.bar(x, mae_means, width, yerr=mae_stds, alpha=0.8, capsize=5, color='#1f77b4')
    ax_mae.set_xlabel("Method", fontsize=14, fontweight='bold')
    ax_mae.set_ylabel("MAE (mmHg)", fontsize=14, fontweight='bold')
    ax_mae.set_title(f"{target} - MAE", fontsize=16, fontweight="bold", pad=15)
    ax_mae.set_xticks(x)
    ax_mae.set_xticklabels(methods, rotation=0, ha="center", fontsize=12)
    ax_mae.grid(True, axis="y", linestyle="--", alpha=0.3)
    ax_mae.tick_params(axis='both', which='major', labelsize=11)
    fig_mae.tight_layout()
    mae_path_svg = output_dir / f"{base_name}_MAE.svg"
    mae_path_png = output_dir / f"{base_name}_MAE.png"
    fig_mae.savefig(mae_path_svg, format="svg", bbox_inches="tight")
    fig_mae.savefig(mae_path_png, format="png", bbox_inches="tight", dpi=150)
    plt.close(fig_mae)
    
    # 2. RMSE
    fig_rmse, ax_rmse = plt.subplots(1, 1, figsize=(6, 6), dpi=150)
    ax_rmse.bar(x, rmse_means, width, yerr=rmse_stds, alpha=0.8, capsize=5, color='#ff7f0e')
    ax_rmse.set_xlabel("Method", fontsize=14, fontweight='bold')
    ax_rmse.set_ylabel("RMSE (mmHg)", fontsize=14, fontweight='bold')
    ax_rmse.set_title(f"{target} - RMSE", fontsize=16, fontweight="bold", pad=15)
    ax_rmse.set_xticks(x)
    ax_rmse.set_xticklabels(methods, rotation=0, ha="center", fontsize=12)
    ax_rmse.grid(True, axis="y", linestyle="--", alpha=0.3)
    ax_rmse.tick_params(axis='both', which='major', labelsize=11)
    fig_rmse.tight_layout()
    rmse_path_svg = output_dir / f"{base_name}_RMSE.svg"
    rmse_path_png = output_dir / f"{base_name}_RMSE.png"
    fig_rmse.savefig(rmse_path_svg, format="svg", bbox_inches="tight")
    fig_rmse.savefig(rmse_path_png, format="png", bbox_inches="tight", dpi=150)
    plt.close(fig_rmse)
    
    # 3. MAPE
    fig_mape, ax_mape = plt.subplots(1, 1, figsize=(6, 6), dpi=150)
    ax_mape.bar(x, mape_means, width, yerr=mape_stds, alpha=0.8, capsize=5, color='#2ca02c')
    ax_mape.set_xlabel("Method", fontsize=14, fontweight='bold')
    ax_mape.set_ylabel("MAPE (%)", fontsize=14, fontweight='bold')
    ax_mape.set_title(f"{target} - MAPE", fontsize=16, fontweight="bold", pad=15)
    ax_mape.set_xticks(x)
    ax_mape.set_xticklabels(methods, rotation=0, ha="center", fontsize=12)
    ax_mape.grid(True, axis="y", linestyle="--", alpha=0.3)
    ax_mape.tick_params(axis='both', which='major', labelsize=11)
    fig_mape.tight_layout()
    mape_path_svg = output_dir / f"{base_name}_MAPE.svg"
    mape_path_png = output_dir / f"{base_name}_MAPE.png"
    fig_mape.savefig(mape_path_svg, format="svg", bbox_inches="tight")
    fig_mape.savefig(mape_path_png, format="png", bbox_inches="tight", dpi=150)
    plt.close(fig_mape)
    
    print(f"  Individual barplots saved:")
    print(f"    MAE: {mae_path_svg} and {mae_path_png}")
    print(f"    RMSE: {rmse_path_svg} and {rmse_path_png}")
    print(f"    MAPE: {mape_path_svg} and {mape_path_png}")

def remove_outliers(df, target_col, time_col=None, subject_col="subject_id"):
    """
    外れ値を除去する
    
    Parameters:
    -----------
    df : pd.DataFrame
        データフレーム
    target_col : str
        ターゲット（ref_SBP/ref_DBP）のカラム名
    time_col : str, optional
        時間カラム名（時系列外れ値検出用）
    subject_col : str
        被験者IDのカラム名
    
    Returns:
    --------
    pd.DataFrame
        外れ値を除去したデータフレーム
    """
    df_clean = df.copy()
    initial_count = len(df_clean)
    
    # 0. 0の値をNaNに変換（明らかに計測できていない値）
    zero_count = (df_clean[target_col] == 0).sum()
    if zero_count > 0:
        print(f"  0の値を無効化: {zero_count} サンプル")
        df_clean.loc[df_clean[target_col] == 0, target_col] = np.nan
        df_clean = df_clean.dropna(subset=[target_col])
    
    # 1. 生理学的範囲チェック
    if target_col == "ref_SBP":
        # SBP: 60-200 mmHg
        valid_range = (df_clean[target_col] >= 60) & (df_clean[target_col] <= 200)
        removed_physio = (~valid_range).sum()
        df_clean = df_clean[valid_range].copy()
        if removed_physio > 0:
            print(f"  生理学的範囲外を除去: {removed_physio} サンプル (SBP: 60-200 mmHg)")
    elif target_col == "ref_DBP":
        # DBP: 40-150 mmHg
        valid_range = (df_clean[target_col] >= 40) & (df_clean[target_col] <= 150)
        removed_physio = (~valid_range).sum()
        df_clean = df_clean[valid_range].copy()
        if removed_physio > 0:
            print(f"  生理学的範囲外を除去: {removed_physio} サンプル (DBP: 40-150 mmHg)")
    
    # 2. 被験者ごとの統計的外れ値検出（±3σ）
    if subject_col in df_clean.columns:
        removed_stats = 0
        for subject_id in df_clean[subject_col].unique():
            subject_mask = df_clean[subject_col] == subject_id
            subject_data = df_clean.loc[subject_mask, target_col]
            
            if len(subject_data) < 3:
                continue
            
            mean_val = subject_data.mean()
            std_val = subject_data.std()
            
            if std_val > 0:
                z_scores = np.abs((subject_data - mean_val) / std_val)
                outlier_mask = z_scores > 3.0
                removed_stats += outlier_mask.sum()
                df_clean.loc[subject_mask & outlier_mask, target_col] = np.nan
        
        if removed_stats > 0:
            print(f"  統計的外れ値（±3σ）を除去: {removed_stats} サンプル")
            df_clean = df_clean.dropna(subset=[target_col])
    
    # 3. 時系列的な外れ値検出（前後の値との差が大きい）
    if time_col and time_col in df_clean.columns and subject_col in df_clean.columns:
        removed_temporal = 0
        df_clean = df_clean.sort_values([subject_col, time_col]).reset_index(drop=True)
        
        for subject_id in df_clean[subject_col].unique():
            subject_mask = df_clean[subject_col] == subject_id
            subject_df = df_clean[subject_mask].copy()
            
            if len(subject_df) < 3:
                continue
            
            subject_values = subject_df[target_col].values
            subject_std = subject_values.std()
            
            if subject_std == 0:
                continue
            
            # 前後の値との差分を計算
            for i in range(1, len(subject_df) - 1):
                prev_val = subject_values[i-1]
                curr_val = subject_values[i]
                next_val = subject_values[i+1]
                
                # 前後の平均値との差が3σ以上の場合
                neighbor_mean = (prev_val + next_val) / 2.0
                diff = abs(curr_val - neighbor_mean)
                
                if diff > 3.0 * subject_std:
                    # 元のDataFrameのインデックスを取得
                    original_idx = subject_df.index[i]
                    df_clean.loc[original_idx, target_col] = np.nan
                    removed_temporal += 1
        
        if removed_temporal > 0:
            print(f"  時系列的外れ値（前後との差>3σ）を除去: {removed_temporal} サンプル")
            df_clean = df_clean.dropna(subset=[target_col])
    
    final_count = len(df_clean)
    removed_total = initial_count - final_count
    if removed_total > 0:
        print(f"  外れ値除去: {initial_count} → {final_count} サンプル ({removed_total} サンプル除去)")
    
    return df_clean

def aggregate_by_time_windows(y_true, y_pred, time_values, window_seconds, time_unit="seconds"):
    """
    時間窓ごとの平均値で再サンプリングした系列を返す
    時間順序を保持して集約する
    """
    if time_values is None or window_seconds is None or window_seconds <= 0:
        return y_true, y_pred
    
    time_arr = np.asarray(time_values, dtype=float)
    y_true_arr = np.asarray(y_true, dtype=float)
    y_pred_arr = np.asarray(y_pred, dtype=float)
    
    valid_mask = ~np.isnan(time_arr) & np.isfinite(y_true_arr) & np.isfinite(y_pred_arr)
    if not np.any(valid_mask):
        return y_true, y_pred
    
    time_arr = time_arr[valid_mask]
    y_true_arr = y_true_arr[valid_mask]
    y_pred_arr = y_pred_arr[valid_mask]
    
    if time_unit == "milliseconds":
        time_arr = time_arr / 1000.0
    elif time_unit == "auto" and np.nanmax(time_arr) > 1e3:
        time_arr = time_arr / 1000.0
    
    # 時間順序でソート（重要：時間窓集約前に順序を保証）
    sort_indices = np.argsort(time_arr)
    time_arr = time_arr[sort_indices]
    y_true_arr = y_true_arr[sort_indices]
    y_pred_arr = y_pred_arr[sort_indices]
    
    # 時間窓IDを計算
    window_ids = np.floor(time_arr / window_seconds).astype(int)
    
    # DataFrameを作成して時間窓ごとに集約（時間順序を保持）
    df_tmp = pd.DataFrame({
        "window_id": window_ids,
        "time": time_arr,
        "y_true": y_true_arr,
        "y_pred": y_pred_arr
    })
    
    # 時間窓IDと時間でソートしてから集約（時間順序を保証）
    df_tmp = df_tmp.sort_values(["window_id", "time"]).reset_index(drop=True)
    
    # 時間窓ごとに平均を計算（時間順序を保持）
    agg = df_tmp.groupby("window_id", sort=True)[["y_true", "y_pred"]].mean().reset_index()
    
    # 時間窓IDでソート（念のため）
    agg = agg.sort_values("window_id").reset_index(drop=True)
    
    return agg["y_true"].values, agg["y_pred"].values

def eval_one_method(df, feature_cols, target_col, groups=None, split_strategy="groupkfold", 
                    n_splits=5, estimator_kind="ols", method_name="Method",
                    time_col=None, window_seconds=None, time_unit="seconds"):
    """
    1つの手法を評価する
    
    Parameters:
    -----------
    df : pd.DataFrame
        データフレーム
    feature_cols : list
        特徴量カラム名のリスト
    target_col : str
        ターゲット（SBP/DBP）のカラム名
    groups : array-like, optional
        グループ（被験者IDなど）の配列
    split_strategy : str
        "groupkfold" または "timeseries"
    n_splits : int
        分割数
    estimator_kind : str
        "ols", "ridge", "lasso", "enet", "huber", "nonneg_ols"
    method_name : str
        手法名（ログ出力用）
    """
    # 特徴量にNaNが含まれている行を除外
    valid_mask = df[feature_cols].notna().all(axis=1)
    df_clean = df[valid_mask].copy()
    
    if len(df_clean) == 0:
        print(f"Warning: {method_name}: すべての特徴量が有効なサンプルがありません。")
        return {
            "mape_mean": np.inf,
            "mape_std": 0.0,
            "mape_each_fold": [],
            "mae_mean": np.inf,
            "mae_std": 0.0,
            "rmse_mean": np.inf,
            "rmse_std": 0.0,
            "coef_each_fold": [],
            "intercept_each_fold": [],
            "scaler_stats_each_fold": [],
            "feature_names": feature_cols,
            "all_y_true": [],
            "all_y_pred": [],
        }
    
    # グループを再計算（行が削除されたため）
    if groups is not None:
        groups_clean = groups[valid_mask]
    else:
        groups_clean = None
    
    # 分割処理
    if split_strategy == "groupkfold":
        assert groups_clean is not None, "GroupKFold requires groups"
        splitter = GroupKFold(n_splits=n_splits)
        split_iter = splitter.split(df_clean, groups=groups_clean)
    elif split_strategy == "timeseries":
        if time_col is None or time_col not in df_clean.columns:
            raise ValueError("TimeSeriesSplit を使用するには時間カラムが必要です。")
        splitter = TimeSeriesSplit(n_splits=n_splits)
        df_clean = df_clean.sort_values(time_col).reset_index(drop=True)
        split_iter = splitter.split(df_clean)
    else:
        raise ValueError(f"Unknown split_strategy: {split_strategy}")
    
    X_all = df_clean[feature_cols].values
    y_all = df_clean[target_col].values
    time_all = df_clean[time_col].values if (time_col and time_col in df_clean.columns) else None

    mape_list = []
    mae_list = []
    rmse_list = []
    coefs_list = []
    intercept_list = []
    scaler_stats = []
    # 全foldの予測値と参照値を保存（図生成用）
    all_y_true = []
    all_y_pred = []

    for fold_idx, (train_idx, test_idx) in enumerate(split_iter):
        X_tr, X_te = X_all[train_idx], X_all[test_idx]
        y_tr, y_te = y_all[train_idx], y_all[test_idx]
        
        # さらにNaNチェック（念のため）
        train_valid = ~(np.isnan(X_tr).any(axis=1) | np.isnan(y_tr))
        test_valid = ~(np.isnan(X_te).any(axis=1) | np.isnan(y_te))
        
        if train_valid.sum() == 0 or test_valid.sum() == 0:
            print(f"Warning: {method_name} Fold {fold_idx+1}: 有効なサンプルがありません。スキップします。")
            continue
        
        X_tr = X_tr[train_valid]
        y_tr = y_tr[train_valid]
        X_te = X_te[test_valid]
        y_te = y_te[test_valid]

        # パイプライン構築
        if estimator_kind == "ols":
            est = LinearRegression()
        elif estimator_kind == "ridge":
            # alpha範囲を調整（過度な正則化を避ける）
            # データが少ない場合でも係数が0になりすぎないようにする
            # alpha範囲をより小さい値から開始（-10から0まで、より細かく）
            # これにより、正則化が弱くなり、係数がより有効に働く
            n_cv_folds = min(5, max(3, len(X_tr) // 10 + 1))
            # alpha範囲を広げて、より小さい値も含める（OLSに近い値も含める）
            # 最大alphaを1.0に制限して、過度な正則化を防ぐ
            alphas = np.concatenate([
                np.logspace(-10, -2, 25),  # 非常に小さい値から（OLSに近い）
                np.logspace(-2, 0, 20)     # 弱い正則化（最大1.0まで）
            ])
            # データが非常に少ない場合はOLSを使用
            if len(X_tr) < 20:
                print(f"  Warning: Training data is very small ({len(X_tr)} samples), using OLS instead of Ridge")
                est = LinearRegression()
            else:
                est = RidgeCV(alphas=alphas, cv=n_cv_folds)
        elif estimator_kind == "lasso":
            est = LassoCV(alphas=None, cv=min(5, len(X_tr) // 10 + 1), max_iter=10000)
        elif estimator_kind == "enet":
            est = ElasticNetCV(l1_ratio=[.1, .3, .5, .7, .9, .95, 1.0], alphas=None, 
                              cv=min(5, len(X_tr) // 10 + 1), max_iter=10000)
        elif estimator_kind == "huber":
            est = HuberRegressor()
        elif estimator_kind == "nonneg_ols":
            est = LinearRegression(positive=True)
        else:
            raise ValueError(f"Unknown estimator_kind: {estimator_kind}")

        pipe = Pipeline([
            ("scaler", StandardScaler(with_mean=True, with_std=True)),
            ("reg", est),
        ])
        
        # 特徴量の統計情報を確認（デバッグ用）
        if fold_idx == 0:  # 最初のfoldのみ
            print(f"{method_name} Fold {fold_idx+1}: Feature statistics:")
            for i, feat_name in enumerate(feature_cols):
                feat_std = np.std(X_te[:, i])
                feat_mean = np.mean(X_te[:, i])
                print(f"  {feat_name}: mean={feat_mean:.4f}, std={feat_std:.4f}, "
                      f"min={X_te[:, i].min():.4f}, max={X_te[:, i].max():.4f}")
                if feat_std < 1e-6:
                    print(f"    WARNING: Feature {feat_name} has no variance!")
        
        pipe.fit(X_tr, y_tr)
        y_hat = pipe.predict(X_te)
        
        # RidgeCVの場合、選択されたalphaが大きすぎる場合は警告
        if estimator_kind == "ridge" and hasattr(pipe.named_steps["reg"], 'alpha_'):
            selected_alpha = pipe.named_steps["reg"].alpha_
            if selected_alpha > 0.1:
                print(f"  WARNING: {method_name} Fold {fold_idx+1}: Selected alpha ({selected_alpha:.6e}) may cause "
                      f"over-regularization. Consider using OLS or reducing alpha range.")
        
        # モデルの係数を確認（デバッグ用）
        if fold_idx == 0:
            scaler = pipe.named_steps["scaler"]
            reg = pipe.named_steps["reg"]
            coef_std = reg.coef_
            intercept_std = reg.intercept_
            
            # RidgeCVの場合、選択されたalphaを確認
            if hasattr(reg, 'alpha_'):
                print(f"{method_name} Fold {fold_idx+1}: Selected alpha (regularization): {reg.alpha_:.6e}")
                if reg.alpha_ > 10.0:
                    print(f"  WARNING: Alpha is large ({reg.alpha_:.6e}), may cause over-regularization!")
                    print(f"  Consider using OLS or reducing alpha range.")
                elif reg.alpha_ < 1e-6:
                    print(f"  INFO: Alpha is very small ({reg.alpha_:.6e}), model is close to OLS.")
            
            # 非標準化空間の係数を計算
            coef_real = coef_std / (scaler.scale_ + 1e-12)
            intercept_real = intercept_std - np.sum(coef_std * scaler.mean_ / (scaler.scale_ + 1e-12))
            
            print(f"{method_name} Fold {fold_idx+1}: Model coefficients:")
            print(f"  Intercept (standardized): {intercept_std:.4f}, (real space): {intercept_real:.4f}")
            for i, feat_name in enumerate(feature_cols):
                print(f"  {feat_name}: std_coef={coef_std[i]:.6f}, real_coef={coef_real[i]:.4f}, "
                      f"scale={scaler.scale_[i]:.4f}, mean={scaler.mean_[i]:.4f}")
            
            # 係数の大きさを確認
            coef_magnitude = np.abs(coef_std).max()
            if coef_magnitude < 0.01:
                print(f"  WARNING: Coefficients are very small (max abs={coef_magnitude:.6f}), "
                      f"predictions will be close to intercept!")
            
            # 予測値の計算を検証
            # StandardScalerで標準化されたX_teを使って予測していることを確認
            X_te_scaled = scaler.transform(X_te)
            y_hat_manual = intercept_std + np.dot(X_te_scaled, coef_std)
            print(f"  Verification: Using standardized features to predict:")
            print(f"    X_te_scaled mean: {X_te_scaled.mean(axis=0)}")
            print(f"    X_te_scaled std: {X_te_scaled.std(axis=0)}")
            print(f"    Manual prediction (first sample): {y_hat_manual[0]:.2f}")
            print(f"    Pipeline prediction (first sample): {y_hat[0]:.2f}")
            print(f"    Difference: {abs(y_hat_manual[0] - y_hat[0]):.6f}")
            
            # 係数の影響を確認
            feature_contributions = X_te_scaled * coef_std
            print(f"  Feature contributions (first sample):")
            for i, feat_name in enumerate(feature_cols):
                print(f"    {feat_name}: {feature_contributions[0, i]:.6f}")
            print(f"    Sum of contributions: {feature_contributions[0].sum():.6f}")
            print(f"    Intercept: {intercept_std:.4f}")
            print(f"    Total prediction: {intercept_std + feature_contributions[0].sum():.4f}")
            
            # 予測値の分散を確認
            if np.std(y_hat) < 1e-6:
                print(f"  WARNING: Predictions have no variance (std={np.std(y_hat):.6f})!")
                print(f"    This suggests the model is over-regularized or features have no effect.")
        
        # 予測値の統計情報を出力（デバッグ用）
        if fold_idx == 0:  # 最初のfoldのみ
            print(f"{method_name} Fold {fold_idx+1}: Prediction statistics:")
            print(f"  y_hat: min={y_hat.min():.2f}, max={y_hat.max():.2f}, mean={y_hat.mean():.2f}, std={y_hat.std():.2f}")
            print(f"  y_te: min={y_te.min():.2f}, max={y_te.max():.2f}, mean={y_te.mean():.2f}, std={y_te.std():.2f}")
            if np.std(y_hat) < 1e-6:
                print(f"  WARNING: Predictions are all the same! Unique values: {np.unique(y_hat)}")
                print(f"  This suggests the model is not learning properly. Check feature values above.")
        
        # numpy配列として確実に扱う
        eval_y_true = np.asarray(y_te, dtype=float).copy()
        eval_y_pred = np.asarray(y_hat, dtype=float).copy()
        
        if time_all is not None and window_seconds and window_seconds > 0:
            eval_y_true, eval_y_pred = aggregate_by_time_windows(
                y_te, y_hat, time_all[test_idx], window_seconds, time_unit
            )
            
            # 時間窓集約後の統計情報を出力
            if fold_idx == 0:
                print(f"  After window aggregation:")
                print(f"  eval_y_pred: min={eval_y_pred.min():.2f}, max={eval_y_pred.max():.2f}, mean={eval_y_pred.mean():.2f}, std={eval_y_pred.std():.2f}")
                if np.std(eval_y_pred) < 1e-6:
                    print(f"  WARNING: Aggregated predictions are all the same! Unique values: {np.unique(eval_y_pred)}")
        
        if window_seconds and window_seconds > 0 and time_all is not None:
            print(f"{method_name} Fold {fold_idx+1}: window aggregation applied "
                  f"({len(y_te)} samples → {len(eval_y_true)} windows, window={window_seconds}s)")
        else:
            print(f"{method_name} Fold {fold_idx+1}: window aggregation disabled")

        fold_mape = mape(eval_y_true, eval_y_pred)
        fold_mae = mean_absolute_error(eval_y_true, eval_y_pred)
        fold_rmse = np.sqrt(mean_squared_error(eval_y_true, eval_y_pred))
        
        mape_list.append(fold_mape)
        mae_list.append(fold_mae)
        rmse_list.append(fold_rmse)
        
        # 予測値と参照値を保存（numpy配列の場合はtolist()、既にリストの場合はそのまま）
        if isinstance(eval_y_true, np.ndarray):
            all_y_true.extend(eval_y_true.tolist())
        else:
            all_y_true.extend(list(eval_y_true))
        
        if isinstance(eval_y_pred, np.ndarray):
            all_y_pred.extend(eval_y_pred.tolist())
        else:
            all_y_pred.extend(list(eval_y_pred))
        
        # デバッグ: 保存されたデータの統計情報を出力（最初のfoldのみ）
        if fold_idx == 0 and len(all_y_pred) > 0:
            print(f"  Saved to all_y_pred: count={len(all_y_pred)}, unique values={len(set(all_y_pred))}, "
                  f"min={min(all_y_pred):.2f}, max={max(all_y_pred):.2f}, "
                  f"std={np.std(all_y_pred):.2f}")
            if len(set(all_y_pred)) < 10:
                print(f"  Unique values: {sorted(set(all_y_pred))}")

        # 係数の取り出し（非標準化空間へ変換）
        scaler = pipe.named_steps["scaler"]
        reg = pipe.named_steps["reg"]
        coef_std = reg.coef_
        intercept_std = reg.intercept_
        
        # 非標準化（原空間）の係数へ戻す
        coef_real = coef_std / (scaler.scale_ + 1e-12)
        intercept_real = intercept_std - np.sum(coef_std * scaler.mean_ / (scaler.scale_ + 1e-12))

        coefs_list.append(coef_real.tolist())
        intercept_list.append(float(intercept_real))
        scaler_stats.append({
            "mean": scaler.mean_.tolist(),
            "scale": scaler.scale_.tolist()
        })

        print(f"{method_name} Fold {fold_idx+1}: MAPE={fold_mape:.2f}%, MAE={fold_mae:.2f}, RMSE={fold_rmse:.2f}")

    return {
        "mape_mean": float(np.mean(mape_list)),
        "mape_std": float(np.std(mape_list, ddof=1)),
        "mape_each_fold": [float(x) for x in mape_list],
        "mae_mean": float(np.mean(mae_list)),
        "mae_std": float(np.std(mae_list, ddof=1)),
        "rmse_mean": float(np.mean(rmse_list)),
        "rmse_std": float(np.std(rmse_list, ddof=1)),
        "coef_each_fold": coefs_list,
        "intercept_each_fold": intercept_list,
        "scaler_stats_each_fold": scaler_stats,
        "feature_names": feature_cols,
        "all_y_true": all_y_true,
        "all_y_pred": all_y_pred,
    }

def main():
    parser = argparse.ArgumentParser(description="3手法の血圧推定モデルの学習・評価")
    parser.add_argument("--data_csv", type=str, required=True, help="学習用CSVファイルのパス")
    parser.add_argument("--output_dir", type=str, default="./results", help="結果出力ディレクトリ")
    parser.add_argument("--split_strategy", type=str, default="groupkfold", choices=["groupkfold", "timeseries"],
                        help="データ分割戦略")
    parser.add_argument("-t", "--timeseries", action="store_true",
                        help="TimeSeriesSplitを使用する（--split_strategy timeseries と同等）")
    parser.add_argument("--n_splits", type=int, default=5, help="分割数")
    parser.add_argument("--target", type=str, default="SBP", choices=["SBP", "DBP"], help="評価対象（SBP/DBP）")
    parser.add_argument("--estimator", type=str, default="ridge", 
                        choices=["ols", "ridge", "lasso", "enet", "huber", "nonneg_ols"],
                        help="推定器の種類")
    parser.add_argument("--window_seconds", type=float, default=0.0,
                        help="この秒数でデータを区切り平均化して指標を算出（0以下で無効、デフォルトはリアルタイム評価）")
    
    args = parser.parse_args()
    
    # -t フラグが指定された場合は split_strategy を timeseries に設定
    if args.timeseries:
        args.split_strategy = "timeseries"

    # 出力ディレクトリ作成
    os.makedirs(args.output_dir, exist_ok=True)

    # データ読み込み
    print(f"Loading data from {args.data_csv}...")
    df = pd.read_csv(args.data_csv)
    df.columns = [col.strip() for col in df.columns]
    print(f"Loaded {len(df)} samples")

    # 参照値のカラム名
    ref_col = f"ref_{args.target}"
    if ref_col not in df.columns:
        print(f"Warning: {ref_col} column not found. Using empty values.")
        df[ref_col] = np.nan

    # 参照値が欠損している行を除外
    df_valid = df.dropna(subset=[ref_col]).copy()
    print(f"Valid samples (with reference): {len(df_valid)}")

    if len(df_valid) == 0:
        print("Error: No valid samples with reference values.")
        return

    # 被験者IDの処理
    if "subject_id" not in df_valid.columns:
        print("Warning: subject_id column not found. Using default.")
        df_valid["subject_id"] = "subject_1"
    
    # 時間カラムの検出（外れ値除去で使用）
    time_col = None
    time_unit = "seconds"
    time_candidates = [
        ("経過時間_秒", "seconds"),
        ("elapsed_seconds", "seconds"),
        ("timestamp", "milliseconds"),
    ]
    for candidate, unit in time_candidates:
        if candidate in df_valid.columns:
            time_col = candidate
            time_unit = unit
            break
    
    # 外れ値除去（参照値）
    print(f"\n=== Removing outliers for {args.target} (reference values) ===")
    df_valid = remove_outliers(df_valid, ref_col, time_col=time_col, subject_col="subject_id")
    
    if len(df_valid) == 0:
        print("Error: No valid samples remaining after outlier removal.")
        return
    
    # 外れ値除去（アプリデータの特徴量）
    print(f"\n=== Removing outliers for app features ===")
    feature_cols = [col for col in df_valid.columns if col.startswith('M1_') or 
                    col.startswith('M2_') or col.startswith('M3_')]
    
    initial_count = len(df_valid)
    removed_features = 0
    
    for col in feature_cols:
        if df_valid[col].dtype not in [np.float64, np.int64]:
            continue
        
        # 被験者ごとに統計的外れ値検出（±3σ）
        for subject_id in df_valid["subject_id"].unique():
            subject_mask = df_valid["subject_id"] == subject_id
            subject_data = df_valid.loc[subject_mask, col]
            
            if len(subject_data) < 3:
                continue
            
            mean_val = subject_data.mean()
            std_val = subject_data.std()
            
            if std_val > 0:
                z_scores = np.abs((subject_data - mean_val) / std_val)
                outlier_mask = z_scores > 3.0
                removed_features += outlier_mask.sum()
                df_valid.loc[subject_mask & outlier_mask, col] = np.nan
    
    # 特徴量に欠損値がある行を除外（参照値は保持）
    if removed_features > 0:
        print(f"  アプリ特徴量の統計的外れ値（±3σ）を除去: {removed_features} 値")
        # 注: 各手法の評価時に、その手法で使用する特徴量がすべて有効な行のみが使用されます
        # ここでは、すべての特徴量が欠損している行のみを除外
        all_features_missing = df_valid[feature_cols].isna().all(axis=1)
        df_valid = df_valid[~all_features_missing].copy()
    
    final_count = len(df_valid)
    if initial_count != final_count:
        print(f"  アプリ特徴量の外れ値除去: {initial_count} → {final_count} サンプル ({initial_count - final_count} サンプル除去)")
    
    if len(df_valid) == 0:
        print("Error: No valid samples remaining after feature outlier removal.")
        return
    
    if args.split_strategy == "groupkfold":
        groups = df_valid["subject_id"].values
        unique_group_count = len(np.unique(groups))
        if unique_group_count < args.n_splits:
            print(f"Warning: n_splits={args.n_splits} ですがグループ数={unique_group_count} のため、分割数を縮小します。")
            args.n_splits = unique_group_count
        if args.n_splits < 2:
            print("Error: GroupKFold の分割数は2以上が必要です。グループを増やすか split_strategy を変更してください。")
            return
    else:
        groups = None
    
    if time_col is None and args.window_seconds and args.window_seconds > 0:
        print("Warning: 時間情報のカラムが見つからないためウィンドウ集計を無効化します。")
        args.window_seconds = 0.0

    # 各手法の特徴量定義
    # RealTimeBP: correctedGreenValueから直接推定
    realtimebp_features = ["M1_A", "M1_HR", "M1_V2P_relTTP", "M1_P2V_relTTP"]
    # SinBP_D: Sin波との歪みから算出
    sinbp_d_features = ["M2_A", "M2_HR", "M2_V2P_relTTP", "M2_P2V_relTTP", "M2_Stiffness", "M2_E"]
    # SinBP_M: Sin波自体をモデルとして算出
    sinbp_m_features = ["M3_A", "M3_HR", "M3_Mean", "M3_Phi"]

    # 特徴量が存在するかチェック
    available_features = set(df_valid.columns)
    
    realtimebp_features = [f for f in realtimebp_features if f in available_features]
    sinbp_d_features = [f for f in sinbp_d_features if f in available_features]
    sinbp_m_features = [f for f in sinbp_m_features if f in available_features]

    print(f"\nRealTimeBP features: {realtimebp_features}")
    print(f"SinBP_D features: {sinbp_d_features}")
    print(f"SinBP_M features: {sinbp_m_features}")

    results = {}

    # RealTimeBP評価
    if realtimebp_features:
        print(f"\n=== Evaluating RealTimeBP for {args.target} ===")
        res1 = eval_one_method(
            df_valid, realtimebp_features, ref_col, groups, 
            args.split_strategy, args.n_splits, args.estimator, "RealTimeBP",
            time_col=time_col, window_seconds=args.window_seconds, time_unit=time_unit
        )
        results["RealTimeBP"] = res1

    # SinBP_D評価
    if sinbp_d_features:
        print(f"\n=== Evaluating SinBP_D (Distortion based) for {args.target} ===")
        res2 = eval_one_method(
            df_valid, sinbp_d_features, ref_col, groups,
            args.split_strategy, args.n_splits, args.estimator, "SinBP_D",
            time_col=time_col, window_seconds=args.window_seconds, time_unit=time_unit
        )
        results["SinBP_D"] = res2

    # SinBP_M評価
    if sinbp_m_features:
        print(f"\n=== Evaluating SinBP_M (Model based) for {args.target} ===")
        res3 = eval_one_method(
            df_valid, sinbp_m_features, ref_col, groups,
            args.split_strategy, args.n_splits, args.estimator, "SinBP_M",
            time_col=time_col, window_seconds=args.window_seconds, time_unit=time_unit
        )
        results["SinBP_M"] = res3

    # 結果のサマリー
    print("\n=== Summary ===")
    summary_data = []
    for method_name, res in results.items():
        summary_data.append({
            "method": method_name,
            "mape_mean": res["mape_mean"],
            "mape_std": res["mape_std"],
            "mae_mean": res["mae_mean"],
            "mae_std": res["mae_std"],
            "rmse_mean": res["rmse_mean"],
            "rmse_std": res["rmse_std"],
        })
        print(f"{method_name}: MAPE={res['mape_mean']:.2f}±{res['mape_std']:.2f}%, "
              f"MAE={res['mae_mean']:.2f}±{res['mae_std']:.2f}, "
              f"RMSE={res['rmse_mean']:.2f}±{res['rmse_std']:.2f}")

    # 結果を保存
    summary_df = pd.DataFrame(summary_data)
    summary_df = summary_df.sort_values("mape_mean")
    
    output_file = os.path.join(args.output_dir, f"evaluation_summary_{args.target}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv")
    summary_df.to_csv(output_file, index=False)
    print(f"\nSummary saved to {output_file}")

    # 詳細結果をJSONで保存
    json_file = os.path.join(args.output_dir, f"evaluation_details_{args.target}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
    with open(json_file, 'w') as f:
        json.dump(results, f, indent=2)
    print(f"Detailed results saved to {json_file}")

    # 係数の平均を計算して保存（Android実装用）
    coefficients_file = os.path.join(args.output_dir, f"coefficients_{args.target}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json")
    coefficients_output = {}
    for method_name, res in results.items():
        # Fold平均の係数
        coefs_array = np.array(res["coef_each_fold"])
        intercepts_array = np.array(res["intercept_each_fold"])
        
        coefficients_output[method_name] = {
            "coefficients": coefs_array.mean(axis=0).tolist(),
            "intercept": float(intercepts_array.mean()),
            "feature_names": res["feature_names"],
            "note": "Average coefficients across all folds. Use these for Android implementation."
        }
    
    with open(coefficients_file, 'w') as f:
        json.dump(coefficients_output, f, indent=2)
    print(f"Coefficients saved to {coefficients_file}")
    
    # 論文用の図を生成・保存
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    plots_dir = Path(args.output_dir) / f"plots_{timestamp}"
    plots_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"\n=== Generating plots for {args.target} ===")
    
    # 各手法のScatter plotとBland-Altman plot
    for method_name, res in results.items():
        if "all_y_true" in res and "all_y_pred" in res and len(res["all_y_true"]) > 0:
            # numpy配列に変換
            y_true = np.asarray(res["all_y_true"], dtype=float)
            y_pred = np.asarray(res["all_y_pred"], dtype=float)
            
            # デバッグ情報を出力
            print(f"\n  {method_name} {args.target} plotting data:")
            print(f"    y_true: shape={y_true.shape}, min={y_true.min():.2f}, max={y_true.max():.2f}, "
                  f"mean={y_true.mean():.2f}, std={y_true.std():.2f}, unique={len(np.unique(y_true))}")
            print(f"    y_pred: shape={y_pred.shape}, min={y_pred.min():.2f}, max={y_pred.max():.2f}, "
                  f"mean={y_pred.mean():.2f}, std={y_pred.std():.2f}, unique={len(np.unique(y_pred))}")
            
            if len(np.unique(y_pred)) < 10:
                print(f"    y_pred unique values: {sorted(np.unique(y_pred))}")
            
            # 有効なデータのみを使用
            valid_mask = np.isfinite(y_true) & np.isfinite(y_pred)
            if valid_mask.sum() > 0:
                y_true_valid = y_true[valid_mask]
                y_pred_valid = y_pred[valid_mask]
                
                # Scatter plot
                scatter_path = plots_dir / f"{method_name}_{args.target}_scatter.svg"
                save_scatter_plot(y_true_valid, y_pred_valid, method_name, args.target, scatter_path)
                print(f"  Scatter plot saved: {scatter_path}")
                
                # Bland-Altman plot
                ba_path = plots_dir / f"{method_name}_{args.target}_bland_altman.svg"
                save_bland_altman_plot(y_true_valid, y_pred_valid, method_name, args.target, ba_path)
                print(f"  Bland-Altman plot saved: {ba_path}")
            else:
                print(f"  Warning: {method_name} has no valid data for plotting")
        else:
            print(f"  Warning: {method_name} has no prediction data for plotting")
    
    # 3手法の比較バーグラフ
    comparison_path = plots_dir / f"comparison_{args.target}_barplot.svg"
    save_comparison_barplot(results, args.target, comparison_path)
    print(f"  Comparison barplot saved: {comparison_path}")
    
    print(f"\n★ All plots saved to {plots_dir}")

if __name__ == "__main__":
    main()

