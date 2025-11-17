# YUV取得から3つのBP算出までのフロー図

## 概要

本ドキュメントでは、カメラからYUV画像を取得してから、3つの異なる方法（RTBP、SinBP(M)、SinBP(D)）で血圧を算出するまでの詳細なフローを説明します。

---

## 1. YUV画像取得とGreen値抽出

### 1.1 カメラ設定と画像取得

```
Camera X API
  ↓
ImageAnalysis (240×180解像度, 30fps)
  ↓
ImageProxy (各フレーム)
  ↓
ImageFormat.YUV_420_888
```

**実装場所**: `GreenValueAnalyzer.bindImageAnalysis()`
- Camera X APIを使用
- 解像度: 240×180
- フレームレート: 30fps
- バックプレッシャー戦略: `STRATEGY_KEEP_ONLY_LATEST`

### 1.2 Green値抽出

```
YUV_420_888 Image
  ↓
getGreen(img)
  ↓
Uプレーン (Planes[1]) から値を取得
  ↓
中央領域を除外した周辺領域の平均値
  (sx = w/4, ex = 3w/4, sy = h/4, ey = 3h/4 を除外)
  ↓
生Green値 (g)
```

**実装場所**: `GreenValueAnalyzer.getGreen()`
- Uプレーンから値を取得
- 中央領域（1/4～3/4）を除外し、周辺領域の平均値を計算
- これにより、顔の中央部ではなく周辺部の血流変化を検出

---

## 2. BaseLogic処理（共通処理）

### 2.1 Green値の前処理

```
生Green値 (g)
  ↓
LogicProcessor.processGreenValueData(g)
  ↓
[BaseLogic共通処理]
  ├─ window配列に追加 (リングバッファ)
  ├─ Green値の正規化・補正
  └─ ピーク検出準備
```

**実装場所**: `BaseLogic.processGreenValueData()`
- リングバッファ（`window[WINDOW_SIZE]`）に値を追加
- `WINDOW_SIZE = 240`（約8秒分のデータ）

### 2.2 ピーク検出とIBI計算

```
window配列
  ↓
detectHeartRateAndUpdate()
  ↓
[ピーク検出条件]
  - framesSinceLastPeak >= REFRACTORY_FRAMES (8フレーム)
  - previous1 > previous2 > previous3 > previous4
  - previous1 > currentVal
  ↓
ピーク検出成功
  ↓
IBI計算: IBI = (currentTime - lastPeakTime) / 1000.0
  ↓
BPM計算: BPM = 60.0 / interval
  ↓
LogicResult生成
  ├─ correctedGreenValue
  ├─ IBI (ms)
  ├─ heartRate (BPM)
  └─ bpmSd (標準偏差)
```

**実装場所**: `BaseLogic.detectHeartRateAndUpdate()`
- 不応期: `REFRACTORY_FRAMES = 8`フレーム
- IBI範囲チェック: 0.25秒～1.2秒（50～240 BPM相当）
- BPM履歴を保持し、平均値と標準偏差を計算

### 2.3 特徴量抽出（BaseLogic）

```
ピーク検出後
  ↓
[非同期処理: updateAverageValuesAsync()]
  ├─ 谷→山パターン検出 (V2P)
  │   ├─ detectValleyForValleyToPeak()
  │   └─ detectPeakForValleyToPeak()
  ├─ 山→谷パターン検出 (P2V)
  │   ├─ detectPeakForPeakToValley()
  │   └─ detectValleyForPeakToValley()
  └─ 特徴量計算
      ├─ V2P_relTTP (谷→山の相対TTP)
      ├─ P2V_relTTP (山→谷の相対TTP)
      ├─ V2P_Amplitude (谷→山の振幅)
      └─ P2V_Amplitude (山→谷の振幅)
  ↓
平均値計算 (10拍分の移動平均)
  ├─ averageValleyToPeakRelTTP
  ├─ averagePeakToValleyRelTTP
  ├─ averageValleyToPeakAmplitude
  └─ averagePeakToValleyAmplitude
```

**実装場所**: `BaseLogic.updateAverageValuesAsync()`
- 各拍ごとに非同期で特徴量を抽出
- 10拍分の移動平均を計算
- `relTTP`: 相対Time-To-Peak（IBIに対する相対的な時間）

### 2.4 コールバック呼び出し

```
ピーク検出後
  ↓
[3つのコールバックを並行実行]
  ├─ BPFrameCallback.onFrame()
  │   └─ RealtimeBP.update() を呼び出し
  ├─ SinBPCallback.onFrame()
  │   └─ SinBPDistortion.onFrame() を呼び出し
  └─ SinBPModelCallback.onFrame()
      └─ SinBPModel.onFrame() を呼び出し
```

**実装場所**: `BaseLogic.detectHeartRateAndUpdate()`
- 各BP推定器に並行してデータを送信

---

## 3. RTBP (RealtimeBP) フロー

### 3.1 データ受信

```
BaseLogic.BPFrameCallback.onFrame()
  ↓
RealtimeBP.update(correctedGreenValue, smoothedIbiMs)
  ↓
[ISOチェック]
  - currentISO < 300 の場合、処理をスキップ
  ↓
estimateAndNotify(smoothedIbiMs)
```

**実装場所**: `RealtimeBP.update()`
- ISO値が300未満の場合は処理をスキップ

### 3.2 特徴量取得

```
estimateAndNotify()
  ↓
BaseLogicから最新値を取得
  ├─ valleyToPeakRelTTP (logicRef.averageValleyToPeakRelTTP)
  ├─ peakToValleyRelTTP (logicRef.averagePeakToValleyRelTTP)
  └─ amplitude (logicRef.averageValleyToPeakAmplitude)
  ↓
HR計算
  - smoothedIBIから計算: HR = 60000.0 / smoothedIBI
  - フォールバック: HR = 60000.0 / ibiMs
```

**実装場所**: `RealtimeBP.estimateAndNotify()`
- BaseLogicの平均値を直接使用

### 3.3 血圧推定（線形回帰）

```
特徴量取得後
  ↓
[線形回帰式]
  SBP = C0 + C1*A + C2*HR + C3*V2P_relTTP + C4*P2V_relTTP
  DBP = D0 + D1*A + D2*HR + D3*V2P_relTTP + D4*P2V_relTTP
  ↓
係数:
  C0=80, C1=0.5, C2=0.1, C3=0.1, C4=-0.1
  D0=60, D1=0.3, D2=0.05, D3=0.05, D4=-0.05
  ↓
範囲制限
  - SBP: 60～200 mmHg
  - DBP: 40～150 mmHg
```

**実装場所**: `RealtimeBP.estimateAndNotify()`
- BaseLogicの形態学的特徴量のみを使用
- シンプルな線形回帰モデル

### 3.4 平均値計算と通知

```
血圧推定後
  ↓
履歴に追加 (sbpHist, dbpHist)
  - 最大10拍分を保持
  ↓
robustAverage() で平均値計算
  - 外れ値を除外した平均値
  ↓
BPListener.onBpUpdated() を呼び出し
  ├─ sbp, dbp (現在値)
  └─ sbpAvg, dbpAvg (平均値)
```

**実装場所**: `RealtimeBP.estimateAndNotify()`
- 10拍分の移動平均を計算
- リスナーに通知

---

## 4. SinBP(M) (SinBPModel) フロー

### 4.1 データ受信とバッファリング

```
BaseLogic.SinBPModelCallback.onFrame()
  ↓
SinBPModel.onFrame(correctedGreenValue, timestampMs)
  ↓
[ISOチェック]
  - currentISO < 300 の場合、処理をスキップ
  ↓
リングバッファに追加
  ├─ ppgBuffer.add(correctedGreenValue)
  └─ timeBuffer.add(timestampMs)
  - BUFFER_SIZE = 90 (30fps × 3秒)
```

**実装場所**: `SinBPModel.onFrame()`
- PPG値とタイムスタンプをバッファに保存

### 4.2 ピーク検出

```
バッファ更新後
  ↓
[ピーク検出]
  - 移動窓最大値検出
  - 不応期: REFRACTORY_PERIOD_MS = 500ms
  - 前回のピークから500ms以上経過しているかチェック
  ↓
ピーク検出成功
  ↓
IBI計算: IBI = currentPeakTime - lastPeakTime
```

**実装場所**: `SinBPModel.onFrame()`
- BaseLogicとは独立したピーク検出

### 4.3 Sin波フィット

```
ピーク検出後
  ↓
processPeak(peakValue, peakTime)
  ↓
[1拍分のデータ抽出]
  - previousPeakTime から lastPeakTime までのデータ
  ↓
fitSineWave(beatSamples, ibi)
  ↓
[Sin波フィット]
  - 最小二乗法でSin波パラメータを推定
  - s(t) = mean + A * sin(2πt/T + φ)
  ↓
パラメータ抽出
  ├─ currentA (振幅)
  ├─ currentMean (平均値)
  ├─ currentPhi (位相)
  └─ currentIBI (周期)
```

**実装場所**: `SinBPModel.fitSineWave()`
- 1拍分のデータに対してSin波をフィット
- 最小二乗法でパラメータを推定

### 4.4 血圧推定（線形回帰）

```
Sin波パラメータ取得後
  ↓
estimateBPFromModel()
  ↓
HR計算
  - smoothedIBIから計算: HR = 60000.0 / smoothedIBI
  ↓
[線形回帰式]
  SBP = α0 + α1*A + α2*HR + α3*Mean + α4*Phi
  DBP = β0 + β1*A + β2*HR + β3*Mean + β4*Phi
  ↓
係数:
  α0=90.0, α1=4.5, α2=0.25, α3=0.15, α4=2.0
  β0=65.0, β1=2.8, β2=0.12, β3=0.08, β4=1.2
  ↓
範囲制限
  - SBP: 60～200 mmHg
  - DBP: 40～150 mmHg
```

**実装場所**: `SinBPModel.estimateBPFromModel()`
- Sin波パラメータのみを使用
- BaseLogicの特徴量は使用しない

### 4.5 平均値計算と通知

```
血圧推定後
  ↓
updateHistory(sbp, dbp)
  ↓
履歴に追加 (sinSbpHist, sinDbpHist)
  - 最大10拍分を保持
  ↓
平均値計算
  ↓
SinBPModelListener.onSinBPUpdated() を呼び出し
  ├─ sbp, dbp (現在値)
  └─ sbpAvg, dbpAvg (平均値)
```

**実装場所**: `SinBPModel.updateHistory()`
- 10拍分の移動平均を計算

---

## 5. SinBP(D) (SinBPDistortion) フロー

### 5.1 データ受信とバッファリング

```
BaseLogic.SinBPCallback.onFrame()
  ↓
SinBPDistortion.onFrame(correctedGreenValue, timestampMs)
  ↓
[ISOチェック]
  - currentISO < 300 の場合、処理をスキップ
  ↓
リングバッファに追加
  ├─ ppgBuffer.add(correctedGreenValue)
  └─ timeBuffer.add(timestampMs)
  - BUFFER_SIZE = 90 (30fps × 3秒)
```

**実装場所**: `SinBPDistortion.onFrame()`
- PPG値とタイムスタンプをバッファに保存

### 5.2 ピーク検出

```
バッファ更新後
  ↓
[ピーク検出]
  - 移動窓最大値検出
  - 不応期: REFRACTORY_PERIOD_MS = 500ms
  ↓
ピーク検出成功
  ↓
IBI計算: IBI = currentPeakTime - lastPeakTime
```

**実装場所**: `SinBPDistortion.onFrame()`
- BaseLogicとは独立したピーク検出

### 5.3 1拍遅延処理と特徴量抽出

```
ピーク検出後
  ↓
processPeak(peakValue, peakTime)
  ↓
[1拍遅延処理]
  - 前の拍のデータ (previousPeakTime ～ lastPeakTime) を処理
  - 現在の拍のデータは次回処理
  ↓
[1拍分のデータ抽出]
  - extractBeatSamplesWithTime()
  ↓
[収縮期/拡張期比率計算]
  - calculateSystoleDiastoleRatio()
  - 動的な比率を計算（デフォルト: 1/3:2/3）
  ↓
[Sin波フィット]
  - fitSineWave(beatSamples, ibi)
  - 動的な比率を使用
  ↓
パラメータ抽出
  ├─ currentA (振幅)
  ├─ currentPhi (位相)
  └─ currentIBI (周期)
```

**実装場所**: `SinBPDistortion.processPeak()`
- **1拍遅延処理**: 前の拍のデータを処理
- 動的な収縮期/拡張期比率を計算

### 5.4 歪み指標計算

```
Sin波フィット後
  ↓
calculateDistortion(beatSamples, A, phi, ibi, systoleRatio, diastoleRatio)
  ↓
[非対称サイン波モデル再構成]
  - asymmetricSineBasis(t, T, systoleRatio, diastoleRatio)
  - 動的な比率を使用
  ↓
[理想波形計算]
  - idealValue = mean + A * sNorm
  - sNorm: 非対称サイン波基底（0～1に正規化）
  ↓
[残差計算]
  - error = beatSamples[i] - idealValue
  - sumSquaredError += error²
  ↓
[歪み指標計算]
  - E = sqrt(sumSquaredError / N)  (RMS誤差)
  ↓
[理想曲線パラメータ保存]
  ├─ currentMean
  ├─ currentAmplitude
  └─ currentIBIms
  ↓
[理想曲線の時間範囲設定]
  - idealCurveStartTime = previousPeakTime
  - idealCurveEndTime = lastPeakTime
  - hasIdealCurve = true
```

**実装場所**: `SinBPDistortion.calculateDistortion()`
- 非対称サイン波モデルからの残差を計算
- 歪み指標Eを算出
- 理想曲線のパラメータを保存（UI表示用）

### 5.5 血圧推定（3段階推定）

```
歪み指標計算後
  ↓
estimateBP(A, ibi, E)
  ↓
[第1段: ベースBP計算]
  HR計算: HR = 60000.0 / smoothedIBI
  ↓
  SBP_base = ALPHA0 + ALPHA1*A + ALPHA2*HR
  DBP_base = BETA0 + BETA1*A + BETA2*HR
  ↓
  係数:
    ALPHA0=80.0, ALPHA1=5.0, ALPHA2=0.3
    BETA0=60.0, BETA1=3.0, BETA2=0.15
  ↓
[第2段: 血管特性補正]
  BaseLogicから血管特性を取得
    ├─ valleyToPeakRelTTP
    ├─ peakToValleyRelTTP
    └─ Stiffness_sin = E * sqrt(A)
  ↓
  SBP_vascular = SBP_base + ALPHA3*V2P_relTTP + ALPHA4*P2V_relTTP + ALPHA5*Stiffness_sin
  DBP_vascular = DBP_base + BETA3*V2P_relTTP + BETA4*P2V_relTTP + BETA5*Stiffness_sin
  ↓
  係数:
    ALPHA3=5.0, ALPHA4=3.0, ALPHA5=0.1
    BETA3=3.0, BETA4=2.0, BETA5=0.05
  ↓
[第3段: 歪み補正]
  deltaSBP = ALPHA6 * E
  deltaDBP = BETA6 * E
  ↓
  SBP_refined = SBP_vascular + deltaSBP
  DBP_refined = DBP_vascular + deltaDBP
  ↓
  係数:
    ALPHA6=0.1, BETA6=0.05
  ↓
[制約適用]
  - SBP >= DBP + 10
  - SBP: 60～200 mmHg
  - DBP: 40～150 mmHg
```

**実装場所**: `SinBPDistortion.estimateBP()`
- **3段階推定**: ベース → 血管特性補正 → 歪み補正
- BaseLogicの特徴量と歪み指標Eを組み合わせ

### 5.6 平均値計算と通知

```
血圧推定後
  ↓
updateHistory(sbp, dbp)
  ↓
履歴に追加 (sinSbpHist, sinDbpHist)
  - 最大10拍分を保持
  ↓
平均値計算
  ↓
SinBPListener.onSinBPUpdated() を呼び出し
  ├─ sbp, dbp (現在値)
  └─ sbpAvg, dbpAvg (平均値)
```

**実装場所**: `SinBPDistortion.updateHistory()`
- 10拍分の移動平均を計算

---

## 6. データ記録（CSV保存）

### 6.1 記録条件

```
isRecordingActive == true
  AND
isDetectionValid() == true (ISO >= 300)
  ↓
データ記録開始
```

**実装場所**: `GreenValueAnalyzer.processImage()`

### 6.2 記録されるデータ

```
[各フレームごとに記録]
  ├─ recValue: correctedGreenValue
  ├─ recIbi: IBI
  ├─ recSd: BPM標準偏差
  ├─ recValTs: タイムスタンプ
  └─ recIbiTs: IBIタイムスタンプ
  ↓
[新しい拍が検出された場合のみ記録]
  ├─ RTBP特徴量
  │   ├─ recM1_A: 振幅
  │   ├─ recM1_HR: 心拍数
  │   ├─ recM1_V2P_relTTP: 谷→山relTTP
  │   ├─ recM1_P2V_relTTP: 山→谷relTTP
  │   ├─ recM1_SBP: SBP
  │   └─ recM1_DBP: DBP
  ├─ SinBP(D)特徴量
  │   ├─ recM2_A: 振幅
  │   ├─ recM2_HR: 心拍数
  │   ├─ recM2_V2P_relTTP: 谷→山relTTP
  │   ├─ recM2_P2V_relTTP: 山→谷relTTP
  │   ├─ recM2_Stiffness: Stiffness_sin
  │   ├─ recM2_E: 歪み指標
  │   ├─ recM2_SBP: SBP
  │   └─ recM2_DBP: DBP
  └─ SinBP(M)特徴量
      ├─ recM3_A: 振幅
      ├─ recM3_HR: 心拍数
      ├─ recM3_Mean: 平均値
      ├─ recM3_Phi: 位相
      ├─ recM3_SBP: SBP
      └─ recM3_DBP: DBP
```

**実装場所**: `GreenValueAnalyzer.processImage()`
- 新しい拍が検出された場合のみ特徴量を記録

### 6.3 CSVファイル保存

```
記録終了時 (stopRecording())
  ↓
[6つのCSVファイルを保存]
  1. _元データ.csv
     - 経過時間_秒, Green, IBI, Smoothed IBI
  2. _RTBP.csv
     - 経過時間_秒, SBP, DBP
  3. _SinBP_M.csv
     - 経過時間_秒, SBP, DBP
  4. _SinBP_D.csv
     - 経過時間_秒, SBP, DBP
  5. _Wave_Data.csv
     - 経過時間_秒, Green, SinWave
  6. _Training_Data.csv
     - 経過時間_秒, M1_A, M1_HR, M1_V2P_relTTP, M1_P2V_relTTP, M1_SBP, M1_DBP,
       M2_A, M2_HR, M2_V2P_relTTP, M2_P2V_relTTP, M2_Stiffness, M2_E, M2_SBP, M2_DBP,
       M3_A, M3_HR, M3_Mean, M3_Phi, M3_SBP, M3_DBP
```

**実装場所**: 
- `GreenValueAnalyzer.saveRawDataToCsv()`
- `GreenValueAnalyzer.saveRTBPToCsv()`
- `GreenValueAnalyzer.saveSinBPMToCsv()`
- `GreenValueAnalyzer.saveSinBPDToCsv()`
- `GreenValueAnalyzer.saveWaveDataToCsv()`
- `GreenValueAnalyzer.saveTrainingDataToCsv()`

---

## 7. まとめ：3つのBP算出方法の違い

| 項目 | RTBP | SinBP(M) | SinBP(D) |
|------|------|----------|----------|
| **データソース** | BaseLogicの特徴量 | Sin波フィット | Sin波フィット + BaseLogic |
| **ピーク検出** | BaseLogicを使用 | 独自のピーク検出 | 独自のピーク検出 |
| **処理タイミング** | リアルタイム | リアルタイム | 1拍遅延 |
| **特徴量** | A, HR, V2P_relTTP, P2V_relTTP | A, HR, Mean, Phi | A, HR, V2P_relTTP, P2V_relTTP, Stiffness_sin, E |
| **推定方法** | 線形回帰（1段階） | 線形回帰（1段階） | 3段階推定 |
| **非対称性** | なし | なし | あり（動的比率） |
| **歪み指標** | なし | なし | あり（E） |

---

## 8. 重要なポイント

### 8.1 ISO値チェック
- すべてのBP推定器で `currentISO < 300` の場合は処理をスキップ
- ISO値は `CameraCaptureSession.CaptureCallback` から取得

### 8.2 1拍遅延処理（SinBP(D)のみ）
- SinBP(D)は前の拍のデータを処理
- これにより、より正確な非対称サイン波モデルを構築

### 8.3 動的な収縮期/拡張期比率（SinBP(D)のみ）
- 各拍ごとに実測データから比率を計算
- デフォルトは1/3:2/3だが、実測値に基づいて動的に変更

### 8.4 理想曲線（SinBP(D)のみ）
- UI表示用の理想曲線を生成
- `getIdealCurveValue()` で任意の時刻の値を取得可能
- 1拍遅延で計算された非対称サイン波モデルを使用

---

## 9. クラス構成図

```
GreenValueAnalyzer
  ├─ Camera X API (ImageAnalysis)
  ├─ BaseLogic (Logic1/Logic2)
  │   ├─ ピーク検出
  │   ├─ IBI計算
  │   └─ 特徴量抽出
  ├─ RealtimeBP
  │   └─ RTBP推定
  ├─ SinBPModel
  │   └─ SinBP(M)推定
  └─ SinBPDistortion
      └─ SinBP(D)推定
```

---

## 10. 参考文献

- `GreenValueAnalyzer.java`: メイン処理クラス
- `BaseLogic.java`: 共通処理クラス
- `RealtimeBP.java`: RTBP推定クラス
- `SinBPModel.java`: SinBP(M)推定クラス
- `SinBPDistortion.java`: SinBP(D)推定クラス

