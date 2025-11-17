### 連続血圧計との比較における3手法のMAPE評価手順（係数推定を含む）

このドキュメントは、現在の3つの線形回帰手法を連続血圧計の参照値と比較し、MAPEで定量評価するための実務的な手順と、係数（回帰重み）の推定方法を複数提示します。各手法で使う特徴量（パラメータ）が異なっていても、公平に比較できるように設計しています。現在の係数は仮置きなので、ここで示す方法で再学習し、評価してください。

---

#### 目的と評価指標
- **目的**: 3手法それぞれが推定する血圧（SBP/DBP/MAPのいずれか/複数）を、連続血圧計の参照値と比較し、一般化性能をMAPEで定量評価する
- **MAPE**（Mean Absolute Percentage Error）:
  \[
  \mathrm{MAPE} = \frac{100}{n}\sum_{i=1}^{n}\left|\frac{\hat{y}_i - y_i}{y_i}\right|
  \]
  - 血圧は正値のため0除算は通常起きませんが、参照値が異常に小さいサンプルは除外/Winsorizeを推奨
  - 参考: 外れ値や極端値に頑健な指標として **sMAPE** や **MAE/RMSE** も併記可

---

#### データ準備（最低限）
- 必須カラム例（CSVなど）:
  - `timestamp`（ms もしくは ISO8601）
  - `subject_id`（被験者ID。LOSO評価に必須）
  - `ref_BP`（連続血圧計の参照値。ターゲット: SBP/DBP/MAPのいずれか）
  - 手法1の特徴量群: `m1_f1, m1_f2, ...`
  - 手法2の特徴量群: `m2_f1, m2_f2, ...`
  - 手法3の特徴量群: `m3_f1, m3_f2, ...`
- **同期/アライメント**:
  - 参照値と特徴量を時間で整列（同一サンプリング周期にリサンプリング、もしくは最も近い時刻で紐付け）
  - スムージングや移動統計は将来情報を使わない「因果」計算にする（データ漏洩防止）
- **クリーニング**:
  - 異常値・アーチファクト除去
  - 外れ値のWinsorize（上位/下位1%など）を検討

---

#### 分割設計（公平な比較の鍵）
- 3手法は「異なる特徴量」を使っていても、**同一の学習/検証分割**を共有してください。これにより、MAPEの比較が公平になります。
- 推奨スキーム（用途に応じて選択）:
  - **被験者独立評価（LOSO/GroupKFold）推奨**:
    - 学習: 一部被験者、検証: 未見の別被験者
    - 汎化性能を厳密に確認可能
  - **時系列分割（TimeSeriesSplit）**:
    - 過去で学習し未来を評価（リーク防止）
  - **キャリブレーション + 残り評価**:
    - 各被験者の最初の一定時間・一定本数で係数を推定、以降で評価（実運用を想定）

---

#### 係数の算出方法（複数アプローチ）
- すべてのアプローチで、標準化を使う場合は「学習Foldのみで推定した平均/分散」を用い、検証Foldにはその統計量だけを適用（リーク防止）
- 係数は手法ごとに「その手法の特徴量のみ」で推定します

- 方法A: **通常の最小二乗（OLS）**
  - 利点: 係数が解釈しやすい、実装が簡単
  - 欠点: 多重共線性や外れ値に弱い

- 方法B: **正則化回帰（Ridge/Lasso/ElasticNet）**
  - 利点: 過学習の抑制、特徴量選択（Lasso）
  - 推奨: 内部CVでハイパーパラメータ（αなど）最適化

- 方法C: **制約付き線形回帰**
  - 例: 生理的整合性のため「係数は非負」「特定特徴量は負」など符号制約
  - scikit-learnの `LinearRegression(positive=true)`（非負）または最小二乗の二次計画問題化（cvxpy等）

- （任意）方法D: **ロバスト回帰（Huber/Quantile）**
  - 外れ値の影響を低減。MAPE評価前の一次モデルとして有用

---

#### Python実装例（学習・評価の雛形）
以下は1つのターゲット（例: MAP）に対し、3手法を同一分割で学習・MAPE評価する雛形です。各手法の特徴量名を差し替えて利用してください。

```python
import numpy as np
import pandas as pd
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.linear_model import LinearRegression, RidgeCV, LassoCV, ElasticNetCV, HuberRegressor
from sklearn.model_selection import GroupKFold, TimeSeriesSplit
from sklearn.metrics import make_scorer

def mape(y_true, y_pred):
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    return np.mean(np.abs((y_pred - y_true) / y_true)) * 100.0

def eval_one_method(df, feature_cols, target_col, groups=None, split_strategy="groupkfold", n_splits=5,
                    estimator_kind="ols"):
    if split_strategy == "groupkfold":
        assert groups is not None
        splitter = GroupKFold(n_splits=n_splits)
        split_iter = splitter.split(df, groups=groups)
    elif split_strategy == "timeseries":
        splitter = TimeSeriesSplit(n_splits=n_splits)
        # 時系列は事前に時刻でソートしておくこと
        df_sorted = df.sort_values("timestamp").reset_index(drop=True)
        df = df_sorted
        split_iter = splitter.split(df)
    else:
        raise ValueError("unknown split_strategy")

    X_all = df[feature_cols].values
    y_all = df[target_col].values

    mape_list = []
    coefs_list = []
    intercept_list = []
    scaler_stats = []  # 推定時の平均・標準偏差（Androidへ持ち込みたい場合）

    for train_idx, test_idx in split_iter:
        X_tr, X_te = X_all[train_idx], X_all[test_idx]
        y_tr, y_te = y_all[train_idx], y_all[test_idx]

        # パイプライン（標準化は任意。使う場合は学習Foldで統計量を推定）
        if estimator_kind == "ols":
            est = LinearRegression()
        elif estimator_kind == "ridge":
            est = RidgeCV(alphas=np.logspace(-4, 4, 20), cv=5)
        elif estimator_kind == "lasso":
            est = LassoCV(alphas=None, cv=5, max_iter=10000)
        elif estimator_kind == "enet":
            est = ElasticNetCV(l1_ratio=[.1,.3,.5,.7,.9,.95,1.0], alphas=None, cv=5, max_iter=10000)
        elif estimator_kind == "huber":
            est = HuberRegressor()
        elif estimator_kind == "nonneg_ols":
            est = LinearRegression(positive=True)
        else:
            raise ValueError("unknown estimator_kind")

        pipe = Pipeline([
            ("scaler", StandardScaler(with_mean=True, with_std=True)),
            ("reg", est),
        ])
        pipe.fit(X_tr, y_tr)
        y_hat = pipe.predict(X_te)
        fold_mape = mape(y_te, y_hat)
        mape_list.append(fold_mape)

        # 係数の取り出し（標準化を使っているため、Androidへ実装する場合は統計量も保存）
        scaler = pipe.named_steps["scaler"]
        reg = pipe.named_steps["reg"]
        # 注意: パイプライン係数はスケーリング込みの空間なので、Android側で同じ標準化を再現するか、
        # ここで非標準化の係数に戻して保存してください（下記は非標準化への戻し方の例）。
        coef_std = reg.coef_
        intercept_std = reg.intercept_
        # 非標準化（原空間）の係数へ戻す
        coef_real = coef_std / (scaler.scale_ + 1e-12)
        intercept_real = intercept_std - np.sum(coef_std * scaler.mean_ / (scaler.scale_ + 1e-12))

        coefs_list.append(coef_real)
        intercept_list.append(intercept_real)
        scaler_stats.append({"mean": scaler.mean_.copy(), "scale": scaler.scale_.copy()})

    return {
        "mape_mean": float(np.mean(mape_list)),
        "mape_std": float(np.std(mape_list, ddof=1)),
        "mape_each_fold": mape_list,
        "coef_each_fold": coefs_list,
        "intercept_each_fold": intercept_list,
        "scaler_stats_each_fold": scaler_stats,
    }

# 使い方例
# df = pd.read_csv("bp_dataset.csv")
# groups = df["subject_id"].values
# method1_features = ["m1_f1","m1_f2","m1_f3"]
# method2_features = ["m2_f1","m2_f2"]
# method3_features = ["m3_f1","m3_f2","m3_f3","m3_f4"]
# target = "ref_BP"
#
# res1 = eval_one_method(df, method1_features, target, groups, "groupkfold", 5, "ols")
# res2 = eval_one_method(df, method2_features, target, groups, "groupkfold", 5, "ridge")
# res3 = eval_one_method(df, method3_features, target, groups, "groupkfold", 5, "nonneg_ols")
#
# print("Method1 OLS MAPE:", res1["mape_mean"], "+/-", res1["mape_std"])
# print("Method2 Ridge MAPE:", res2["mape_mean"], "+/-", res2["mape_std"])
# print("Method3 NonNeg OLS MAPE:", res3["mape_mean"], "+/-", res3["mape_std"])
```

（参考）符号制約が混在する場合は `cvxpy` による二次計画問題で実装できます（非負以外の制約が必要なとき）。

```python
# pip install cvxpy
import cvxpy as cp
import numpy as np

def constrained_ls(X, y, sign_constraints):
    """
    sign_constraints: 長さpの配列で、1 -> w>=0, -1 -> w<=0, 0 -> 制約なし
    """
    n, p = X.shape
    w = cp.Variable(p)
    b = cp.Variable()
    objective = cp.Minimize(cp.sum_squares(X @ w + b - y))
    constraints = []
    for j, sc in enumerate(sign_constraints):
        if sc == 1:
            constraints.append(w[j] >= 0)
        elif sc == -1:
            constraints.append(w[j] <= 0)
    prob = cp.Problem(objective, constraints)
    prob.solve()
    return w.value, b.value
```

---

#### MAPEの集計と不確実性
- 各FoldのMAPEから「平均±標準偏差」を報告
- **ブートストラップ**（被験者単位の再標本化）で95%信頼区間を付与するとより堅牢
- 併せて以下も推奨:
  - **MAE/RMSE**、平均誤差（バイアス）
  - 被験者別MAPE（公平性の確認）
  - Bland–Altmanプロット（臨床的妥当性の視覚化）

---

#### Androidへの実装反映の注意
- 学習に標準化を使った場合、Android側でも同じ平均`mean_i`と標準偏差`std_i`を保持する必要があります。非標準化（原空間）に戻した係数・切片を保存しておけば、Android側ではそのまま
  ```
  y_hat = intercept_real + Σ_i (coef_real_i * x_i)
  ```
  で推定可能です。
- すでに標準化前提のコードがある場合は、`mean_i`/`std_i`を定数として実装し、以下の順で計算:
  ```
  z_i = (x_i - mean_i) / std_i
  y_hat = intercept_std + Σ_i (coef_std_i * z_i)
  ```
- 係数を複数Foldから得た場合は、以下いずれかで本番係数を決めます:
  - Fold平均の係数（安定性重視）
  - ベストFold（最小MAPE）の係数（性能重視・過学習に注意）
  - 追加のホールドアウトデータで再学習（推奨）

---

#### レポート出力（例）
```python
summary = pd.DataFrame([
    {"method": "M1-OLS", "mape_mean": res1["mape_mean"], "mape_std": res1["mape_std"]},
    {"method": "M2-Ridge", "mape_mean": res2["mape_mean"], "mape_std": res2["mape_std"]},
    {"method": "M3-NonNegOLS", "mape_mean": res3["mape_mean"], "mape_std": res3["mape_std"]},
]).sort_values("mape_mean")
print(summary)
```

---

#### 実務的なおすすめ構成
- 被験者独立（GroupKFold=5）で、各手法につき「A:OLS」「B:Ridge」「C:非負OLS」を評価
- 指標は「MAPE（主）+ MAE（副）」、被験者別MAPEも併記
- もっとも良いスキームを選んだ後、全学習データで最終係数を再推定し、Androidへ反映

---

#### 補足（参考）
- 臨床評価基準（AAMI/BHS）ではMAPEでなく「平均誤差・標準偏差」を使うことが多いので、最終報告ではそれらの併記も推奨
- 時刻同期や前処理の一貫性がMAPEに大きく効きます。評価コードは必ず固定のランダムシード・同一の分割で再現可能にすること

---

本ドキュメントの手順・コード断片をそのままテンプレートとして使えば、3手法が異なる特徴量であっても、公平な分割・学習・MAPE算出が可能です。現在の仮係数は、ここで示した任意の方法（例: Ridge + GroupKFold）で再推定して置き換えてください。


