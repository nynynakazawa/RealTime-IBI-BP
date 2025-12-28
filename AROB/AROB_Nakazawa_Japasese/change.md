# AROB.tex 修正内容一覧

## 1. 序論（Introduction）への追記

### 1.1 30fpsにおける定量的問題点の明記
- サンプリング間隔が約33msであり、ピーク位置誤差が±16.5ms以上生じ得ることを追記
- 心拍数60-80bpm（周期750-1000ms）において、相対的なタイミング誤差が1.6-2.2%となることを明記
- ナイキスト定理により最大検出可能周波数が15Hzに制限され、PPGの2次・3次以上の高調波（通常10-20Hz）が取得不能であることを追記
- これらの制約によりRTBPベースの血圧推定が分散増加・再現性低下を示す具体的理由を説明

**English excerpt:**
> "At 30 fps, the sampling interval is approximately 33 ms, meaning peak position errors can reach ±16.5 ms or more. For a typical heart rate of 60–80 bpm (period 750–1000 ms), this corresponds to a relative timing error of 1.6–2.2%, substantially degrading morphological feature accuracy. Furthermore, according to the Nyquist theorem, the maximum detectable frequency is limited to 15 Hz, making it impossible to capture PPG harmonics above the 2nd or 3rd order (typically extending to 10–20 Hz). This loss of high-frequency components critically impairs waveform detail analysis. Under these conditions, morphological features that depend on fine timing (peak positions, TTP) become unstable, causing RTBP-based blood pressure estimation to exhibit increased variance and reduced reproducibility."

### 1.2 Gamma/skewed-Gaussianモデルの不安定性の説明
- 既存の複雑なモデル（GammaモデルやSkewed-Gaussianモデル）は、1つの波形を表現するのにも多くのパラメータ（4〜20個）を必要とすることを説明
- 30fpsの低フレームレートでは、1拍あたりのデータ点数が約20〜30点（60-90bpmの場合）しかなく、パラメータ数に対してデータが少なすぎる「過学習」に近い状態になることを指摘
- その結果、計算が不安定になりやすく、局所解への収束（最適な当てはめ結果が得られず間違った値で止まる）、パラメータ同定性の問題（異なるパラメータの組み合わせでも同じような波形になり値が決まらない）、ノイズへの過敏性（わずかなノイズで結果が大きく変わる）といった問題が生じることを説明
- 提案モデルは4パラメータのみで済むため、データ点数が少なくても安定して計算できることを強調

**English excerpt:**
> "Gamma, Beta, and skewed-Gaussian models have been reported for PPG representation; however, these models present significant challenges at 30 fps. The Gamma model requires 3–5 parameters per component, and multi-component decomposition (typically 2–4 components for dicrotic notch representation) requires 6–20 parameters total. Similarly, skewed-Gaussian models require at least 4 parameters (amplitude, mean, standard deviation, skewness) per component. At 30 fps, with only approximately 20–30 samples per cardiac cycle (assuming 60–90 bpm), the number of data points approaches or falls below the number of parameters, leading to underdetermined or ill-conditioned fitting problems. This results in local minima trapping during nonlinear optimization, parameter identifiability issues where different parameter combinations yield similar waveforms, and high sensitivity to noise where single-sample outliers cause large parameter shifts. In contrast, the proposed asymmetric sine wave model captures the steep rise and gradual decay characteristic of PPG using the systolic-to-diastolic time ratio ($\alpha$). With only four parameters (amplitude, phase, mean, $\alpha$), the ratio of samples to parameters remains favorable (approximately 5:1 to 7.5:1), ensuring stable convergence and noise robustness even in the 30 fps environment."

---

## 2. 手法（Methods）への追記

### 2.1 正弦波モデルの導関数不連続性について
- モデルの数式上、波形の頂点（ピーク）で傾きが急に変わる「角（かど）」ができる理論的な性質があることを説明
- しかし、30fps（0.033秒に1回）という粗いサンプリング間隔では、この微細な「角」はデータの隙間に埋もれてしまい、実用上は滑らかな波形として扱っても問題ないことを補足

**English excerpt:**
> "Note that at the boundary t' = T_sys, the first derivative of the phase function θ(t) exhibits a discontinuity: the derivative is π/T_sys in the rising phase and π/T_dia in the falling phase. This causes a theoretical slope discontinuity in s(t) at the peak. However, at 30 fps sampling (33 ms interval), this discontinuity falls well within the sampling resolution and does not manifest as a visible artifact in the discretized waveform. Since the sampling coarseness dominates over this mathematical discontinuity, the practical impact on fitting quality and feature extraction is negligible."

### 2.2 αの推定プロセスの詳細化
- モデルの形状を決める重要なパラメータ $\alpha$（収縮期と拡張期の比率）の決定方法を記述
- 平滑化された波形から収縮期ピークとそれに続く谷（dicrotic notchまたは拡張期の開始点）を検出し、その時間差から比率を直接計算するヒューリスティックな手法を採用していることを説明（モバイル端末でのリアルタイム性を重視）

**English excerpt:**
> "The systolic/diastolic ratio $\alpha = T_{sys}/T$ is estimated by detecting the systolic peak and the subsequent valley from the smoothed waveform. The time difference between the peak and the valley is defined as the diastolic time $T_{dia}$, and the systolic time is calculated as $T_{sys} = T - T_{dia}$. The ratio is then derived as $\alpha = T_{sys}/T$. This heuristic approach ensures real-time performance on mobile devices."

### 2.3 前処理パラメータの詳細化
- 再現性を担保するため、具体的な数値を明記
- **ピーク検出**: 誤検出を防ぐため、最低でも0.5秒（心拍数120bpm相当）の間隔を空ける設定
- **IBI外れ値除去**: 急激な心拍変動（前の拍から30%以上の変化）はエラーとみなして除外
- **振幅外れ値除去**: 固定の生理学的範囲（0.5〜50任意単位）から外れた拍を除外

**English excerpt:**
> "Peak Detection: Local maxima are identified using a minimum peak distance of 0.5 s (corresponding to 120 bpm maximum heart rate). Peaks are refined by local search within ±2 samples of the initial detection to correct for sampling-induced position errors.
>
> IBI Outlier Removal: Beats are rejected if the IBI ratio to the preceding beat falls outside the range [0.7, 1.3], corresponding to sudden heart rate changes exceeding 30%. Additionally, beats with IBI outside the physiological range of 0.5–1.5 s (40–120 bpm) are excluded.
>
> Amplitude Outlier Removal: Beats with amplitude outside a fixed physiological range (0.5–50 arbitrary units) are rejected to eliminate motion artifacts and contact pressure variations."

---

## 3. 実験設計（Experimental Design）への追記

### 3.1 実験環境の統制条件の詳細化
- **接触式測定（Contact Method）への修正**: スマートフォンを机に置き、被験者がフロントカメラに指を直接接触させる方式であることを明記
- 照度条件（400±50 lux）や、指を押し付ける圧力（白くならない程度）についても記述

**English excerpt:**
> "A Google Pixel 8 front camera (30 fps, 1080p resolution) acquired PPG via fingertip contact (index finger) under controlled indoor lighting of 400 ± 50 lux, measured by a calibrated lux meter at the fingertip position. To minimize illumination variation, measurements were conducted in a windowless room with fluorescent lighting. The smartphone was placed flat on a stable desk surface. Subjects were instructed to place their index finger directly on the front camera lens, applying light, consistent pressure without blanching the fingertip, and the experimenter visually confirmed adequate contact before each session."

### 3.2 被験者情報の拡充
- 年齢（20-23歳）と性別（男性のみ）を明記
- 3回のセッションを別日に実施したことを記述

### 3.3 サンプル数の限界についての明示
- 被験者数5名は小規模であり、結果を一般化するには限界があることを正直に記述
- 若年男性のみであるため、女性や高齢者、疾患を持つ人への適用は今後の課題であることを明記

**English excerpt:**
> "Five healthy males aged 20–23 (mean 21.4 ± 1.1 years) participated; severe arrhythmia was an exclusion criterion. Each subject underwent three measurement sessions on separate days. We acknowledge that this sample size is limited, and the generalizability of results is constrained. The current cohort consists entirely of young healthy males, which may not represent the broader population including females, older adults, or individuals with cardiovascular conditions. Future studies should expand the sample to include (1) female participants for sex-balanced validation, (2) older age groups (40s–60s) to assess performance under age-related vascular changes, and (3) individuals with hypertension or other cardiovascular conditions to validate clinical applicability."

### 3.4 血圧変動範囲の制限についての議論
- 深呼吸による血圧変動は安全だが範囲が狭い（SBPで10-20mmHg程度）ことを説明
- 臨床的に重要な高血圧・低血圧の範囲まではカバーできていないため、あくまで「正常血圧付近での検証結果」であることを明記

**English excerpt:**
> "Blood Pressure Range Limitation: The deep breathing protocol was selected to induce moderate, physiologically safe blood pressure variations. However, this approach results in a limited BP range: typical SBP variations of 10–20 mmHg and DBP variations of 5–15 mmHg around baseline. This narrow range may restrict the model's ability to capture relationships across the full clinical BP spectrum (e.g., 90–180 mmHg for SBP). Consequently, the validation reflects performance within a constrained, near-normotensive range, and extrapolation to hypertensive or hypotensive conditions requires further investigation with appropriately designed protocols (e.g., exercise stress, pharmacological intervention)."

---

## 4. 結果（Results）への追記

### 4.1 波形比較図の説明追加
- 実際に生成した波形比較図（Figure 1）について説明
- **ジッタ（Jitter）の低減**: 生波形（青）はピーク位置が微妙に揺らぐが、モデル（赤）は滑らかで安定していることを示す
- **下降相の滑らかさ**: 波形の戻りの部分（拡張期）のガタつき（高周波ノイズ）が取れていることを示す
- **ベースライン（DCオフセット）**: 波形の上下位置の変動が補正されていることを示す

**English excerpt:**
> "Figure 1 presents representative examples of raw Green-channel waveforms and corresponding sinWave fits, illustrating improvements in (1) peak position stability—the sinWave peak locations exhibit reduced jitter compared to raw signal peaks, (2) falling-phase smoothness—the diastolic decay is regularized, removing high-frequency noise without distorting the overall morphology, and (3) baseline consistency—DC offset variations are normalized through the mean parameter."

### 4.2 相関係数の限界についての明示
- SBPの相関係数が0.21と低いことは、「平均的な血圧は推定できているが、一拍ごとの細かい変動までは追いきれていない」ことを意味すると正直に記述
- 絶対的な追従性能にはまだ課題があることを認める記述を追加

**English excerpt:**
> "However, it should be noted that the correlation coefficient for SBP (0.21) remains relatively low, indicating that instantaneous blood pressure tracking capability is limited. This suggests that while the method reduces average estimation error, its ability to follow rapid beat-to-beat fluctuations in blood pressure is constrained."

---

## 5. 考察（Discussion）への追記

### 5.1 残差Eと血圧の関係についての生理学的解釈
- モデルとの「ズレ（残差E）」がなぜ血圧と関係するのかを説明
- **反射波（Reflected Wave）**: 血管が硬い（血圧が高い）と、血液の「反響（エコー）」が早く戻ってくるため、波形が歪みやすくなる
- **血管抵抗（Vascular Resistance）**: 血圧が高いと血液が流れにくくなり、波形の減衰の仕方が変わる
- これらの生理現象による波形の歪みを、残差Eが捉えていると解釈

**English excerpt:**
> "The residual E reflects waveform distortions that the idealized asymmetric sine model cannot capture. Physiologically, these distortions arise primarily from two mechanisms. First, reflected waves from peripheral vascular beds return to the measurement site and superimpose on the forward-traveling pulse wave. The timing and magnitude of these reflections depend on pulse wave velocity (PWV), which increases with arterial stiffness and blood pressure. In stiffer vessels, faster PWV causes earlier wave reflection, creating more pronounced distortion in the diastolic phase. Second, increased vascular resistance associated with elevated blood pressure alters the pressure-flow relationship, modifying the decay characteristics of the diastolic portion. The residual E thus encapsulates information about vascular impedance and wave reflection phenomena that correlate with blood pressure independently of the primary waveform parameters."

### 5.2 制限と今後の課題の拡充
- 精度（MAPE約16%）が、医療機器として認められる基準（AAMI基準）には達していないことを明記
- サンプル数や血圧範囲の制限についても再度触れ、今後の課題として挙げる

**English excerpt:**
> "MAPE remains around 16%, and the SBP correlation coefficient of 0.21 indicates limited instantaneous tracking capability, falling short of AAMI criteria for clinical device approval. The current validation is further constrained by the small sample size (N=5), narrow demographic range (young healthy males), and limited blood pressure variation range induced by the deep breathing protocol."

---

## 6. 結論（Conclusion）の修正

### 6.1 限定的な相関係数についての明示
- AAMI基準（医療機器基準）を満たしていないことを結論でも明確に述べる

**English excerpt:**
> "However, the correlation coefficients (SBP: 0.21, DBP: 0.28) indicate that instantaneous blood pressure tracking capability remains limited, and the method does not currently meet AAMI criteria for clinical device approval."

### 6.2 適用範囲の限定（「臨床用途」の回避）
- 「臨床用途（病院での診断など）」には使えないことを明言
- 代わりに「mHealth（モバイルヘルス）」や「日常的な自己モニタリング（傾向把握）」としての可能性に留める表現に修正

**English excerpt:**
> "This method shows potential for mHealth applications and daily self-monitoring purposes, where the goal is trend awareness rather than clinical-grade accuracy. It is not intended for clinical diagnostic or treatment decision-making at the current stage of development."

---

## 7. 波形比較図の作成と挿入

### 7.1 図生成スクリプトの作成
新規Pythonスクリプト `Analysis/Waveform_Analysis/generate_waveform_comparison.py` を作成:
- 22セッションのWave_Dataファイルから331ビートを自動収集
- 各ビートのノイズレベル（Green と SinWave の差のRMS）と振幅を計算
- 3つの代表的なビート例を自動選択:
  - (a) 高ノイズケース（ノイズ上位25%から選択）
  - (b) 典型的な品質ケース（ノイズ中央値付近から選択）
  - (c) 低振幅ケース（振幅下位25%から選択）
- scipy.signal.find_peaks を使用したビート検出アルゴリズム
- 出力: `figures/waveform_comparison_examples.png` および SVG 版

### 7.2 evaluate_waveforms.py への機能追加
`evaluate_waveforms.py` にも以下の関数を追加:
- `detect_beats()`: ピーク検出によるビート区間の特定
- `save_waveform_comparison_figure()`: 波形比較図の生成

### 7.3 AROB.tex への図の挿入
- Results セクション (4.1節) に波形比較図を挿入
- Figure ラベル: `fig:waveform_examples`
- キャプション: 3つのビート例（高ノイズ、典型品質、低振幅）における Raw Green channel と sinWave fit の比較
- 縦点線によるピーク位置表示とタイミング差のアノテーション

---

## 査読コメント対応チェックリスト

| # | 査読コメント | 対応状況 | 対応箇所 |
|---|-------------|---------|---------|
| 1 | 30fpsのピーク位置誤差(±16.5ms)・高調波制限(15Hz)の定量記述 | ✅ 完了 | 1章 Introduction |
| 2 | Gamma/skewed-Gaussianモデルの不安定性説明 | ✅ 完了 | 1章 Introduction |
| 3 | sin一階導関数の不連続性と影響説明 | ✅ 完了 | 2.1節 |
| 4 | αの推定プロセス詳細化 | ✅ 完了 | 2.1節 |
| 5 | 前処理パラメータの数値付き記述 | ✅ 完了 | 2.3節 |
| 6 | 照度・指圧・保持角度の統制条件詳細（接触式に修正済） | ✅ 完了 | 3章 Experimental Environment |
| 7 | サンプル数の限界と今後の拡大計画 | ✅ 完了 | 3章 Subjects |
| 8 | 深呼吸による血圧範囲制限の議論 | ✅ 完了 | 3章 Experimental Procedure |
| 9 | sinWave vs Green波形比較図の挿入 | ✅ 完了 | 4.1節 + Figure生成 |
| 10 | SBP相関0.21の限界明記 | ✅ 完了 | 4章・5章・結論 |
| 11 | 残差EとPWV/反射波の関連説明 | ✅ 完了 | 5章 Discussion |
| 12 | 「臨床用途」回避・mHealth用途限定 | ✅ 完了 | 結論 |

---

## 変更日
2024年12月23日
