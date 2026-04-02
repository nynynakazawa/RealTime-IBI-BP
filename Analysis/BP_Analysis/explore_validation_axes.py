#!/usr/bin/env python3
"""
Explore defensible validation axes for BP estimation.

This script audits the prepared dataset, applies explicit preprocessing,
and compares multiple evaluation settings:
  - beat-level vs time-window-aggregated evaluation
  - TimeSeriesSplit vs grouped validation
  - alternative feature-set definitions

Outputs are written to Analysis/BP_Analysis/exploration_results/.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
from sklearn.linear_model import LinearRegression, RidgeCV, HuberRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.model_selection import GroupKFold, LeaveOneGroupOut, TimeSeriesSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import train_bp_models as tbm


ROOT = Path(__file__).resolve().parent
DATA_CSV = ROOT / "prepared_training_data.csv"
OUT_DIR = ROOT / "exploration_results"


@dataclass(frozen=True)
class MethodSpec:
    name: str
    feature_cols: tuple[str, ...]


METHODS = [
    MethodSpec("RTBP", ("M1_A", "M1_HR", "M1_V2P_relTTP", "M1_P2V_relTTP")),
    MethodSpec("SinBP_M", ("M3_A", "M3_HR", "M3_Mean", "M3_Phi")),
    MethodSpec("SinBP_D_full", ("M2_A", "M2_HR", "M2_V2P_relTTP", "M2_P2V_relTTP", "M2_Stiffness", "M2_E")),
    MethodSpec("SinBP_D_no_stiffness", ("M2_A", "M2_HR", "M2_V2P_relTTP", "M2_P2V_relTTP", "M2_E")),
    MethodSpec("SinBP_D_E_only", ("M2_E",)),
    MethodSpec("SinBP_D_fit_only", ("M2_A", "M2_HR", "M2_V2P_relTTP", "M2_P2V_relTTP", "M2_Stiffness")),
]


def mape(y_true: np.ndarray, y_pred: np.ndarray) -> float:
    y_true = np.asarray(y_true, dtype=float)
    y_pred = np.asarray(y_pred, dtype=float)
    mask = y_true > 0
    return float(np.mean(np.abs((y_pred[mask] - y_true[mask]) / y_true[mask])) * 100.0)


def build_estimator(kind: str):
    if kind == "ols":
        reg = LinearRegression()
    elif kind == "ridge":
        reg = RidgeCV(alphas=np.concatenate([np.logspace(-8, -2, 13), np.logspace(-2, 1, 13)]), cv=5)
    elif kind == "huber":
        reg = HuberRegressor()
    else:
        raise ValueError(kind)
    return Pipeline([("scaler", StandardScaler()), ("reg", reg)])


def aggregate_within_group(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    group_ids: Iterable[str],
    time_values: np.ndarray,
    window_seconds: float,
) -> tuple[np.ndarray, np.ndarray]:
    if window_seconds <= 0:
        return np.asarray(y_true, dtype=float), np.asarray(y_pred, dtype=float)

    df = pd.DataFrame(
        {
            "group_id": np.asarray(list(group_ids)),
            "time_s": np.asarray(time_values, dtype=float),
            "y_true": np.asarray(y_true, dtype=float),
            "y_pred": np.asarray(y_pred, dtype=float),
        }
    )
    df = df[np.isfinite(df["time_s"]) & np.isfinite(df["y_true"]) & np.isfinite(df["y_pred"])].copy()
    if df.empty:
        return np.array([]), np.array([])

    df["window_id"] = np.floor(df["time_s"] / window_seconds).astype(int)
    agg = (
        df.groupby(["group_id", "window_id"], sort=True)[["y_true", "y_pred"]]
        .mean()
        .reset_index()
    )
    return agg["y_true"].to_numpy(), agg["y_pred"].to_numpy()


def eval_config(
    df: pd.DataFrame,
    method: MethodSpec,
    target_col: str,
    split_name: str,
    estimator_kind: str,
    window_seconds: float,
) -> dict:
    feature_cols = [c for c in method.feature_cols if c in df.columns]
    if len(feature_cols) != len(method.feature_cols):
        return {"status": "missing_features"}

    work = df.dropna(subset=[target_col]).copy()
    valid_mask = work[feature_cols].notna().all(axis=1)
    work = work.loc[valid_mask].copy()
    if len(work) < 20:
        return {"status": "too_few_samples", "n_samples": len(work)}

    if split_name == "timeseries":
        work = work.sort_values(["timestamp", "subject_id"]).reset_index(drop=True)
        splitter = TimeSeriesSplit(n_splits=5)
        split_iter = splitter.split(work)
    elif split_name == "groupkfold":
        groups = work["subject_id"].to_numpy()
        n_splits = min(5, len(np.unique(groups)))
        splitter = GroupKFold(n_splits=n_splits)
        split_iter = splitter.split(work, groups=groups)
    elif split_name == "logo":
        groups = work["subject_id"].to_numpy()
        splitter = LeaveOneGroupOut()
        split_iter = splitter.split(work, groups=groups)
    else:
        raise ValueError(split_name)

    X = work[feature_cols].to_numpy()
    y = work[target_col].to_numpy()
    t = work["経過時間_秒"].to_numpy()
    g = work["subject_id"].astype(str).to_numpy()

    fold_rows = []
    all_true = []
    all_pred = []
    all_group = []
    for fold_idx, (tr, te) in enumerate(split_iter, start=1):
        est = build_estimator(estimator_kind)
        est.fit(X[tr], y[tr])
        pred = est.predict(X[te])
        y_eval, pred_eval = aggregate_within_group(y[te], pred, g[te], t[te], window_seconds)
        if len(y_eval) < 2:
            continue
        corr = np.corrcoef(y_eval, pred_eval)[0, 1] if np.std(y_eval) > 0 and np.std(pred_eval) > 0 else np.nan
        fold_rows.append(
            {
                "fold": fold_idx,
                "n_test_points": int(len(te)),
                "n_eval_points": int(len(y_eval)),
                "mape": mape(y_eval, pred_eval),
                "mae": float(mean_absolute_error(y_eval, pred_eval)),
                "rmse": float(np.sqrt(mean_squared_error(y_eval, pred_eval))),
                "corr": float(corr) if np.isfinite(corr) else np.nan,
            }
        )
        all_true.extend(y_eval.tolist())
        all_pred.extend(pred_eval.tolist())
        all_group.extend(g[te][: len(y_eval)].tolist() if window_seconds <= 0 else [])

    if not fold_rows:
        return {"status": "no_valid_folds", "n_samples": len(work)}

    folds = pd.DataFrame(fold_rows)
    return {
        "status": "ok",
        "n_samples": int(len(work)),
        "n_groups": int(work["subject_id"].nunique()),
        "split": split_name,
        "window_seconds": window_seconds,
        "estimator": estimator_kind,
        "method": method.name,
        "target": target_col,
        "mape_mean": float(folds["mape"].mean()),
        "mape_std": float(folds["mape"].std(ddof=1)) if len(folds) > 1 else 0.0,
        "mae_mean": float(folds["mae"].mean()),
        "mae_std": float(folds["mae"].std(ddof=1)) if len(folds) > 1 else 0.0,
        "rmse_mean": float(folds["rmse"].mean()),
        "rmse_std": float(folds["rmse"].std(ddof=1)) if len(folds) > 1 else 0.0,
        "corr_mean": float(folds["corr"].mean(skipna=True)),
        "corr_std": float(folds["corr"].std(ddof=1, skipna=True)) if len(folds) > 1 else 0.0,
        "fold_details": fold_rows,
    }


def audit_dataset(df: pd.DataFrame) -> dict:
    eval_df = df[df["ref_SBP"].notna() & df["ref_DBP"].notna()].copy()
    per_group = (
        eval_df.groupby("subject_id")
        .agg(
            n_samples=("subject_id", "size"),
            sbp_min=("ref_SBP", "min"),
            sbp_max=("ref_SBP", "max"),
            dbp_min=("ref_DBP", "min"),
            dbp_max=("ref_DBP", "max"),
            m2e_mean=("M2_E", "mean"),
            m2e_std=("M2_E", "std"),
        )
        .sort_values("n_samples", ascending=False)
        .reset_index()
    )
    return {
        "total_rows": int(len(df)),
        "rows_with_both_refs": int(len(eval_df)),
        "all_subject_ids": sorted(df["subject_id"].astype(str).unique().tolist()),
        "eval_subject_ids": sorted(eval_df["subject_id"].astype(str).unique().tolist()),
        "per_group": per_group.to_dict(orient="records"),
    }


def prepare_clean_df(df: pd.DataFrame, target_col: str) -> pd.DataFrame:
    df_valid = df.dropna(subset=[target_col]).copy()
    time_col = "経過時間_秒" if "経過時間_秒" in df_valid.columns else "timestamp"
    df_valid = tbm.remove_outliers(df_valid, target_col, time_col=time_col, subject_col="subject_id")

    feature_cols = [c for c in df_valid.columns if c.startswith(("M1_", "M2_", "M3_"))]
    for col in feature_cols:
        if not pd.api.types.is_numeric_dtype(df_valid[col]):
            continue
        for sid in df_valid["subject_id"].unique():
            mask = df_valid["subject_id"] == sid
            x = df_valid.loc[mask, col]
            if len(x) < 3:
                continue
            std = x.std()
            if std and std > 0:
                z = np.abs((x - x.mean()) / std)
                df_valid.loc[mask & (z > 3.0), col] = np.nan
    return df_valid


def main():
    OUT_DIR.mkdir(exist_ok=True)
    df = pd.read_csv(DATA_CSV)

    audit = audit_dataset(df)
    (OUT_DIR / "dataset_audit.json").write_text(json.dumps(audit, indent=2, ensure_ascii=False))
    pd.DataFrame(audit["per_group"]).to_csv(OUT_DIR / "dataset_audit_per_group.csv", index=False)

    result_rows = []
    for target_col in ["ref_SBP", "ref_DBP"]:
        clean_df = prepare_clean_df(df, target_col)
        clean_df.to_csv(OUT_DIR / f"clean_{target_col}.csv", index=False)

        for method in METHODS:
            for split_name in ["timeseries", "groupkfold", "logo"]:
                for estimator_kind in ["ols", "ridge", "huber"]:
                    for window_seconds in [0, 5, 10, 15, 20, 30]:
                        res = eval_config(clean_df, method, target_col, split_name, estimator_kind, window_seconds)
                        result_rows.append(res)

    results_df = pd.DataFrame(result_rows)
    results_df.to_csv(OUT_DIR / "validation_axis_comparison.csv", index=False)

    ok_df = results_df[results_df["status"] == "ok"].copy()
    for target_col in ["ref_SBP", "ref_DBP"]:
        sub = ok_df[ok_df["target"] == target_col].copy()
        sub.sort_values(["split", "window_seconds", "mae_mean", "rmse_mean"], inplace=True)
        sub.to_csv(OUT_DIR / f"validation_axis_comparison_{target_col}.csv", index=False)

        best_defensible = sub[sub["split"].isin(["groupkfold", "logo"])].sort_values(
            ["mae_mean", "rmse_mean", "corr_mean"], ascending=[True, True, False]
        ).head(10)
        best_defensible.to_csv(OUT_DIR / f"best_defensible_{target_col}.csv", index=False)

        best_absolute = sub.sort_values(
            ["mae_mean", "rmse_mean", "corr_mean"], ascending=[True, True, False]
        ).head(10)
        best_absolute.to_csv(OUT_DIR / f"best_absolute_{target_col}.csv", index=False)

    print(f"Wrote results to {OUT_DIR}")


if __name__ == "__main__":
    main()
