#!/usr/bin/env python3
"""
Explore alternative ways to use the asymmetric-sine residual E while keeping
the RTBP feature family fixed.

Outputs are written to:
    Analysis/BP_Analysis/e_handling_results/
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import RidgeCV
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.model_selection import GroupKFold, TimeSeriesSplit
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

import explore_validation_axes as eva


ROOT = Path(__file__).resolve().parent
DATA_CSV = ROOT / "prepared_training_data.csv"
OUT_DIR = ROOT / "e_handling_results"
EPS = 1e-6


def build_estimator() -> Pipeline:
    alphas = np.concatenate([np.logspace(-8, -2, 13), np.logspace(-2, 1, 13)])
    return Pipeline([("scaler", StandardScaler()), ("reg", RidgeCV(alphas=alphas, cv=5))])


def add_e_features(df: pd.DataFrame) -> pd.DataFrame:
    work = df.copy()
    a = work["M1_A"].astype(float)
    hr = work["M1_HR"].astype(float)
    rise = work["M1_V2P_relTTP"].astype(float)
    fall = work["M1_P2V_relTTP"].astype(float)
    e = work["M2_E"].astype(float)

    work["E_raw"] = e
    work["E_over_A"] = e / (a.abs() + EPS)
    work["E_times_A"] = e * a
    work["E_times_HR"] = e * hr
    work["E_times_R"] = e * rise
    work["E_times_F"] = e * fall
    work["E_times_asym"] = e * (rise - fall)

    for n in [0.1, 0.25, 0.5, 1.0, 2.0]:
        label = f"{n:g}".replace(".", "p")
        factor = 1.0 + n * e
        work[f"A_mul_{label}"] = a * factor
        work[f"HR_mul_{label}"] = hr * factor
        work[f"R_mul_{label}"] = rise * factor
        work[f"F_mul_{label}"] = fall * factor
        work[f"A_div_{label}"] = a / factor
        work[f"HR_div_{label}"] = hr / factor
        work[f"R_div_{label}"] = rise / factor
        work[f"F_div_{label}"] = fall / factor

    return work


def method_specs() -> dict[str, dict]:
    base = ["M1_A", "M1_HR", "M1_V2P_relTTP", "M1_P2V_relTTP"]
    specs: dict[str, dict] = {
        "RTBP": {"cols": base, "needs_e": False, "family": "reference"},
        "sinBP_M": {"cols": ["M3_A", "M3_HR", "M3_Mean", "M3_Phi"], "needs_e": False, "family": "reference"},
        "sinBP_D_current": {
            "cols": ["M2_A", "M2_HR", "M2_V2P_relTTP", "M2_P2V_relTTP", "M2_E"],
            "needs_e": True,
            "family": "reference",
        },
        "RTBP_plus_E": {"cols": base + ["E_raw"], "needs_e": True, "family": "additive"},
        "RTBP_plus_E_over_A": {"cols": base + ["E_over_A"], "needs_e": True, "family": "normalized"},
        "RTBP_plus_AxE": {"cols": base + ["E_times_A"], "needs_e": True, "family": "interaction"},
        "RTBP_plus_HRxE": {"cols": base + ["E_times_HR"], "needs_e": True, "family": "interaction"},
        "RTBP_plus_RxE": {"cols": base + ["E_times_R"], "needs_e": True, "family": "interaction"},
        "RTBP_plus_FxE": {"cols": base + ["E_times_F"], "needs_e": True, "family": "interaction"},
        "RTBP_plus_AsymxE": {"cols": base + ["E_times_asym"], "needs_e": True, "family": "interaction"},
        "RTBP_plus_all_interactions": {
            "cols": base + ["E_raw", "E_times_A", "E_times_HR", "E_times_R", "E_times_F"],
            "needs_e": True,
            "family": "interaction",
        },
    }

    for n in [0.1, 0.25, 0.5, 1.0, 2.0]:
        label = f"{n:g}".replace(".", "p")
        specs[f"RTBP_scaled_mul_{label}"] = {
            "cols": [f"A_mul_{label}", f"HR_mul_{label}", f"R_mul_{label}", f"F_mul_{label}"],
            "needs_e": True,
            "family": "scaled_mul",
        }
        specs[f"RTBP_scaled_div_{label}"] = {
            "cols": [f"A_div_{label}", f"HR_div_{label}", f"R_div_{label}", f"F_div_{label}"],
            "needs_e": True,
            "family": "scaled_div",
        }
    return specs


def evaluate_scenario(
    raw_df: pd.DataFrame,
    target_col: str,
    split_name: str,
    window_seconds: float,
    centered: bool,
) -> pd.DataFrame:
    clean = eva.prepare_clean_df(raw_df, target_col)
    clean = add_e_features(clean)

    eval_target = target_col
    if centered:
        clean = clean.copy()
        eval_target = f"{target_col}_centered"
        clean[eval_target] = clean[target_col] - clean.groupby("subject_id")[target_col].transform("mean")

    rows = []
    for name, spec in method_specs().items():
        cols = spec["cols"]
        work = clean.dropna(subset=[eval_target]).copy()
        if spec["needs_e"]:
            work = work.dropna(subset=["M2_E"]).copy()
        work = work[work[cols].notna().all(axis=1)].copy()
        if len(work) < 20:
            continue

        if split_name == "timeseries":
            work = work.sort_values(["timestamp", "subject_id"]).reset_index(drop=True)
            split_iter = TimeSeriesSplit(n_splits=5).split(work)
        elif split_name == "groupkfold":
            split_iter = GroupKFold(n_splits=min(5, work["subject_id"].nunique())).split(work, groups=work["subject_id"])
        else:
            raise ValueError(split_name)

        x = work[cols].to_numpy()
        y = work[eval_target].to_numpy()
        g = work["subject_id"].astype(str).to_numpy()
        t = work["経過時間_秒"].to_numpy()
        fold_rows = []
        for fold_idx, (tr, te) in enumerate(split_iter, start=1):
            est = build_estimator()
            est.fit(x[tr], y[tr])
            pred = est.predict(x[te])
            y_eval, pred_eval = eva.aggregate_within_group(y[te], pred, g[te], t[te], window_seconds)
            if len(y_eval) < 2:
                continue
            corr = np.corrcoef(y_eval, pred_eval)[0, 1] if np.std(y_eval) > 0 and np.std(pred_eval) > 0 else np.nan
            fold_rows.append(
                {
                    "fold": fold_idx,
                    "mae": float(mean_absolute_error(y_eval, pred_eval)),
                    "rmse": float(np.sqrt(mean_squared_error(y_eval, pred_eval))),
                    "corr": float(corr) if np.isfinite(corr) else np.nan,
                }
            )
        if not fold_rows:
            continue

        fold_df = pd.DataFrame(fold_rows)
        rows.append(
            {
                "method": name,
                "family": spec["family"],
                "n": int(len(work)),
                "mae_mean": float(fold_df["mae"].mean()),
                "rmse_mean": float(fold_df["rmse"].mean()),
                "corr_mean": float(fold_df["corr"].mean(skipna=True)),
                "mae_sd": float(fold_df["mae"].std(ddof=1)) if len(fold_df) > 1 else 0.0,
                "rmse_sd": float(fold_df["rmse"].std(ddof=1)) if len(fold_df) > 1 else 0.0,
                "corr_sd": float(fold_df["corr"].std(ddof=1, skipna=True)) if len(fold_df) > 1 else 0.0,
            }
        )

    return pd.DataFrame(rows).sort_values(["mae_mean", "rmse_mean", "corr_mean"], ascending=[True, True, False])


def main() -> None:
    OUT_DIR.mkdir(exist_ok=True)
    raw_df = pd.read_csv(DATA_CSV)

    scenarios = {
        "abs_ts_sbp": ("ref_SBP", "timeseries", 0, False),
        "abs_ts_dbp": ("ref_DBP", "timeseries", 0, False),
        "abs_gkf_sbp": ("ref_SBP", "groupkfold", 0, False),
        "abs_gkf_dbp": ("ref_DBP", "groupkfold", 0, False),
        "trend_gkf20_sbp": ("ref_SBP", "groupkfold", 20, True),
        "trend_gkf20_dbp": ("ref_DBP", "groupkfold", 20, True),
    }

    summary_rows = []
    for label, (target, split_name, window_seconds, centered) in scenarios.items():
        res = evaluate_scenario(raw_df, target, split_name, window_seconds, centered)
        res.to_csv(OUT_DIR / f"{label}.csv", index=False)
        top = res.iloc[0].to_dict()
        top["scenario"] = label
        summary_rows.append(top)

    pd.DataFrame(summary_rows).to_csv(OUT_DIR / "summary_best_per_scenario.csv", index=False)


if __name__ == "__main__":
    main()
