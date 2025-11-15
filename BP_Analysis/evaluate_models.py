"""
学習済みモデルの評価スクリプト

学習済みの係数を使用して、新しいデータセットで評価を行います。

使用方法:
    python evaluate_models.py --data_csv <CSVファイル> --coefficients_json <係数JSONファイル>
"""

import numpy as np
import pandas as pd
import argparse
import json
from sklearn.metrics import mean_absolute_error, mean_squared_error

def mape(y_true, y_pred):
    """Mean Absolute Percentage Error"""
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    mask = y_true > 0
    if np.sum(mask) == 0:
        return np.inf
    return np.mean(np.abs((y_pred[mask] - y_true[mask]) / y_true[mask])) * 100.0

def predict_with_coefficients(X, coefficients, intercept):
    """
    係数と切片を使用して予測
    
    Parameters:
    -----------
    X : np.array
        特徴量行列 (n_samples, n_features)
    coefficients : list or np.array
        係数のリスト
    intercept : float
        切片
    """
    return np.dot(X, coefficients) + intercept

def evaluate_method(df, feature_cols, target_col, coefficients, intercept, method_name):
    """
    1つの手法を評価
    
    Parameters:
    -----------
    df : pd.DataFrame
        データフレーム
    feature_cols : list
        特徴量カラム名のリスト
    target_col : str
        ターゲット（SBP/DBP）のカラム名
    coefficients : list or np.array
        係数のリスト
    intercept : float
        切片
    method_name : str
        手法名
    """
    # 特徴量の存在チェック
    available_features = [f for f in feature_cols if f in df.columns]
    if len(available_features) != len(feature_cols):
        print(f"Warning: Some features missing for {method_name}")
        return None
    
    # データの準備
    X = df[feature_cols].values
    y_true = df[target_col].values
    
    # 欠損値の処理
    valid_mask = ~(np.isnan(X).any(axis=1) | np.isnan(y_true))
    X = X[valid_mask]
    y_true = y_true[valid_mask]
    
    if len(X) == 0:
        print(f"Error: No valid samples for {method_name}")
        return None
    
    # 予測
    y_pred = predict_with_coefficients(X, coefficients, intercept)
    
    # 評価指標の計算
    mape_val = mape(y_true, y_pred)
    mae_val = mean_absolute_error(y_true, y_pred)
    rmse_val = np.sqrt(mean_squared_error(y_true, y_pred))
    
    # バイアス（平均誤差）
    bias = np.mean(y_pred - y_true)
    
    # 標準偏差
    std_error = np.std(y_pred - y_true)
    
    return {
        "method": method_name,
        "n_samples": len(X),
        "mape": mape_val,
        "mae": mae_val,
        "rmse": rmse_val,
        "bias": bias,
        "std_error": std_error,
        "predictions": y_pred.tolist(),
        "true_values": y_true.tolist(),
    }

def main():
    parser = argparse.ArgumentParser(description="学習済みモデルの評価")
    parser.add_argument("--data_csv", type=str, required=True, help="評価用CSVファイルのパス")
    parser.add_argument("--coefficients_json", type=str, required=True, help="係数JSONファイルのパス")
    parser.add_argument("--target", type=str, default="SBP", choices=["SBP", "DBP"], help="評価対象（SBP/DBP）")
    parser.add_argument("--output", type=str, help="結果出力ファイル（JSON）")
    
    args = parser.parse_args()

    # データ読み込み
    print(f"Loading data from {args.data_csv}...")
    df = pd.read_csv(args.data_csv)
    print(f"Loaded {len(df)} samples")

    # 係数の読み込み
    print(f"Loading coefficients from {args.coefficients_json}...")
    with open(args.coefficients_json, 'r') as f:
        coefficients_data = json.load(f)
    
    # 参照値のカラム名
    ref_col = f"ref_{args.target}"
    if ref_col not in df.columns:
        print(f"Error: {ref_col} column not found.")
        return
    
    # 参照値が欠損している行を除外
    df_valid = df.dropna(subset=[ref_col]).copy()
    print(f"Valid samples (with reference): {len(df_valid)}")

    if len(df_valid) == 0:
        print("Error: No valid samples with reference values.")
        return

    results = {}

    # 各手法を評価
    for method_name, method_data in coefficients_data.items():
        print(f"\n=== Evaluating {method_name} ===")
        
        feature_names = method_data["feature_names"]
        coefficients = np.array(method_data["coefficients"])
        intercept = method_data["intercept"]
        
        res = evaluate_method(
            df_valid, feature_names, ref_col,
            coefficients, intercept, method_name
        )
        
        if res is not None:
            results[method_name] = res
            print(f"MAPE: {res['mape']:.2f}%")
            print(f"MAE: {res['mae']:.2f}")
            print(f"RMSE: {res['rmse']:.2f}")
            print(f"Bias: {res['bias']:.2f}")
            print(f"Std Error: {res['std_error']:.2f}")

    # 結果のサマリー
    print("\n=== Summary ===")
    summary_data = []
    for method_name, res in results.items():
        summary_data.append({
            "method": method_name,
            "n_samples": res["n_samples"],
            "mape": res["mape"],
            "mae": res["mae"],
            "rmse": res["rmse"],
            "bias": res["bias"],
            "std_error": res["std_error"],
        })
        print(f"{method_name}: MAPE={res['mape']:.2f}%, MAE={res['mae']:.2f}, RMSE={res['rmse']:.2f}")

    # 結果を保存
    if args.output:
        with open(args.output, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"\nResults saved to {args.output}")
    else:
        # デフォルトの出力ファイル名
        output_file = f"evaluation_results_{args.target}.json"
        with open(output_file, 'w') as f:
            json.dump(results, f, indent=2)
        print(f"\nResults saved to {output_file}")

if __name__ == "__main__":
    main()

