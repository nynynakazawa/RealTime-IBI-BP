# 改稿TODO

このメモは、`2026-03-22` 時点の原稿・解析・Android 実装を前提にした最新版です。修士学生にも分かるように、

- 何がもう終わったか
- 何がまだ残っているか
- 何は Codex がすぐできるか
- 何は著者が決めないと進められないか

をはっきり分けています。

---

## まず結論

今のデータで一番通しやすい論文の軸は、

`30 fps の visible-light rPPG では、absolute BP を強く主張するより、within-recording の BP trend を追う方が説得力がある`

です。

すごく簡単に言うと、

- `血圧の絶対値を初見の group でも正確に当てられた` とは言いにくい
- でも `同じ recording の中で上がった・下がったを追う` なら、whole-wave fitting の良さを説明しやすい

という状況です。

今、論文の方向として考えているのは次です。

- 論文の看板:
  - `trend-oriented BP estimation under 30 fps visible-light rPPG`
- absolute BP:
  - 主結果ではなく補助結果
- task の置き方:
  - `SBP` も `DBP` も trend-oriented task として扱う
- 本文での役割:
  - `SBP` を主結果
  - `DBP` を supporting result
- 関連研究の軸:
  - `low-frame-rate waveform modeling`
  - `BP trend estimation`

---

## 1. もう終わったこと

以下は、すでに反映済みです。

### 1.1 原稿の主張を現実に合わせた

- `main.tex` を trend-oriented 寄りに修正した
- `absolute BP` と `trend-oriented BP` を分けて書いた
- `group-centered target` の式を Methods に入れた
- `20 s` の非重複窓平均の定義を入れた

### 1.2 表の構成を整理した

- 表1: waveform fitting
- 表2: absolute BP
- 表3: grouped trend-oriented result

という形に整理した。

### 1.3 `sinBP(D)` の説明を揃えた

今の定義は、

- `RTBP features`
- `+ residual E`

です。

つまり、

- `E√A`
- `stiffness`

を主役にした説明はやめています。

### 1.4 Android 側のロジックも揃えた

Android 側でも、

- `E*sqrt(A)` を削除
- `M2_Stiffness` を削除
- `sinBP(D) = RTBP features + E`

に直しています。

### 1.5 解析コードも揃えた

`train_bp_models.py` の `sinBP(D)` デフォルト特徴量も、

- `M2_A`
- `M2_HR`
- `M2_V2P_relTTP`
- `M2_P2V_relTTP`
- `M2_E`

に変更済みです。

### 1.6 reviewer 向けメモも更新した

- `reviewer_report.md`
- `reviewer_report_ja.md`

は今の原稿に合わせて更新済みです。

### 1.7 Related Work と図表を補強した

- Related Work に recent literature を追加した
- trend-oriented の window scan 図を追加した
- caption を少し journal 向けに強くした

### 1.8 補助資料を切り出した

- grouped absolute 結果
- grouped trend の window 比較
- subject ごとのばらつきメモ

を `supplementary_results.md` にまとめた

### 1.9 README を現在版にした

- 今の主張の境界
- 補助資料の場所
- 再確認が必要な項目

を README に反映した

---

## 2. Codex がすぐできる作業

ここは、著者判断がなくても進めやすい作業です。
`2026-03-22` 時点で、下の 2.1〜2.4 は実行済みです。

### 2.1 Related Work を厚くする

実行済みです。

- camera-based / rPPG-based BP estimation
- waveform modeling
- trend-oriented estimation

の流れを追加して、`この研究がどこに立つか` を前より分かりやすくしました。

### 2.2 Figure と caption をもう少し強くする

一部実行済みです。

- trend-oriented の結果を見せる図を追加した
- caption を「何を示したい図か」が前より分かる形に直した

ただし、

- failure case を 1〜2 例、論文の main figure として入れる

ところまではまだやっていません。

### 2.3 `main.tex` の英語をさらに詰める

一部実行済みです。

- Abstract を少し圧縮した
- Discussion の `E` の説明を少し論理的にした
- Conclusion を trend-oriented 主軸に合わせた

ただし、最終英語 polishing はまだ残っています。

### 2.4 解析の補助表を作る

実行済みです。すぐ出せる補助資料として、

- `timeseries` 結果の補助表
- `groupkfold` 結果の補助表
- `window size` 比較表

を `supplementary_results.md` にまとめました。

### 2.5 README を仕上げる

README は更新済みです。今の版では、

- どの結果を main claim に使うか
- どのファイルが最新か
- どの解析が exploratory か

を前より分かりやすく書いています。

---

## 3. 著者判断が必要な作業

ここは、Codex だけでは勝手に決めない方がよい部分です。

補足すると、今は次の方向で検討しています。

- 論文の看板は `trend-oriented BP estimation under 30 fps visible-light rPPG`
- `SBP` と `DBP` は両方とも trend-oriented task として扱う
- ただし本文では `SBP` を主結果、`DBP` を supporting result にする
- `absolute BP` は補助結果として残す
- 関連研究は `low-frame-rate waveform modeling + BP trend estimation` に寄せる

以下は、その前提でまだ著者判断が必要な部分です。

### 3.1 論文の主軸を最終的にどこに置くか

候補は大きく 2 つです。

- `absolute BP estimation`
- `trend-oriented BP estimation`

今のデータでは後者の方が安全です。
今は後者を採る方向で考えていますが、最終決定は著者判断です。

### 3.2 absolute BP の表を main に残すか

今の absolute BP の数字は、

- `timeseries` ではそこそこ見える
- でも grouped validation では弱い

という状態です。

だから、

- 表2を main result として前に出すか
- 補助結果に落とすか

は判断が必要です。

今のおすすめは、

- `absolute BP は残す`
- ただし `main result` ではなく `supporting / supplementary` 寄りに扱う

です。

### 3.3 SBP と DBP のどちらを前に出すか

今の傾向は、

- `SBP trend` は比較的説明しやすい
- `DBP trend` も task としては自然
- ただし `DBP` は `sin(D)` の勝ち方が `SBP` ほどはきれいではない

です。

つまり、

- `SBP` を主結果にする
- `DBP` を supporting result にする

のどちらを前面に出すかは、著者が決める必要があります。

今のおすすめは、

- `SBP` を主役
- `DBP` を supporting result

です。

### 3.4 関連研究をどの方向で増やすか

この研究を、

- smartphone BP estimation として見せるのか
- low-frame-rate waveform modeling として見せるのか
- BP trend estimation として見せるのか

で、引用の集め方が変わります。

今のおすすめは、

- `smartphone absolute BP estimation` に寄せすぎず
- `low-frame-rate waveform modeling`
- `BP trend estimation`

の 2 本を中心に組むことです。

---

## 4. 著者が実データや記録を確認しないと進められない作業

ここはかなり重要です。
文章を直すだけでは済みません。

### 4.1 participant / session の対応表を確定する

今の export から分かるのは、

- total rows: `1113`
- synchronized BP rows: `469`
- recording-group IDs: `25`
- usable groups with synchronized BP: `11`

までです。

でも reviewer が本当に知りたいのは、

- 被験者は何人か
- 各被験者に何 session あるか
- どの group が誰に対応するか
- 除外後に何が残ったか

です。

必要なもの:

- 被験者 ID 一覧
- session 対応表
- recording-group ID 対応表
- 除外理由メモ

### 4.2 ethics / 実験条件の正式確認

原稿にちゃんと書くには、次を実験記録から確定する必要があります。

- 年齢や属性
- 実験条件
- session 数
- 照明条件
- ethics approval の正式な書き方

### 4.3 対応著者情報の最終確認

投稿前に必要です。

- 所属
- 電話
- Fax の扱い
- 対応著者メール

---

## 5. 追加解析として本当は欲しいもの

これは「やった方がよい」だけでなく、journal としてはかなり重要です。

### 5.1 grouped validation を absolute BP の主評価として出す

今の弱点はここです。少なくとも reviewer は、

- `GroupKFold`
  または
- `Leave-One-Subject-Out`

を見たがります。

### 5.2 ablation study を出す

今のままだと reviewer は、

`本当に E が効いているのか？`

と聞きます。

最低限ほしい比較:

- `RTBP`
- `sinBP(M)`
- `RTBP + E`

できれば追加で:

- `fitted-wave descriptors only`
- `fitted-wave descriptors + E`

### 5.3 subject-wise / group-wise の結果を見せる

平均だけだと説得力が弱いです。少なくとも、

- どの group で効いたか
- どの group で失敗したか

は見せたいです。

---

## 6. もし追加データを取るなら必要な条件

今のデータでも論文の形にはできます。
ただし、より強い journal paper にしたいなら追加計測は有効です。

最低限ほしい条件:

- 被験者数を増やす
- 男性だけに偏らせない
- BP range を広げる
- 1人あたり複数 session を取る
- reference BP と smartphone の同期を厳密に残す

目安:

- `N >= 15-20`
- 各被験者 `3 session` 以上が理想
- 高め・低めの BP 条件を含める
- 失敗例も少し残す

---

## 7. 今いちばんおすすめの進め方

迷ったら、この順で進めるのが安全です。

1. `trend-oriented` を主軸にするか著者が決める
2. participant / session 対応表を回収する
3. `GroupKFold` か `LOSO` を absolute BP の主評価にするか決める
4. ablation を追加する
5. Related Work を増やす
6. 最後に本文の英語を仕上げる

---

## 8. 今の状態を一言で言うと

今の原稿は、

- `文章がまずくて落ちる段階` はかなり抜けた
- でも `研究設計の弱さ` はまだ残っている

という状態です。

だから次に大事なのは、英語の飾りよりも、

- validation
- participant structure
- ablation

を詰めることです。
