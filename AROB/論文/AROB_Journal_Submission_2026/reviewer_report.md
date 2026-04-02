# Reviewer Report for the Current Draft

## Overall Verdict

`Major Revision`

The manuscript is no longer an internal-looking conference-extension draft. The method definition is now clearer, the `sinBP(D)` description is aligned with the current `RTBP + E` logic, and the paper is more careful about distinguishing absolute BP estimation from within-recording trend estimation. However, the scientific risks remain substantial. The paper is still limited by the absence of explicit participant/session structure, the weakness of grouped absolute-BP validation, the lack of ablation, and thin literature positioning.

## What Improved

### 1. The method identity is now clearer

The revised draft no longer mixes multiple incompatible definitions of `sinBP(D)`. It now describes `sinBP(D)` as the RTBP feature set augmented by the asymmetric-sine residual `E`, which is much easier to defend than the earlier mixture of residual and stiffness-style descriptions.

### 2. The trend-oriented task is now defined rather than merely asserted

The manuscript now defines the centered target explicitly and explains the use of non-overlapping time-window averaging within each recording group. This is an important improvement because it turns the trend claim into a measurable evaluation setting rather than a rhetorical reinterpretation of the same absolute-BP table.

### 3. The manuscript structure is more coherent

The paper now separates waveform fitting, absolute-BP estimation, and grouped trend-oriented reanalysis into distinct result blocks. This is a substantial improvement in readability and reduces the earlier confusion between “absolute estimation” and “trend tracking.”

### 4. Claims are better calibrated to the evidence

The manuscript now acknowledges that absolute cross-group estimation remains weak, especially for SBP, and that the current data support a narrower trend-tracking interpretation more convincingly than a strong absolute-BP claim. This calibration is appropriate.

## Remaining Serious Problems

### 1. Absolute-BP evaluation is still not reviewer-proof

The absolute-BP table still relies on time-series splitting. Even though the manuscript now openly acknowledges this limitation, the underlying weakness remains. A reviewer will still ask whether the apparent gain is mostly due to within-group regularity rather than cross-group generalization.

### 2. The effective sample structure is still unclear

The manuscript refers to recording-group IDs and synchronized beats, but it still does not establish the true participant/session structure. Without that information, it is difficult to judge the independence of samples and the strength of the evidence.

### 3. The improvement mechanism is not isolated

The current definition of `sinBP(D)` is now cleaner, but the manuscript still does not isolate how much of the gain is specifically attributable to `E`. An ablation study is still needed to distinguish “RTBP only,” “fitted-parameter descriptors,” and “RTBP + residual.”

### 4. The trend-oriented result is mainly convincing for SBP, not uniformly for both targets

The grouped trend table is more persuasive for centered SBP than for centered DBP. DBP remains mixed, with small differences among methods and weak correlations. This means the current story is most defensible as an SBP-trend paper with DBP as supporting evidence, not as a uniformly improved BP-estimation framework.

### 5. The related-work section remains thin

The manuscript now has a dedicated related-work section, but it still reads more like a short orientation paragraph than a journal-level positioning argument. It does not yet convincingly place the work relative to direct alternatives in low-frame-rate rPPG, camera-based BP estimation, parametric waveform modeling, and personalized/trend-oriented estimation.

## What Codex Already Fixed

- Aligned the manuscript definition of `sinBP(D)` with the current `RTBP + E` logic
- Added an explicit trend-target definition
- Added the non-overlapping window-averaging definition
- Reorganized the results into waveform, absolute BP, and trend-oriented tables
- Updated the absolute-BP numbers to the current `sinBP(D)` setting
- Reframed the Bland--Altman figure as a secondary absolute-estimation diagnostic

## What Still Requires Author-Level Input

### 1. Participant/session mapping

The authors need to provide the mapping between recording-group IDs, participants, and sessions. This cannot be inferred safely from the exported files alone.

### 2. Primary validation choice

The authors need to decide whether the journal version will explicitly prioritize grouped trend evaluation over absolute-BP estimation. The current data strongly favor that choice, but it is still a scientific framing decision.

### 3. Ablation scope

The authors need to decide how much additional analysis to add, but at minimum the paper should compare:

- RTBP
- sinBP(M)
- RTBP + E

and ideally an intermediate fitted-feature baseline as well.

### 4. Literature scope

The authors need to decide how broadly to position the work: as a low-frame-rate rPPG representation study, a camera-based BP estimation study, or a trend-oriented BP estimation study. The current manuscript implicitly moves toward the third option.

## Practical Recommendation

If the goal is to maximize the probability of acceptance, the manuscript should continue to center its claim on:

`whole-wave fitting improves trend-oriented BP estimation under 30 fps visible-light acquisition`

and should treat absolute cross-group BP estimation as a secondary, still-limited result. That is the most defensible interpretation of the current data.
