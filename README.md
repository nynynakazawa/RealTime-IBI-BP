<style>
  :root {
    --ink: #172033;
    --muted: #657087;
    --paper: #f7fafc;
    --panel: #ffffff;
    --line: #dce6ef;
    --blue: #2563eb;
    --cyan: #06b6d4;
    --green: #10b981;
    --red: #ef4444;
  }
  body {
    color: var(--ink);
    background: linear-gradient(135deg, #f8fbff 0%, #edf7f5 100%);
    font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  .rtbp-hero {
    padding: 34px 36px;
    border: 1px solid var(--line);
    border-radius: 16px;
    background:
      linear-gradient(135deg, rgba(37, 99, 235, 0.10), rgba(16, 185, 129, 0.10)),
      var(--panel);
    box-shadow: 0 18px 55px rgba(20, 42, 78, 0.12);
  }
  .rtbp-kicker {
    margin: 0 0 10px;
    color: var(--blue);
    font-size: 13px;
    font-weight: 800;
    letter-spacing: 0.12em;
    text-transform: uppercase;
  }
  .rtbp-title {
    margin: 0;
    font-size: 42px;
    line-height: 1.05;
    letter-spacing: 0;
  }
  .rtbp-lead {
    max-width: 880px;
    color: var(--muted);
    font-size: 17px;
    line-height: 1.75;
  }
  .rtbp-badges {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-top: 18px;
  }
  .rtbp-badge {
    padding: 8px 12px;
    border: 1px solid var(--line);
    border-radius: 999px;
    background: rgba(255, 255, 255, 0.74);
    color: #243047;
    font-size: 13px;
    font-weight: 700;
  }
  .rtbp-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    margin: 20px 0;
  }
  .rtbp-card {
    padding: 18px;
    border: 1px solid var(--line);
    border-radius: 14px;
    background: var(--panel);
  }
  .rtbp-card h3 {
    margin: 0 0 8px;
    font-size: 16px;
  }
  .rtbp-card p {
    margin: 0;
    color: var(--muted);
    line-height: 1.65;
  }
  .rtbp-flow {
    padding: 18px;
    border-left: 5px solid var(--cyan);
    border-radius: 12px;
    background: #f2fbfd;
  }
  .rtbp-formula {
    padding: 14px 16px;
    border: 1px solid #cfe5ff;
    border-radius: 12px;
    background: #f8fbff;
    color: #183153;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    overflow-x: auto;
  }
  .rtbp-note {
    padding: 14px 16px;
    border: 1px solid #fee2e2;
    border-radius: 12px;
    background: #fff7f7;
    color: #7f1d1d;
  }
  @media (max-width: 760px) {
    .rtbp-hero { padding: 24px; }
    .rtbp-title { font-size: 32px; }
    .rtbp-grid { grid-template-columns: 1fr; }
  }
</style>

<section class="rtbp-hero">
  <p class="rtbp-kicker">Smartphone PPG / IBI / Absolute BP</p>
  <h1 class="rtbp-title">RealTime-IBI-BP</h1>
  <p class="rtbp-lead">
    スマートフォンのフロントカメラで指先のrPPG波形を取得し、心拍間隔（IBI）と心拍数をリアルタイム算出したうえで、波形特徴量から収縮期血圧（SBP）と拡張期血圧（DBP）を推定するAndroidアプリです。
    バイオフィードバック機能は試験的な付加機能として実装し、主軸はカメラ信号からの心拍算出と血圧推定ロジックに置いています。
  </p>
  <div class="rtbp-badges">
    <span class="rtbp-badge">Android / CameraX</span>
    <span class="rtbp-badge">30 fps ImageAnalysis</span>
    <span class="rtbp-badge">IBI absolute error 2.95%</span>
    <span class="rtbp-badge">MAP / PP regression</span>
    <span class="rtbp-badge">CNAP labels for offline training</span>
    <span class="rtbp-badge">Java + Android SDK 34</span>
  </div>
</section>

## Overview

<div class="rtbp-grid">
  <div class="rtbp-card">
    <h3>Input</h3>
    <p>CameraXのYUVフレームから指先ROIの緑成分を抽出します。ISO、露光、ホワイトバランス、色温度メタデータも記録対象です。</p>
  </div>
  <div class="rtbp-card">
    <h3>Heart</h3>
    <p>緑成分波形を正規化・平滑化し、ピーク間隔からIBIとBPMを算出します。検証上の心拍算出の絶対誤差は2.95%です。</p>
  </div>
  <div class="rtbp-card">
    <h3>Blood Pressure</h3>
    <p>波形振幅、HR、相対TTP、Sin波特徴を使ってMAPとPPを推定し、SBP/DBPへ再構成します。</p>
  </div>
</div>

## Engineering Stack

このプロジェクトは、スマートフォン内でリアルタイムに画像解析・信号処理・推定・記録を完結させるAndroidアプリです。モデル推論サーバーや外部APIへ依存せず、実行時の入力はカメラフレームと端末カメラメタデータです。

| Layer | Stack / API | Purpose |
| --- | --- | --- |
| Platform | Android SDK 34 / minSdk 34 / targetSdk 34 | Android実機でのカメラ入力、UI、CSV保存 |
| Language | Java 8 + Kotlin plugin | 主要ロジックはJava実装。Gradle設定上はKotlin pluginも有効 |
| Camera | CameraX `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | フロントカメラの30 fps解析、ライフサイクル連動 |
| Camera metadata | Camera2 Interop / `CaptureResult` | ISO、露光時間、AWB、焦点距離、F値などの取得 |
| Signal processing | Custom Java logic + JTransforms | rPPG平滑化、ピーク検出、Sin波特徴量抽出 |
| Visualization | MPAndroidChart | 緑成分波形、理想曲線、推定値の表示 |
| ML / RL experiment | TensorFlow Lite, custom DQN classes | バイオフィードバック系の試験的制御 |
| Audio / haptics | MIDI libraries + Android vibration APIs | 音・振動刺激の生成 |
| Data export | CSV + JSON raw resources | セッション記録、係数、解析パイプライン連携 |

### Runtime APIs

- `androidx.camera.core.ImageAnalysis`  
  `YUV_420_888` フレームを逐次受け取り、指先ROIの緑成分を計算します。
- `androidx.camera.camera2.interop.Camera2Interop`  
  `CONTROL_AE_TARGET_FPS_RANGE` を30 fpsへ設定し、Camera2のCaptureCallbackを追加します。
- `android.hardware.camera2.CaptureResult`  
  `SENSOR_SENSITIVITY`、`SENSOR_EXPOSURE_TIME`、`CONTROL_AWB_MODE`、`LENS_APERTURE`、`LENS_FOCUS_DISTANCE` を記録します。
- `android.os.Handler` / `Executor`  
  UI更新とフレーム解析を分離し、解析はsingle-thread executorで処理します。
- `android.Manifest.permission.CAMERA` / storage permissions  
  カメラ計測とCSV保存に利用します。

### Architecture

```text
MainActivity
  -> GreenValueAnalyzer
      -> CameraX ImageAnalysis / Camera2 metadata
      -> Logic1 / Logic2
          -> BaseLogic
              -> IBI / BPM / waveform features
      -> RealtimeBP        -> RTBP MAP/PP model
      -> SinBPDistortion   -> SinBP(D) MAP/PP residual model
      -> SinBPModel        -> SinBP(M) sine-fit MAP/PP model
      -> BPPostProcessor   -> MAP/PP smoothing and SBP/DBP reconstruction
      -> CSV export
```

設計上の要点は、カメラ入力、心拍検出、血圧推定、後処理、記録を明確に分けていることです。`RealtimeMapPpModels` に実行時係数を固定し、`realtime_bp_coefficients.json` に係数生成元のメタデータを残すことで、アプリ内推定とオフライン解析の対応を追跡できます。

## Signal Flow

<div class="rtbp-flow">

1. 指先をフロントカメラに当て、CameraX `ImageAnalysis` で30 fpsのYUVフレームを受け取る。
2. `GreenValueAnalyzer` がフレーム中央の指先領域から緑成分の平均値を取得する。
3. `Logic1` / `Logic2` が緑成分を0-10または0-100に正規化し、移動平均とレンジ正規化でrPPG波形へ変換する。
4. `BaseLogic` がピークを検出し、ピーク間隔からIBIとBPMを更新する。
5. `RealtimeBP`、`SinBPDistortion`、`SinBPModel` が拍ごとの特徴量からMAP/PPを推定し、SBP/DBPへ変換する。
6. `BPPostProcessor` がMAP/PP単位でcausal smoothingをかけ、表示値とCSV出力値を整える。

</div>

## Heart Rate Logic

入力は指先画像そのものではなく、各フレームから抽出された緑成分平均値です。血流量の変化により指先の緑成分が周期的に変動するため、この波形をPPG信号として扱います。

```text
YUV frame
  -> fingertip ROI green average
  -> corrected green value
  -> smoothing
  -> local range normalization
  -> peak detection
  -> IBI = peak_time[n] - peak_time[n-1]
  -> BPM = 60 / IBI(sec)
```

主な処理は以下です。

- `GreenValueAnalyzer.processImage()`  
  CameraXの解析フレームから緑成分を取得し、選択中のロジックへ渡します。
- `Logic1.processGreenValueData()` / `Logic2.processGreenValueData()`  
  緑成分を補正・平滑化し、心拍検出用のリングバッファへ格納します。
- `BaseLogic.detectHeartRateAndUpdate()`  
  適応的不応期、ピークプロミネンス、ピーク時刻補間を使ってピークを検出します。
- `BaseLogic.calculateSmoothedValueRealTime()`  
  IBIを平滑化し、安定した`IBI(Smooth)`と`HR(Smooth)`を表示します。

<div class="rtbp-formula">
BPM = 60.0 / interval_sec<br>
IBI_ms = interval_sec * 1000.0<br>
smoothed_IBI = (last_smoothed_IBI + new_IBI) / 2.0
</div>

## Blood Pressure Logic

血圧推定は、SBP/DBPを直接平滑化するのではなく、平均血圧MAPと脈圧PPを中間表現として使います。これにより、拍ごとの推定と後処理を同じ構造で扱えます。

<div class="rtbp-formula">
MAP = f(features)<br>
PP  = g(features)<br>
DBP = MAP - PP / 3<br>
SBP = DBP + PP
</div>

### Method 1: RTBP

`RealtimeBP` は通常のrPPG形態特徴量からMAP/PPを推定します。

```text
features = [A, HR, V2P_relTTP, P2V_relTTP]
```

- `A`: 谷から山への振幅
- `HR`: 平滑化IBIから算出した心拍数
- `V2P_relTTP`: valley to peakの相対到達時間
- `P2V_relTTP`: peak to valleyの相対到達時間

係数は `bp/RealtimeMapPpModels.java` に固定値として保持されています。学習時にはCNAPを教師ラベルとして使いますが、アプリ実行時はスマートフォン由来特徴量だけで推定します。

### Method 2: SinBP(D)

`SinBPDistortion` はRTBPの特徴量に、拍波形の歪み成分 `E` を加えます。RTBPのMAP/PPをベースにし、`E` による残差補正を加える構成です。

```text
features = [A, HR, V2P_relTTP, P2V_relTTP, E]
```

現在のアクティブな回帰では、`Stiffness` は記録対象ですが主推定式には使われません。

### Method 3: SinBP(M)

`SinBPModel` は1拍分のrPPG波形をSin波としてフィットし、振幅・平均値・位相を特徴量化します。

```text
features = [A, HR, Mean, sin(Phi), cos(Phi)]
```

1拍区間に対してDC成分を除去し、Sin/Cos内積から振幅と位相を得ます。フィット誤差が大きい拍は `poor_sine_fit` として除外されます。

## Post Processing

`BPPostProcessor` は全方式で共通です。推定されたSBP/DBPをMAP/PPへ分解し、MAPとPPに別々のcausal smoothingをかけてからSBP/DBPに戻します。

<div class="rtbp-formula">
MAP_smooth = 0.30 * MAP_raw + 0.70 * MAP_prev<br>
PP_smooth  = 0.50 * PP_raw  + 0.50 * PP_prev
</div>

出力は生理的範囲に収まるようにクリップされます。

- SBP: 60-200 mmHg
- DBP: 40-150 mmHg
- 最小脈圧: 20 mmHg（SinBP系）

## CSV Logging

計測セッションでは、以下の系列をCSVに保存できます。

- raw green value / IBI / smoothed HR
- RTBP: `M1_*`
- SinBP(D): `M2_*`
- SinBP(M): `M3_*`
- カメラメタデータ: ISO、露光時間、ホワイトバランス、色温度、fps
- 参照血圧欄: `ref_SBP`, `ref_DBP`  
  CNAPなどの連続血圧計データは後段の解析パイプラインで結合します。

## Biofeedback

バイオフィードバックは試験的な機能です。`MidiHaptic`、`RandomStimuliGeneration`、`IBIControlEnv`、`DQNAgent` により、IBIや刺激条件に応じた音・振動刺激の制御を試しています。現時点では、心拍・血圧推定ロジックの補助実験として位置づけています。

## Key Files

| File | Role |
| --- | --- |
| `app/src/main/java/com/nakazawa/realtimeibibp/GreenValueAnalyzer.java` | CameraX入力、緑成分抽出、記録、CSV出力 |
| `app/src/main/java/com/nakazawa/realtimeibibp/BaseLogic.java` | IBI/BPM算出、ピーク・谷検出、rPPG特徴量 |
| `app/src/main/java/com/nakazawa/realtimeibibp/Logic1.java` | Logic1用の緑成分補正と0-10正規化 |
| `app/src/main/java/com/nakazawa/realtimeibibp/Logic2.java` | Logic2用の緑成分補正と0-100正規化 |
| `app/src/main/java/com/nakazawa/realtimeibibp/RealtimeBP.java` | RTBP方式のMAP/PP推定 |
| `app/src/main/java/com/nakazawa/realtimeibibp/SinBPDistortion.java` | SinBP(D)方式の歪み特徴量ベース推定 |
| `app/src/main/java/com/nakazawa/realtimeibibp/SinBPModel.java` | SinBP(M)方式のSin波フィット推定 |
| `app/src/main/java/com/nakazawa/realtimeibibp/BPPostProcessor.java` | MAP/PP後処理と表示値生成 |
| `app/src/main/java/com/nakazawa/realtimeibibp/bp/RealtimeMapPpModels.java` | 実行時のMAP/PP係数 |
| `app/src/main/res/raw/realtime_bp_coefficients.json` | 係数生成元メタデータと学習セッション情報 |

## Build

```bash
cd RealTime-IBI-BP
./gradlew assembleDebug
```

主な環境です。

- Android SDK 34
- minSdk 34 / targetSdk 34
- CameraX 1.2.0
- MPAndroidChart 3.1.0
- TensorFlow Lite 2.12.0

## Notes

<div class="rtbp-note">
本アプリの血圧値は研究・検証用の推定値です。医療診断や治療判断を目的とした値ではありません。
</div>
