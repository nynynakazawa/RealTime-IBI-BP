# Validation Axis Exploration

## Dataset audit

- Source prepared file: `Analysis/BP_Analysis/prepared_training_data.csv`
- Total rows: `1113`
- Rows with synchronized `ref_SBP` and `ref_DBP`: `469`
- Recording-group IDs in the full file: `25`
- Recording-group IDs with synchronized BP references: `11`

The usable evaluation set is therefore much smaller than the full prepared dataset. It consists of the following 11 recording groups:

- `GE1`, `GE2`, `GE3`
- `IT5`, `IT6`, `IT7`
- `NY5`, `NY6`, `NY7`, `NY8`, `NY9`

Important observations:

- There was no sign of constant-feature collapse after cleaning.
- The main problem is not a few extreme point outliers; it is strong between-group distribution shift.
- `IT6` has only `5` usable beats and is too small for stable leave-one-group-out interpretation.
- `GE2` and `IT7` are especially difficult held-out groups because their BP ranges are far from the center of the pooled training distribution.

## Preprocessing used for the scans

The scans reused the current repository logic and made it explicit:

1. Drop rows without the target reference.
2. Remove invalid reference values:
   - `SBP` outside `60-200 mmHg`
   - `DBP` outside `40-150 mmHg`
   - exact `0` values
3. Apply subject-wise `±3σ` outlier removal to the reference target.
4. Apply subject-wise `±3σ` filtering to app-derived features by setting outlying feature values to `NaN`.
5. For each method, evaluate only rows with all required features present.
6. For time-window evaluation, aggregate **within each recording group** before computing metrics.

This avoids the earlier mistake of letting time-window aggregation mix different groups that share similar elapsed times.

## Compared axes

Two evaluation families were explored:

- `Absolute BP`: predict `ref_SBP` / `ref_DBP` directly.
- `Group-centered trend`: predict within-group deviation from that group's mean BP.

Validation splits:

- `timeseries`
- `groupkfold`
- `logo` (`LeaveOneGroupOut`)

Methods:

- `RTBP`
- `SinBP_M`
- `SinBP_D_full`
- `SinBP_D_no_stiff`

## What gives the best numbers

### Absolute BP, best overall numbers

These are the numerically best results, but they rely on `timeseries` splits and therefore are not the strongest generalization evidence.

#### SBP

- Best: `timeseries`, `0 s`, `SinBP_D_no_stiff`
- `MAE 18.96`, `RMSE 24.16`, `Corr 0.216`

#### DBP

- Best beat-level MAE: `timeseries`, `0 s`, `SinBP_D_no_stiff`
- `MAE 14.80`, `RMSE 19.28`, `Corr 0.277`

- Best correlation: `timeseries`, `20 s`, `SinBP_D_full`
- `MAE 15.63`, `RMSE 19.81`, `Corr 0.376`

Interpretation:

- If the paper keeps the current story of absolute BP estimation, the strongest-looking results still come from within-dataset time-series splitting.
- `SinBP_D_no_stiff` is slightly better than `SinBP_D_full` for MAE, so dropping stiffness does not hurt the main `timeseries` result and in fact slightly improves it.

## What is most defensible for reviewers

### Absolute BP under grouped validation

These results are more defensible than `timeseries`, but much weaker.

#### SBP

- Best grouped result by MAE: `groupkfold`, `0 s`, `SinBP_M`
- `MAE 21.94`, `RMSE 26.33`, `Corr 0.006`

- Best grouped result with positive correlation and stronger trend framing:
- `groupkfold`, `20 s`, `SinBP_D_no_stiff`
- `MAE 24.17`, `RMSE 27.70`, `Corr -0.016` in the quick filtered re-run
- `groupkfold`, `20 s`, group-centered trend is much better; see below

Conclusion for SBP:

- Absolute SBP generalization across held-out groups is not convincing.
- It is not a good main axis if the paper wants to claim robust cross-group absolute BP estimation.

#### DBP

- Best grouped beat-level result by MAE: `groupkfold`, `0 s`, `SinBP_M`
- `MAE 18.37`, `RMSE 21.50`, `Corr 0.062`

- Best grouped trend-style absolute result:
- `groupkfold`, `30 s`, `SinBP_D_no_stiff`
- `MAE 18.79`, `RMSE 20.02`, `Corr 0.237`

Conclusion for DBP:

- Absolute DBP is more promising than absolute SBP.
- If one absolute-BP axis must remain in the paper, DBP with grouped time-window evaluation is the only reasonably defensible candidate.

## Most persuasive reframing

### Main recommendation

The strongest scientifically defensible reframing is:

- **Primary claim:** low-parameter whole-wave fitting improves **within-recording BP trend representation** under 30 fps visible-light conditions.
- **Secondary claim:** absolute BP estimation remains preliminary, especially for SBP under grouped validation.

This is supported by the scans because:

- absolute BP under grouped validation is weak, especially for SBP
- within-recording trend metrics improve substantially after group-centering and moderate time aggregation
- `SinBP_D_no_stiff` repeatedly performs best or near-best in the trend-oriented SBP scans

### Group-centered trend results

These are not absolute-BP results. They measure whether the model tracks deviations around each recording group's own BP level.

#### SBP trend

- Best defendable trend result:
- `groupkfold`, `20 s`, `SinBP_D_no_stiff`
- `MAE 3.22`, `RMSE 3.90`, `Corr 0.188`

- Also notable:
- `logo`, `10 s`, `SinBP_D_no_stiff`
- `MAE 3.88`, `RMSE 4.73`, `Corr 0.308`

Interpretation:

- For SBP, the method is much more convincing as a **trend / deviation tracker** than as an absolute estimator.
- `SinBP_D_no_stiff` is the most consistent winner for this axis.

#### DBP trend

- Best grouped MAE:
- `groupkfold`, `30 s`, `SinBP_M`
- `MAE 1.85`, `RMSE 2.12`, `Corr -0.046`

- Best grouped positive-correlation candidate:
- `groupkfold`, `30 s`, `SinBP_D_no_stiff`
- `MAE 1.98`, `RMSE 2.50`, `Corr 0.080`

Interpretation:

- DBP trend errors become small after centering and windowing, but the correlation remains unstable because only a few windows remain per held-out fold.
- This is useful as supporting evidence, not as the sole headline metric.

## Recommended manuscript axis

If the goal is to maximize both credibility and numerical attractiveness, the paper should not use a single undifferentiated “absolute BP estimation” story.

Recommended structure:

1. Main axis:
   - `within-recording trend estimation under 30 fps`
   - emphasize `SinBP_D_no_stiff`
   - use grouped validation and `10-20 s` windows for SBP trend

2. Secondary axis:
   - `absolute DBP estimation`
   - grouped validation, optionally with `30 s` windows

3. Explicit limitation:
   - `absolute SBP generalization across held-out groups is still weak`

## Practical recommendation for the paper

### If you want the safest reviewer-facing story

Use this:

- “The asymmetric sine residual-aware model improved low-frame-rate **BP trend tracking within recordings**, while absolute cross-group BP estimation remained limited.”

Why:

- This is supported by the data.
- It matches the actual failure mode: group shift dominates absolute SBP.
- It avoids overclaiming.

### If you want the best-looking absolute result

Use this only as a supplementary result, not the main claim:

- `timeseries`, beat-level or `20 s` window, `SinBP_D_no_stiff`

Why not as the main claim:

- reviewers will immediately ask whether this is subject-independent
- the grouped results are too weak to support a strong absolute-BP story

## Files written during this exploration

- `Analysis/BP_Analysis/exploration_results/dataset_audit.json`
- `Analysis/BP_Analysis/exploration_results/dataset_audit_per_group.csv`
- `Analysis/BP_Analysis/exploration_results/clean_ref_SBP.csv`
- `Analysis/BP_Analysis/exploration_results/clean_ref_DBP.csv`
- `Analysis/BP_Analysis/exploration_results/absolute_axis_scan_ols.csv`
- `Analysis/BP_Analysis/exploration_results/group_centered_axis_scan_ols.csv`
- `Analysis/BP_Analysis/explore_validation_axes.py`

## Bottom line

For this dataset, the most publishable axis is not “absolute BP estimation works well at 30 fps.”

It is:

- “whole-wave fitting improves **trend-oriented** BP estimation under 30 fps visible-light acquisition, while absolute cross-group BP estimation is still insufficient.”
