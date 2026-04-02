# Supplementary Results Memo

This note collects helper tables that support the current `main.tex` draft but are not all placed in the main paper.

## 1. Dataset Snapshot

- Prepared dataset size: `1113` extracted beats
- Beats with synchronized SBP and DBP references used for BP evaluation: `469`
- Recording-group IDs with synchronized BP references: `11`
- Source files:
  - `Analysis/BP_Analysis/exploration_results/absolute_axis_scan_ols.csv`
  - `Analysis/BP_Analysis/exploration_results/group_centered_axis_scan_ols.csv`
  - `Analysis/BP_Analysis/exploration_results/dataset_audit_per_group.csv`

## 2. Stricter Grouped Absolute Results

The main manuscript still reports repository-style time-series results for comparability, but the stricter grouped absolute results are weaker.

### 2.1 Best GroupKFold absolute SBP results

| Method | Window | MAE [mmHg] | RMSE [mmHg] | Corr |
| --- | ---: | ---: | ---: | ---: |
| sinBP(M) | 0 s | 21.94 | 26.33 | 0.006 |
| sinBP(D) | 0 s | 23.46 | 27.65 | -0.094 |
| RTBP | 0 s | 24.32 | 29.15 | -0.206 |

Interpretation: under grouped absolute SBP validation, none of the three methods generalize strongly.

### 2.2 Best GroupKFold absolute DBP results

| Method | Window | MAE [mmHg] | RMSE [mmHg] | Corr |
| --- | ---: | ---: | ---: | ---: |
| sinBP(M) | 0 s | 18.37 | 21.50 | 0.062 |
| sinBP(M) | 20 s | 18.53 | 21.18 | 0.108 |
| sinBP(D) | 30 s | 18.79 | 20.02 | 0.237 |
| RTBP | 0 s | 18.87 | 21.95 | 0.072 |

Interpretation: grouped DBP is less catastrophic than grouped SBP, but the margin between methods is still small and unstable.

## 3. Grouped Trend Window Comparison

The current manuscript uses `GroupKFold + group-centered targets + 20 s non-overlapping windows` as the representative trend setting.

### 3.1 Centered SBP trend MAE

| Window [s] | RTBP | sinBP(M) | sinBP(D) |
| ---: | ---: | ---: | ---: |
| 0 | 4.39 | 4.24 | 4.32 |
| 10 | 3.73 | 3.91 | 3.65 |
| 20 | 3.35 | 3.51 | 3.22 |
| 30 | 2.83 | 2.83 | 2.78 |

### 3.2 Centered DBP trend MAE

| Window [s] | RTBP | sinBP(M) | sinBP(D) |
| ---: | ---: | ---: | ---: |
| 0 | 3.59 | 3.53 | 3.54 |
| 10 | 3.20 | 3.12 | 3.25 |
| 20 | 2.84 | 2.75 | 2.88 |
| 30 | 2.00 | 1.85 | 1.98 |

### 3.3 Why the manuscript keeps 20 s as the representative trend condition

- For centered SBP, `30 s` gives the lowest MAE for all methods, but the mean correlation for `sinBP(D)` becomes negative.
- `20 s` still lowers SBP MAE relative to the beat-level setting and keeps a positive mean SBP correlation for `sinBP(D)`.
- This makes `20 s` easier to defend as a trend-tracking setting than `30 s`.

## 4. Stored Subject-Level Heterogeneity

The repository already contains subject-wise summary CSVs. They show that `sinBP(D)` does not improve every recording group.

### 4.1 SBP: groups where sinBP(D) was worse than RTBP in stored subject-wise MAPE

| Group ID | n | RTBP MAPE | sinBP(D) MAPE | Improvement |
| --- | ---: | ---: | ---: | ---: |
| GE1 | 24 | 23.26 | 23.57 | -0.30 |
| GE2 | 57 | 64.67 | 67.78 | -3.11 |
| IT7 | 16 | 24.75 | 25.69 | -0.94 |
| NY5 | 48 | 16.04 | 16.35 | -0.30 |
| NY9 | 69 | 6.00 | 7.99 | -1.99 |

Summary: `sinBP(D)` was better than RTBP for `6 / 11` SBP groups.

### 4.2 DBP: groups where sinBP(D) was worse than RTBP in stored subject-wise MAPE

| Group ID | n | RTBP MAPE | sinBP(D) MAPE | Improvement |
| --- | ---: | ---: | ---: | ---: |
| GE1 | 24 | 37.29 | 41.22 | -3.93 |
| GE2 | 57 | 113.76 | 114.31 | -0.55 |
| GE3 | 43 | 49.59 | 56.77 | -7.17 |
| IT5 | 24 | 27.45 | 46.65 | -19.19 |
| NY5 | 48 | 22.30 | 23.05 | -0.75 |
| NY6 | 70 | 6.14 | 6.18 | -0.04 |
| NY7 | 47 | 7.18 | 7.88 | -0.70 |
| NY8 | 66 | 9.73 | 11.01 | -1.29 |
| NY9 | 69 | 3.03 | 5.27 | -2.24 |

Summary: `sinBP(D)` was better than RTBP for only `2 / 11` DBP groups in this stored subject-wise comparison.

## 5. Practical Reading Guide for the Current Manuscript

- Use the time-series absolute results only as `repository-style representation comparison`.
- Use the grouped trend results as the main scientifically defensible claim.
- Do not claim that the current grouped absolute results demonstrate strong cross-group BP generalization.
