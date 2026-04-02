# AROB2025_Presentation.pptx 精査レポート

**対象ファイル:** `/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/AROB/AROB_Nakazawa_Japasese/AROB2025_Presentation.pptx`  
**比較対象:** `/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/AROB/AROB_Nakazawa_Japasese/AROB.tex`  
**精査日:** 2026年1月7日

---

## スライド構成（全21枚）

| Slide | タイトル | 論文対応節 |
|-------|---------|-----------|
| 1 | Title | - |
| 2 | Background: Need for Continuous BP Monitoring | Introduction |
| 3 | PPG & Related Features | Introduction |
| 4 | Problem: Challenge in 30fps Environment | Introduction |
| 5 | Problem: Existing Method Limitations | Introduction |
| 6 | Model: Overview | Methods 2.1 |
| 7 | Model: Asymmetric Sine Wave | Methods 2.1 |
| 8 | Feature 1: Morphological (RTBP) | Methods 2.2 |
| 9 | Feature 2: Model Parameters | Methods 2.2 |
| 10 | Feature 3: Residual (Distortion) | Methods 2.2 |
| 11 | Estimation Overview | Methods 2.3 |
| 12 | Method Comparison | Methods |
| 13 | Preprocessing Pipeline | Methods 2.3.1 |
| 14 | Experimental Setup | Experimental Design 3.1 |
| 15 | Result: Waveform & BP Accuracy | Results 4.1-4.2 |
| 16 | Result: SBP Accuracy Comparison | Results 4.2 |
| 17 | Result: DBP Accuracy Comparison | Results 4.2 |
| 18 | Result: Bland-Altman Analysis | Results 4.2 |
| 19 | Discussion: Why is sinBP(D) Best? | Discussion 5.1-5.2 |
| 20 | Conclusion | Conclusion |

> **注記:** 「Presentation Outline」というスライドは存在しません。

---

## 1. 論文内容との整合性

### ✅ 数値データの一致確認

**完全に一致している項目:**

| 項目 | 論文 (AROB.tex) | スライド | 状態 |
|------|-----------------|----------|------|
| SBP MAE (RTBP) | 20.66 mmHg | 20.66 | ✅ |
| SBP MAE (sinBP(M)) | 19.47 mmHg | 19.47 | ✅ |
| SBP MAE (sinBP(D)) | 18.98 mmHg | 18.98 | ✅ |
| DBP MAE (RTBP) | 16.11 mmHg | 16.11 | ✅ |
| DBP MAE (sinBP(M)) | 15.20 mmHg | 15.20 | ✅ |
| DBP MAE (sinBP(D)) | 14.84 mmHg | 14.84 | ✅ |
| SBP Corr (sinBP(D)) | 0.21 | 0.21 | ✅ |
| DBP Corr (sinBP(D)) | 0.28 | 0.28 | ✅ |
| sinWave MAPE | 18.22% | 18.22% | ✅ |
| Green MAPE | 29.71% | 29.71% | ✅ |
| 被験者数 | 5名 | 5名 | ✅ |
| 年齢範囲 | 20-23歳 | 20-23歳 | ✅ |

**→ 数値データに不一致はありません。**

---

## 2. ⚠️ 戦略的な見せ方に関する懸念

### Slide 21 (Conclusion) の相関係数の提示方法

**現状の記載:**
```
Limitations
Correlation modest，SBP 0.21，DBP 0.28
Small cohort，N=5，young males only
Limited BP range by protocol
```

**懸念点:**
- 相関係数0.2台は統計的には「弱い相関」であり、「精度が出ていない」という印象を与えるリスクがある
- Conclusionでこの数値を強調すると、ネガティブな印象で発表が終わる可能性

**改善提案:**
1. **MAEの改善率を強調する**
   - RTBP → sinBP(D): SBP MAE が 20.66 → 18.98 mmHg（**8.1%改善**）
   - RTBP → sinBP(D): DBP MAE が 16.11 → 14.84 mmHg（**7.9%改善**）

2. **Limitationsの表現を調整**
   ```
   Limitations
   - Instantaneous tracking requires improvement (Corr: SBP 0.21, DBP 0.28)
   - Small cohort (N=5, young males only)
   - Limited BP range by protocol
   ```

3. **Key Findingでより強調すべき内容**
   - 「sinBP(D) achieved the lowest MAE among all methods」
   - 「8% improvement over baseline RTBP」

---

## 3. 重複と流れ（Flow）の評価

### 良好な点 ✅

1. **Slide 4 → 5 の流れ**
   - Slide 4: 30fpsカメラのスペック制約
   - Slide 5: 既存手法RTBPの限界
   - → 段階的な深掘りになっており、「なぜ新手法が必要か」が明確

2. **Slide 6 → 7 のモデル説明**
   - 概要 → 詳細の順で理解しやすい

3. **Discussion (Slide 20) の構成**
   - 「なぜsinBP(D)が最も良いか」を生理学的に説明
   - RTBP vs sinBP(M) vs sinBP(D) の比較図が効果的

### 留意点 ⚠️

1. **Slide 2 (研究室紹介) について**
   - 論文Abstractには無い内容だが、プレゼンの「つかみ」としては有効
   - **注意:** ここに時間をかけすぎると焦点がぼやける
   - → 「我々の研究室ではバイオフィードバックシステムを研究しており、その一環として血圧モニタリングが必要」という**つなぎ**として短く話すこと

2. **Slide 12 (Method Comparison) の位置**
   - 現在: Feature説明 (Slide 8-11) の後
   - 代替案: Feature説明の前に置いて全体像を先に示す方法もある
   - → 現在の順序でも問題はないが、発表時間によって調整を検討

---

## 4. 細かな表記チェック

### 4.1 会議名と年度

| 箇所 | 記載 | 状態 |
|------|------|------|
| ファイル名 | AROB2025_Presentation.pptx | ⚠️ |
| タイトルスライド | AROB 31st 2026 | ✅ |
| フッター | AROB 31st 2026 | ✅ |

**推奨:** ファイル名を `AROB2026_Presentation.pptx` に統一した方がトラブル（提出ミス等）を防げます。

### 4.2 スライド番号

各スライドに手動で `2/21`, `3/21`, ... と記載されています。

- Slide 1: 番号なし ✅
- Slide 2: 2/21 ✅
- ...
- Slide 21: 21/21 ✅

**→ スライド枚数と整合しています。**

### 4.3 英語表現の微修正

**Slide 21 (Conclusion):**

| 現状 | 修正案 |
|------|--------|
| `Correlation modest，SBP 0.21，DBP 0.28` | `Correlation is modest: SBP 0.21, DBP 0.28` または `Modest correlation (SBP: 0.21, DBP: 0.28)` |
| `Small cohort，N=5，young males only` | `Small cohort: N=5, young males only` |
| `Larger and more diverse dataset，age and gender` | `Larger and more diverse dataset (age and gender)` |

**句読点:** 日本語の読点「，」が混在しています。カンマ「,」に統一を推奨。

---

## 5. 論文にあってスライドにない内容

以下は意図的に省略されていると思われますが、質疑応答で聞かれる可能性があります：

1. **倫理審査承認**
   - 論文: 「Declaration of Helsinki遵守、機関の倫理審査承認済み」
   - スライド: 記載なし
   - → 口頭で答えられれば問題なし

2. **Ridge回帰の正則化パラメータ**
   - 論文: λ = 1.0（交差検証で決定）
   - スライド: 詳細記載なし
   - → 質疑で聞かれる可能性あり

3. **血圧変動範囲の制限**
   - 論文: SBP 10-20mmHg、DBP 5-15mmHgの狭い範囲
   - スライド: "Limited BP range by protocol" と記載あり ✅

---

## 6. 総合評価

### 強み
- 論文の核心（30fps制約下での新手法の優位性）がしっかり捉えられている
- 論理構成がクリアで、「なぜ」が視覚化・言語化されている
- 数値データは論文と完全一致

### 改善推奨（優先度順）

| 優先度 | 項目 | 対応 |
|--------|------|------|
| 高 | Conclusionの見せ方 | MAE改善率を強調、相関係数の表現を調整 |
| 中 | 英語表記の統一 | 句読点を半角カンマに統一 |
| 低 | ファイル名 | AROB2026に変更（推奨） |
| 低 | Slide 2の尺 | 発表練習時に時間配分を確認 |

---

## 修正チェックリスト

- [ ] Slide 21: 相関係数の表現を調整（Limitationsの一項目として簡潔に）
- [ ] Slide 21: MAE改善率（約8%）をKey Findingに追加検討
- [ ] 全スライド: 日本語読点「，」を半角カンマ「,」に統一
- [ ] ファイル名: AROB2026_Presentation.pptx に変更（任意）

---

**結論:** 国際学会発表として十分な完成度があります。上記の微調整で更に良くなります。
