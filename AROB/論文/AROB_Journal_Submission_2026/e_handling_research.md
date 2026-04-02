# E の扱い方に関する定量調査メモ

更新日: `2026-03-22`

## 1. このメモの目的

検討したい問いはこれです。

`sinBP(D)` の residual `E` は、単純な `RTBP features + E` よりも、別の入れ方をした方が良い結果になるか。

条件:

- `RTBP features` 自体は変えない
- 変えるのは `E` の扱いだけ
- 例:
  - `RTBP features + E`
  - `RTBP features + E*A`
  - `RTBP features + E/|A|`
  - `RTBP features * (1 + nE)`
  - `RTBP features / (1 + nE)`

ここでは、

1. 既存研究ではどういう発想が多いか  
2. 今の手元データでどの形が実際に良いか  
3. その結果を受けて、アプリ側を変える価値があるか

をまとめる。

---

## 2. 先に結論

短く言うと、**今のデータでは `RTBP features + E` を別の形に変えても、論文の主軸に使えるほどは強くならなかった**。

もう少し具体的に言うと、

- `timeseries` の absolute BP では、`RTBP*(1+nE)` 系が `RTBP+E` より少し良い
- でも `groupkfold` の absolute BP では改善が弱い
- さらに、今の論文で一番大事な `trend-oriented` 軸では、`RTBP+変形E` は **現在の `sinBP(D)` に勝てない**
- ひどいものだと、`plain RTBP` にも負ける

したがって、**今の段階でアプリの主ロジックを `RTBP+変形E` に置き換えるのはおすすめしない**。

もしアプリに足して新規データを取るなら、

- `現行ロジック` は残す
- `実験ロジック` を並列で追加してログを取る

のが安全。

---

## 3. 既存研究から見た「E の扱い方」のヒント

### 3.1 既存研究は「残差をそのまま足す」より、「正規化した比」や「反射波指標」をよく使う

PPG/BP の特徴量研究では、単純な生値よりも、

- 面積比
- 時間比
- 正規化した歪み
- 波形の反射成分を表す比

のような形がよく使われている。

たとえば review 論文では、以下のような特徴量が整理されている。

- `A2/A1` のような面積比
- `Tsys/Tdia` のような時間比
- `NHA` のような harmonic distortion を正規化した量
- `PI` のような個人差を考慮した正規化時間指標

出典:

- `Features from the photoplethysmogram and the electrocardiogram for estimating changes in blood pressure`
  - https://pmc.ncbi.nlm.nih.gov/articles/PMC9849280/

この論文の重要な点は、

- BP 関連情報は「面積」「時間」「反射波」「歪み」に出る
- そのとき **ratio / normalized feature** が多い

ということ。

つまり、文献的には

- `E` を単に 1 個足す
- あるいは `全特徴をEで一括乗算する`

よりも、

- `E` を何かで正規化する
- `E` を特定の形状特徴と組み合わせる

方が自然、という示唆がある。

ただし、ここで注意が必要で、**文献に `RTBP features * (1+nE)` が定番だという根拠は見つからなかった**。  
これは既存研究そのものというより、こちらの feature engineering の仮説に近い。

### 3.2 waveform reconstruction 系では「誤差そのもの」を global index として使う発想はある

PPG から ABP waveform を再構成する研究では、再構成誤差を全体性能の指標として扱う発想がある。

出典:

- `Robust modelling of arterial blood pressure reconstruction from photoplethysmography`
  - https://www.nature.com/articles/s41598-024-82026-1

この論文では `TEI (total error index)` が提案されていて、**波形のずれそのものを1つの重要な情報として見る** という意味では、`E` を使いたいという発想と整合する。

ただし、この文献も

- `E` を RTBP 全特徴に一括で掛ける
- `E` で割る

という形を直接支持しているわけではない。

### 3.3 rPPG 側の研究でも、full waveform の対称性や形状は使われている

rPPG から SBP を推定する研究では、

- 平均 pulse waveform の形
- symmetry
- pulse amplitude

のような full-wave feature が使われている。

出典:

- `Improving Systolic Blood Pressure Prediction From Remote Photoplethysmography Using a Stacked Ensemble Regressor`
  - https://openaccess.thecvf.com/content/CVPR2023W/CVPM/papers/van_Putten_Improving_Systolic_Blood_Pressure_Prediction_From_Remote_Photoplethysmography_Using_a_CVPRW_2023_paper.pdf

ここから言えるのは、

- wave-shape の情報を使うこと自体は自然
- ただし使い方は、**別の shape feature として入れる** 方向が主流

ということ。

### 3.4 trend estimation は absolute estimation と別タスクとして考えるべき

最近の研究では、BP trend estimation 自体を独立した課題として扱っている。

出典:

- `Robust Estimation of Unsteady Beat-to-Beat Systolic Blood Pressure Trends Using Photoplethysmography Contextual Cycles`
  - https://pmc.ncbi.nlm.nih.gov/articles/PMC12196987/

この論文の含意は明確で、

- absolute BP prediction と
- trend tracking

は同じではない。

したがって、`E` の扱いを比較するときも、

- `timeseries absolute`
- `grouped absolute`
- `grouped trend`

を分けて見るべき。

### 3.5 strict split を使うと数字が大きく変わることは既存研究でも指摘されている

PPG だけで absolute BP を出す研究でも、subject separation の置き方で成績が大きく変わることが報告されている。

出典:

- `Beat-to-Beat Blood Pressure Estimation by Photoplethysmography and Its Interpretation`
  - https://pmc.ncbi.nlm.nih.gov/articles/PMC9506534/

この論文でも、データ分割の仕方が結果に大きく影響すると明言されている。  
なので、今回も `timeseries` だけを見て「良い」と判断するのは危ない。

---

## 4. 今回やった定量実験

### 4.1 データ

- 入力: `Analysis/BP_Analysis/prepared_training_data.csv`
- 総行数: `1113`
- synchronized BP がある行: `469`

### 4.2 前処理

既存の `explore_validation_axes.py` と同じ前処理を使った。

- 参照 BP の範囲チェック
- target ごとの `±3σ` outlier removal
- subject/group ごとの feature outlier removal

重要:

`E` の変形特徴量は **前処理後に** 作った。  
つまり、`E` が outlier 扱いで落ちた行をうっかり残すことはしていない。

### 4.3 比較した方法

基準方法:

- `RTBP`
- `sinBP(M)`
- `sinBP_D_current`
  - これは現在の `M2_A, M2_HR, M2_V2P_relTTP, M2_P2V_relTTP, M2_E`

`RTBP features` を固定して `E` の扱いだけ変えた方法:

- `RTBP + E`
- `RTBP + E/|A|`
- `RTBP + E*A`
- `RTBP + E*HR`
- `RTBP + E*rise`
- `RTBP + E*fall`
- `RTBP + all interactions`
- `RTBP*(1+nE)` with `n = 0.1, 0.25, 0.5, 1, 2`
- `RTBP/(1+nE)` with `n = 0.1, 0.25, 0.5, 1, 2`

### 4.4 評価軸

以下の 6 条件を見た。

1. `timeseries absolute SBP`
2. `timeseries absolute DBP`
3. `GroupKFold absolute SBP`
4. `GroupKFold absolute DBP`
5. `GroupKFold 20 s centered SBP trend`
6. `GroupKFold 20 s centered DBP trend`

推定器はすべて `RidgeCV + StandardScaler` で揃えた。

再現用スクリプト:

- `Analysis/BP_Analysis/explore_e_handling.py`

出力 CSV:

- `Analysis/BP_Analysis/e_handling_results/`

---

## 5. 結果

## 5.1 重要な結論だけ先に

**`RTBP features` を固定したまま `E` の入れ方だけ工夫しても、今の main candidate を超えるものは出なかった。**

特に、

- reviewer に見せやすい `GroupKFold absolute`
- 今の論文の主軸である `GroupKFold trend`

では、`RTBP+変形E` はかなり弱い。

---

## 5.2 timeseries absolute では少し改善する

### SBP

- 現在の best: `sinBP_D_current`
  - `MAE 18.89`, `RMSE 24.03`, `Corr 0.220`
- `RTBP + E`:
  - `MAE 19.73`, `RMSE 26.53`, `Corr 0.102`
- `RTBP*(1+E)`:
  - `MAE 19.22`, `RMSE 25.75`, `Corr 0.137`

つまり、

- `RTBP + E` よりは `RTBP*(1+E)` の方が良い
- でも `sinBP_D_current` には負ける

### DBP

- 現在の best: `sinBP_D_current`
  - `MAE 14.76`, `RMSE 19.12`, `Corr 0.285`
- `RTBP + E`:
  - `MAE 15.22`, `RMSE 21.63`, `Corr 0.224`
- `RTBP*(1+0.5E)`:
  - `MAE 14.98`, `RMSE 21.01`, `Corr 0.247`
- `RTBP/(1+2E)`:
  - `MAE 15.17`, `RMSE 20.18`, `Corr 0.284`

DBP でも、

- additive より multiplicative / divisive の方が少し良い
- ただし current best より強いとは言えない

---

## 5.3 GroupKFold absolute では改善しない

### SBP

- best: `sinBP_M`
  - `MAE 21.89`, `RMSE 26.25`, `Corr 0.007`
- `sinBP_D_current`
  - `MAE 23.41`, `RMSE 27.55`, `Corr -0.092`
- best transformed E method: `RTBP/(1+2E)`
  - `MAE 23.27`, `RMSE 27.68`, `Corr -0.088`

ここでは、`RTBP+変形E` はほぼ全滅に近い。  
少し MAE が下がっても correlation は弱く、主結果に使える強さではない。

### DBP

- best: `sinBP_M`
  - `MAE 18.35`, `RMSE 21.43`, `Corr 0.063`
- `sinBP_D_current`
  - `MAE 18.74`, `RMSE 21.53`, `Corr 0.074`
- best transformed E method: `RTBP*(1+0.25E)`
  - `MAE 18.96`, `RMSE 23.06`, `Corr -0.155`

DBP でも、変形 `E` は grouped validation では有利ではない。

---

## 5.4 GroupKFold trend ではさらに弱い

### centered SBP trend, 20 s window

- best: `sinBP_D_current`
  - `MAE 3.22`, `RMSE 3.91`, `Corr 0.200`
- `RTBP`
  - `MAE 3.35`, `RMSE 4.01`, `Corr 0.126`
- best transformed E method: `RTBP/(1+0.25E)`
  - `MAE 3.67`, `RMSE 4.56`, `Corr 0.159`
- `RTBP + E`
  - `MAE 3.84`, `RMSE 4.86`, `Corr -0.024`

これはかなり大事で、**今の論文の本命である trend 軸では、`RTBP+変形E` は current `sinBP(D)` に勝てていない**。

### centered DBP trend, 20 s window

- best MAE: `sinBP_M`
  - `MAE 2.75`, `RMSE 3.43`
- best positive correlation among references: `RTBP`
  - `Corr 0.180`
- `sinBP_D_current`
  - `MAE 2.87`, `RMSE 3.56`, `Corr 0.071`
- transformed E methods:
  - だいたい `MAE 3.28` 前後

DBP trend でも、`E` 変形系は優勢ではない。

---

## 6. 解釈

## 6.1 `RTBP*(1+nE)` が timeseries だけ少し良い理由

これはたぶん、

- `E` が waveform mismatch の大きさを持っていて
- その mismatch が amplitude や timing の信頼度に関係している

ため、`RTBP` の各特徴量を `E` で少し重み付けすると、within-dataset では見かけ上うまくいくからだと考えられる。

でもこれは、

- unseen group に対して通る関係

とは限らない。

今回の結果では、**この改善は group-aware validation で消えるか、むしろ悪化した**。

なので、

- `E` を掛けることで physiological relation をうまく捉えた

というより、

- 特定 group のスケール差を拾ってしまった

可能性の方が高い。

## 6.2 `E/|A|` が悪かった理由

文献的には ratio / normalized feature は自然だが、今回の `E/|A|` はかなり悪かった。

考えられる理由:

- もともと `M1_A` が正規化済みで、分母として安定でない
- 小さい振幅の beat でノイズが増幅される
- `E` と `A` を単純に割るだけでは、意味のある shape index になっていない

つまり、

- `正規化する発想` 自体は悪くない
- でも `E/|A|` という単純な形は今のデータではダメ

ということ。

## 6.3 文献と整合するのは「任意の掛け算」より「意味のある ratio / shape index」

今回の文献調査で一番大事なのはここ。

既存研究でよく出るのは、

- 反射波を表す ratio
- 面積比
- 時間比
- harmonic distortion

であって、

- `全部の RTBP features を一括で (1+nE) 倍する`

という形ではない。

なので、`RTBP*(1+nE)` は exploratory にはありだが、**論文で強く押すには理屈が弱い**。

---

## 7. 実務的なおすすめ

## 7.1 いま主ロジックとしては採用しない方がよい

現時点では、

- `RTBP + 変形E`

をアプリの主ロジックに置き換えるのはおすすめしない。

理由:

- main manuscript 軸の `trend_gkf20_sbp` で負けている
- grouped absolute でも改善しない
- 文献的にも理屈が弱い

## 7.2 もしアプリに足して新規データを取るなら「並列ログ」がよい

それでも試す価値があるとすれば、**主ロジックの置き換えではなく parallel experiment** として入れるのがよい。

おすすめ候補はこの 2 つ。

### 候補 A: `RTBP_scaled_mul_1`

式:

\[
x'_k = x_k (1 + E)
\]

ここで `x_k` は

- `A`
- `HR`
- `V2P_relTTP`
- `P2V_relTTP`

の 4 つ。

理由:

- timeseries absolute SBP では変形系の中で一番良かった
- DBP でもそこそこ悪くない

### 候補 B: `RTBP_scaled_div_2`

式:

\[
x'_k = \frac{x_k}{1 + 2E}
\]

理由:

- timeseries DBP の correlation は比較的高かった
- multiplicative 系とは違う方向の modulation を見られる

### ただし注意

この 2 つは、

- exploratory branch としてはあり
- でも current main logic に置き換える根拠はまだない

## 7.3 新規データを取るなら、同時に残すべき出力

もしアプリに追加するなら、以下を全部同時に保存するのがよい。

- `RTBP`
- `sinBP_M`
- `current sinBP_D`
- `RTBP_scaled_mul_1`
- `RTBP_scaled_div_2`
- raw `E`

これで、

- 同じ recording
- 同じ subject
- 同じ session

上で直接比較できる。

---

## 8. 論文としての判断

今のデータに基づく限り、論文では次のように判断するのが妥当。

- `RTBP features + E` を別の algebraic form に変える案は、現時点では主張の中心にしない
- もし触れるなら「exploratory feature-engineering attempt」として補助解析に留める
- main story は引き続き
  - `30 fps`
  - `whole-wave fitting`
  - `trend-oriented estimation`

に置く方が安全

---

## 9. 最終判断

### 9.1 今の答え

`E` の扱いを変えることで、`RTBP + E` より少し良い数値が出るケースはある。  
特に `timeseries absolute` では `RTBP*(1+nE)` 系が少し良い。

しかし、

- current `sinBP_D_current` を超えない
- grouped validation では効かない
- trend 軸ではむしろ悪い

ので、**今すぐ「これが本命」と言える案はない**。

### 9.2 実務上のおすすめ

- 主ロジックはまだ変えない
- 変えるなら parallel logging として追加
- 追加するなら `RTBP_scaled_mul_1` と `RTBP_scaled_div_2`

### 9.3 研究上のおすすめ

次に本当にやる価値があるのは、`E` をむやみに掛けたり割ったりすることよりも、

- 反射波を意識した ratio
- 面積比
- 時間比
- fit residual の正規化指標

のような、**文献に近い shape index として設計し直すこと**。

---

## 10. 関連ファイル

再現用:

- `Analysis/BP_Analysis/explore_e_handling.py`

結果:

- `Analysis/BP_Analysis/e_handling_results/abs_ts_sbp.csv`
- `Analysis/BP_Analysis/e_handling_results/abs_ts_dbp.csv`
- `Analysis/BP_Analysis/e_handling_results/abs_gkf_sbp.csv`
- `Analysis/BP_Analysis/e_handling_results/abs_gkf_dbp.csv`
- `Analysis/BP_Analysis/e_handling_results/trend_gkf20_sbp.csv`
- `Analysis/BP_Analysis/e_handling_results/trend_gkf20_dbp.csv`
- `Analysis/BP_Analysis/e_handling_results/summary_best_per_scenario.csv`

---

## 11. RTBP の線形回帰を基準にした場合の実務的な答え

ここでは考え方をさらに絞って、

- 基準モデルは `RTBP` の線形回帰
- その上で residual `E` をどう使えばよいか

だけを考える。

### 11.1 一番素直な答え

**`E` を 1 個の追加説明変数として足すより、`E` を RTBP 特徴量の重みづけに使う方がまだマシ**。

今回の結果では、RTBP ベースで一番マシだった候補は次。

#### absolute BP を timeseries でよく見せたい場合

- SBP:
  - `x'_k = x_k (1 + E)` が最良
  - `MAE 19.22`, `RMSE 25.75`, `Corr 0.137`
- DBP:
  - `x'_k = x_k (1 + 0.5E)` が最良 MAE
  - `MAE 14.98`, `RMSE 21.01`, `Corr 0.247`
  - `x'_k = x_k / (1 + 2E)` は correlation が少し高め
  - `MAE 15.17`, `RMSE 20.18`, `Corr 0.284`

つまり、RTBP 線形回帰を前提にするなら、

- `RTBP + E`

より

- `RTBP*(1+nE)`
または
- `RTBP/(1+nE)`

の方が有望。

### 11.2 ただし reviewer に効く条件ではあまり強くない

この「有望」はあくまで `timeseries` の中での話。  
`GroupKFold` や `trend` で見ると、

- `RTBP + E`
- `RTBP*(1+nE)`
- `RTBP/(1+nE)`

のどれも current `sinBP(D)` を超えていない。

だから、

- `論文の主結果`
- `アプリの本命ロジック`

としてこれを採用するのはまだ危ない。

### 11.3 もう 1 つの使い方: `E` を「信頼度」とみなす

追加で試したのは、

- RTBP の説明変数はそのまま
- ただし学習時に `E` が大きい beat の重みを下げる

という `weighted linear regression`。

試した重みの例:

\[
w = \frac{1}{1 + \lambda \, E/\mathrm{median}(E_{\mathrm{train}})}
\]

または

\[
w = \exp\!\left(-\lambda \, E/\mathrm{median}(E_{\mathrm{train}})\right)
\]

結果:

- absolute BP では改善しなかった
- ただし trend ではほんの少しだけ改善した

具体例:

- `trend_gkf20_sbp`
  - unweighted: `MAE 3.843`, `RMSE 4.774`, `Corr 0.054`
  - best weighted: `MAE 3.841`, `RMSE 4.756`, `Corr 0.107`
- `trend_gkf20_dbp`
  - unweighted: `MAE 3.424`, `RMSE 4.308`, `Corr 0.108`
  - best weighted: `MAE 3.347`, `RMSE 4.194`, `Corr 0.089`

解釈:

- `E` を physiological feature として使うより
- `E` を beat quality / confidence の proxy として使う方が理屈は自然

ただし、この改善幅はまだ小さい。

### 11.4 いま一番おすすめの考え方

RTBP 線形回帰を基準にするなら、優先順位はこう。

1. **第一候補**
   - `E` を confidence とみなして使う
   - 例: weighted regression, high-E beat の smoothing, high-E beat の low-confidence flag

2. **第二候補**
   - `RTBP*(1+nE)` を experimental branch として追加
   - SBP なら `n=1`
   - DBP なら `n=0.5` か `divide by (1+2E)` も比較

3. **避けたい候補**
   - `RTBP + E` のみ
   - `E/|A|` のような単純正規化

### 11.5 実装としてどうするのがよいか

もしアプリ側で増やすなら、いきなり置き換えずに次の 3 本立てがよい。

- 現行 `RTBP`
- 現行 `sinBP(D)`
- 実験用 `RTBP_scaled_mul_1`

余裕があれば追加:

- 実験用 `RTBP_scaled_div_2`
- `E` に基づく confidence score

この形なら、

- 現行ロジックは壊さない
- 新しい仮説も同時にログできる
- 後で subject / session ごとに比較できる

### 11.6 ここまでを一言で言うと

`RTBP 線形回帰 + E` を前提にするなら、

- **スコアを少しでも上げやすいのは `RTBP*(1+nE)` 系**
- **理屈として一番きれいなのは `E` を confidence として使う方法**

です。

ただし現時点では、どちらも `current sinBP(D)` を main result として置き換えるほどではない。
