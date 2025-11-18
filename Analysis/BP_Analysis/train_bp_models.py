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
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LinearRegression, RidgeCV, LassoCV, ElasticNetCV, HuberRegressor
from sklearn.model_selection import GroupKFold, TimeSeriesSplit
from sklearn.metrics import mean_absolute_error, mean_squared_error
import json
from datetime import datetime

def mape(y_true, y_pred):
    """Mean Absolute Percentage Error"""
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    # 0除算を避ける
    mask = y_true > 0
    if np.sum(mask) == 0:
        return np.inf
    return np.mean(np.abs((y_pred[mask] - y_true[mask]) / y_true[mask])) * 100.0

def eval_one_method(df, feature_cols, target_col, groups=None, split_strategy="groupkfold", 
                    n_splits=5, estimator_kind="ols", method_name="Method"):
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
    if split_strategy == "groupkfold":
        assert groups is not None, "GroupKFold requires groups"
        splitter = GroupKFold(n_splits=n_splits)
        split_iter = splitter.split(df, groups=groups)
    elif split_strategy == "timeseries":
        splitter = TimeSeriesSplit(n_splits=n_splits)
        df_sorted = df.sort_values("timestamp").reset_index(drop=True)
        df = df_sorted
        split_iter = splitter.split(df)
    else:
        raise ValueError(f"Unknown split_strategy: {split_strategy}")

    X_all = df[feature_cols].values
    y_all = df[target_col].values

    mape_list = []
    mae_list = []
    rmse_list = []
    coefs_list = []
    intercept_list = []
    scaler_stats = []

    for fold_idx, (train_idx, test_idx) in enumerate(split_iter):
        X_tr, X_te = X_all[train_idx], X_all[test_idx]
        y_tr, y_te = y_all[train_idx], y_all[test_idx]

        # パイプライン構築
        if estimator_kind == "ols":
            est = LinearRegression()
        elif estimator_kind == "ridge":
            est = RidgeCV(alphas=np.logspace(-4, 4, 20), cv=5)
        elif estimator_kind == "lasso":
            est = LassoCV(alphas=None, cv=5, max_iter=10000)
        elif estimator_kind == "enet":
            est = ElasticNetCV(l1_ratio=[.1, .3, .5, .7, .9, .95, 1.0], alphas=None, cv=5, max_iter=10000)
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
        
        pipe.fit(X_tr, y_tr)
        y_hat = pipe.predict(X_te)
        
        fold_mape = mape(y_te, y_hat)
        fold_mae = mean_absolute_error(y_te, y_hat)
        fold_rmse = np.sqrt(mean_squared_error(y_te, y_hat))
        
        mape_list.append(fold_mape)
        mae_list.append(fold_mae)
        rmse_list.append(fold_rmse)

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
    }

def main():
    parser = argparse.ArgumentParser(description="3手法の血圧推定モデルの学習・評価")
    parser.add_argument("--data_csv", type=str, required=True, help="学習用CSVファイルのパス")
    parser.add_argument("--output_dir", type=str, default="./results", help="結果出力ディレクトリ")
    parser.add_argument("--split_strategy", type=str, default="groupkfold", choices=["groupkfold", "timeseries"],
                        help="データ分割戦略")
    parser.add_argument("--n_splits", type=int, default=5, help="分割数")
    parser.add_argument("--target", type=str, default="SBP", choices=["SBP", "DBP"], help="評価対象（SBP/DBP）")
    parser.add_argument("--estimator", type=str, default="ridge", 
                        choices=["ols", "ridge", "lasso", "enet", "huber", "nonneg_ols"],
                        help="推定器の種類")
    
    args = parser.parse_args()

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
    
    groups = df_valid["subject_id"].values if args.split_strategy == "groupkfold" else None

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
            args.split_strategy, args.n_splits, args.estimator, "RealTimeBP"
        )
        results["RealTimeBP"] = res1

    # SinBP_D評価
    if sinbp_d_features:
        print(f"\n=== Evaluating SinBP_D (Distortion based) for {args.target} ===")
        res2 = eval_one_method(
            df_valid, sinbp_d_features, ref_col, groups,
            args.split_strategy, args.n_splits, args.estimator, "SinBP_D"
        )
        results["SinBP_D"] = res2

    # SinBP_M評価
    if sinbp_m_features:
        print(f"\n=== Evaluating SinBP_M (Model based) for {args.target} ===")
        res3 = eval_one_method(
            df_valid, sinbp_m_features, ref_col, groups,
            args.split_strategy, args.n_splits, args.estimator, "SinBP_M"
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

if __name__ == "__main__":
    main()

