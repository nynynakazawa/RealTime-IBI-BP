# 改稿後査読メモ 日本語版

対象:
- `AROB/論文/AROB_Journal_Submission_2026/main.tex`
- `AROB/論文/AROB_Journal_Submission_2026/reviewer_report.md`

このメモは、現在の改稿版を前提に、

- どこが前進したか
- どこがまだ弱いか
- どこまでが `Codexがすぐ実行できる作業` で、どこから先が著者判断か

を切り分けるための再評価です。

---

## 1. 現時点の総合判定

`Major Revision`

現稿は、以前のような `Reject` 寄りの「内部ドラフト状態」ではなくなりました。`sinBP(D)` の説明は `RTBP features + residual E` に整理され、`trend-oriented` という主軸もデータに合わせたものになっています。一方で、`group-aware validation`、`participant/session structure`、`ablation`、`related work` はまだ弱く、ここを埋めないと journal paper としての説得力は足りません。

---

## 2. 今回の改稿で改善した点

### 2.1 `sinBP(D)` の定義が本文・PPTX・Android 側で揃った

今回の最重要改善です。  
以前は `sinBP(D)` の説明が解析コードや Android 実装とズレていましたが、現在は

- `RTBP amplitude`
- `heart rate`
- `relative rise/fall time`
- `residual E`

という定義で統一されています。  
`E\sqrt{A}` や `stiffness` を中心にした説明は落ちたので、method identity はかなり明確になりました。

### 2.2 trend-oriented task が方法として明文化された

今回、本文に

\[
\tilde{y}_i = y_i - \bar{y}_{g(i)}
\]

という group-centered target の定義が入りました。  
これにより、`trend-oriented estimation` が単なる言い換えではなく、評価対象そのものを変えた解析であることが明示されました。

### 2.3 時間窓平均の定義が入った

`20 s non-overlapping windows` をどう扱うかが Methods に書かれたのは良い修正です。  
以前は reviewer に「trend とは何か」「window averaging はどう定義したのか」と聞かれたときに弱かったですが、今は最低限の答えがあります。

### 2.4 表の役割が整理された

今の原稿は、

- 表1: waveform fitting
- 表2: absolute BP
- 表3: group-centered trend

という構成になっており、absolute と trend を同じ意味で混ぜなくなりました。  
これは reviewer にとってかなり読みやすく、主張の軸も追いやすいです。

### 2.5 absolute BP の言い過ぎが減った

現稿は、`time-series` の absolute result を残しつつも、それを強い generalization evidence とは言っていません。  
この姿勢は正しいです。とくに SBP については grouped validation で弱いことを認めているので、以前よりはるかに健全です。

### 2.6 trend-oriented interpretation が数字に支えられる形になった

少なくとも SBP については、`GroupKFold + 20 s window` の centered target で `sinBP(D)` が最良でした。  
このため、「30 fps 条件では whole-wave fitting は absolute level estimation より trend tracking に向く」というストーリーは、今のデータと整合しています。

---

## 3. まだ弱い点

### 3.1 absolute BP の主結果は依然として `time-series` 依存

本文はその限界を認めていますが、表2の absolute BP はまだ `time-series` split です。  
reviewer は当然、

- 同じ group が train/test にまたがっていないか
- その結果が汎化性能ではなく within-group regularity を見ているだけではないか

を疑います。  
ここは文章ではなく、評価設計の問題です。

### 3.2 participant / session 構造が不明確

今の本文は「確認できたことだけ書く」という意味では正しいです。  
ただし reviewer は最終的に、

- 実被験者数
- 各被験者の session 数
- 除外後に何 session 残ったか
- `recording-group ID` と participant の対応

を知りたがります。  
これがないと effective sample size を評価できません。

### 3.3 `sinBP(D)` の improvement がどこから来るかはまだ分離できていない

今は `RTBP + E` に揃いましたが、次の疑問は残ります。

- 効いているのは本当に `E` なのか
- それとも単に RTBP だけでも近い結果なのか
- `sinBP(M)` との差は何が本質なのか

したがって `ablation` は依然として必要です。

### 3.4 trend result は SBP では筋が良いが DBP ではまだ弱い

表3では SBP は `sinBP(D)` が最良ですが、DBP は `RTBP` や `sinBP(M)` と差が小さく、相関も強くありません。  
つまり今の story は

- `SBP trend` には比較的説得力がある
- `DBP trend` は補助的

という読み方が自然です。  
BP 全体に一様な improvement があるとはまだ言えません。

### 3.5 Related Work はまだ journal 水準に足りない

独立節は入りましたが、中身はまだ薄いです。  
今のままだと reviewer には

- low-frame-rate rPPG
- camera-based BP estimation
- parametric pulse modeling
- learning-based cuffless BP

の中でこの研究がどこに立つのかが十分見えません。

---

## 4. いま `Codexがすぐ実行できる作業` として完了したもの

- `main.tex` の `sinBP(D)` 定義を `RTBP + E` に統一
- `Trend-Oriented Target Definition` を追加
- 非重複時間窓平均の定義を追加
- absolute BP と trend-oriented result の表を分離
- absolute BP の数値を `SinBP_D_no_stiff` に合わせて更新
- Bland--Altman 図の位置づけを `absolute-estimation diagnostic` に修正
- `reviewer_report.md` / `reviewer_report_ja.md` を現稿に合わせて更新

---

## 5. まだ著者判断が必要なもの

### 5.1 主結果を本当に trend-oriented に切るか

今のデータではこの方が圧倒的に通しやすいです。  
ただし最終的に、

- absolute BP estimation paper として押すのか
- trend-oriented BP estimation paper として押すのか

は著者判断です。

### 5.2 表2を補助表に落とすか

査読を考えると、absolute BP の `time-series` 結果を main claim の中心に置くのは危ないです。  
より安全なのは、

- 表3を主表
- 表2を補助表

という扱いです。

### 5.3 `GroupKFold` を主結果にするか

今の trend table は already `GroupKFold` ですが、論文全体としてそれを primary validation と言い切るかどうかは著者判断です。

---

## 6. 著者が自分で詰めないといけないこと

### 6.1 participant / session 対応表を確定する

必要:
- 被験者 ID 一覧
- 各 session 数
- `recording-group ID` との対応
- 除外 session / beat の理由

### 6.2 subject-independent absolute evaluation を正式に出す

最低限:
- `GroupKFold`
または
- `Leave-One-Subject-Out`

のどちらかを absolute BP の主評価として提示する必要があります。

### 6.3 `ablation study` を追加する

最低限欲しいのは次です。

- `RTBP`
- `sinBP(M)`
- `RTBP + E`
- 可能なら `RTBP + fitted residual family`

### 6.4 related work を増やす

特に必要:
- smartphone / camera-based BP estimation
- low-frame-rate rPPG morphology
- parametric waveform modeling
- trend estimation / personalized estimation

---

## 7. 現在の稿に対する実務的な判断

現稿は「文章のまずさ」で落ちる段階はかなり脱しています。  
ただし、`journal-ready science` という意味ではまだ足りません。  
安全な出し方は、

- 主張の中心を `trend-oriented estimation under 30 fps` に置く
- absolute BP は補助結果に落とす
- participant/session structure と grouped validation を次の改稿で必ず補強する

です。

---

## 8. 次に進める順番

1. participant / session 対応表を著者側で確定  
2. absolute BP の grouped evaluation を正式表にするか判断  
3. `ablation` を追加  
4. related work を補強  
5. その後に本文最終調整
