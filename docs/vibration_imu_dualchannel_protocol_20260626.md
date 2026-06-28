# 3軸同時取得（PPG＋接触面積＋バイブ/IMU）デュアルチャネル・オシロメトリ プロトコル（2026-06-26）

本書は、1回の押し込みの中で **受動PP（光学PPG）／圧力軸（接触面積）／機械チャネル（バイブ励振×IMU）** を**同時・時刻整合**で取得するための、計測プロトコル・データスキーマ・取り込み（realtime_sessions）仕様を定義する。設計思想の背景は [research_journey_and_pivot](research_journey_and_pivot_20260624.md) と [先行研究まとめ](先行研究まとめと新規性再検討_20260625.md) を参照。

---

## 0. 目的と新規性の位置づけ

- VFE 2024（Mukkamala, Sci Rep）= 「バイブ→IMUダンピングで**力（圧力軸）のみ**推定」「BPは**PPGオシログラム**」「**機種ごと力較正必須**」「安静・Omron単発」。
- 本研究の差分：
  1. **デュアルチャネル・オシログラム**：光学（PPG振幅包絡）と機械（IMU振動減衰の包絡／共振シフト）が**独立にMAPを指す**ことを検証（収束的検証。cosφ≡sys_upslopeと同型の論法）。
  2. **較正フリー**：機械チャネルは「自己正規化の相対圧プロキシ＋共振シフト」として使い、絶対mmHgは接触面積＋CNAP学習へ寄せる。
  3. **寒冷昇圧×被験者内トラッキング＋CNAP連続参照＋受動PP融合**。
- 本書はこの検証を可能にする**生データ取得基盤**を定義する（解析は別PG）。

---

## 1. 衝突分析（なぜバーストゲーティングが要るか）

| 組み合わせ             | 同時取得        | 根拠                                                                                                                                                                                                     |
| ---------------------- | --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PPG（光学）＋接触面積  | ◎ 衝突なし     | 既存アプリで同時記録済。カメラとMotionEventは独立                                                                                                                                                        |
| ＋IMU生収録            | ◎ 衝突なし     | IMUは独立センサ                                                                                                                                                                                          |
| ＋**バイブ発振** | △ ここだけ衝突 | 振動が**光学PPGと接触面積に体動アーティファクトを注入**。175Hz級搬送波は30fps Nyquist超でPPG帯域に**エイリアス**。特に受動PP特徴 `sys_upslope = max dGreen/dt`（微分）は高周波雑音に致命的 |

→ **バイブだけを時分割（バースト）**。OFF窓＝クリーン光学／圧力軸、ON窓＝機械チャネル。押し込み包絡は緩変化なので交互サンプリングでも情報をほぼ失わない。

---

## 2. セッション構成（phase）

既存の phase 状態機械（0=配置, 1=安静, 2=押込, 3=解放）を踏襲。本プロトコルは **phase2（押込スイープ）にバーストバイブを重畳**する。phase1（安静）はバイブOFF固定（受動PPをクリーンに取る）。

- **phase0 配置**：品質ゲート（PPG拍動／露出Y_mean[35,245]／接触位置）通過まで本計測に進まない。バイブOFF。
- **phase1 安静**：数十秒。受動PP用。**バイブOFF**。
- **phase2 押込スイープ**：ガイド一定速度で接触面積を単調増加（10–20秒）。**この間だけバーストバイブON**。
- **phase3 解放**：ゆっくり戻す（ヒステリシス確認）。バイブOFF。

安全：接触面積/圧の上限で過圧停止。バイブ振幅も上限固定。

---

## 3. バーストゲーティング仕様

phase2の間、以下の周期を繰り返す。各サンプルに `vib_state`(0/1) と `burst_id`(0,1,2,…) を付与。

```
[OFF窓] off_ms        → 光学PPG振幅＋接触面積＝光学オシログラム点＋圧力軸
[ガード] guard_ms     → モータのring-down待ち（この区間は両チャネルとも解析除外）
[ON窓]  on_ms         → IMU振動減衰／共振＝機械オシログラム点
[ガード] guard_ms
… phase2終了まで反復
```

### 既定値（meta.jsonに必ず記録、パイロットで調整）

| パラメータ        | 既定     | 備考                                                                                                          |
| ----------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| `off_ms`        | 1500     | OFF窓。**最低1拍ぶんの収縮期立上りを含む長さ**にする（HR≈60–100で1拍≈0.6–1.0s）。受動PP抽出の生命線 |
| `on_ms`         | 500      | ON窓。IMUで振動が定常に達し統計量（RMS/PSD）が取れる長さ                                                      |
| `guard_ms`      | 50       | LRAのring-down（数十ms）を吸収。両チャネル解析除外                                                            |
| `vib_amplitude` | 端末最大 | VFE同様「最大振動」。API31+は`VibrationEffect.createWaveform`/`createOneShot`の振幅固定                   |

### 設計上の注意（パイロットで最優先に詰める）

- **OFF窓は心拍と"うなり"を起こさないこと**：ゲート周期(≈2s)とHR周期(≈1s)が近接すると、OFF窓が収縮期ピークを取りこぼす拍が出る。`off_ms`はHRに対し十分長く（≥1.5×拍間隔）取り、**OFF窓内に必ず1拍以上**入るようにする。実機で`sys_upslope`がOFF窓だけでクリーンに取れるか確認。
- **エイリアス残留検証**：OFF窓のPPGに振動由来の高周波が残っていないか（FFT/微分のノイズ床）を実機で確認し、`guard_ms`を調整。
- **フォールバック**：チョッピングで光学チャネルが劣化して使えない場合は、**phase2を2回連続**（2a=バイブOFFで光学、2b=バイブONで機械）に切替える設計余地を残す（同一セッション・同一被験者）。これは設定フラグで切替。

---

## 4. データスキーマ（非破壊拡張）

### 4.1 Wave_Data（per-frame, ~30fps）— 末尾に追加

現行ヘッダ：

```
経過時間_秒, Green, SinWave, Y_mean, U_mean, V_mean, contact_area, touch_pressure, touch_cx, touch_cy, touch_major, touch_minor, touch_valid, phase, press_target
```

**末尾に追加（既存列順は不変）**：

```
, t_elapsed_ns, vib_state, burst_id
```

- `t_elapsed_ns`：`SystemClock.elapsedRealtimeNanos()`（**全センサ共通の単調時計**。IMU/タッチと直接整合させるための絶対基準）。
- `vib_state`：そのフレーム時刻がOFF=0 / ON=1 / ガード=2（ガードは解析除外）。
- `burst_id`：バーストサイクル連番（OFF→ON→…を1サイクルとし、サイクル毎にインクリメント）。

### 4.2 `_IMU_Data.csv`（**新規・高レート別ファイル**）

IMUはカメラより高レートで取るため**Wave_Dataに混ぜない**（30fpsへ間引くと振動が観測不能になる）。`SENSOR_DELAY_FASTEST`で取得。

```
t_elapsed_ns, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, vib_state, burst_id, phase
```

- `t_elapsed_ns`：Wave_Dataと**同一時計**（`elapsedRealtimeNanos`）。これでカメラ/タッチ/IMUが直接整合。
- accel/gyro：3軸生値（m/s², rad/s）。校正・フィルタは**PC側解析で実施**（端末では生のまま）。
- `vib_state`/`burst_id`/`phase`：各IMUサンプル時刻における状態（バーストスケジューラの状態を参照して付与）。

### 4.3 Training_Data（per-beat）— 末尾に追加

拍区間ごとの代表値（中央値）として、機械チャネルの拍要約を追加（任意・解析補助）：

```
, imu_vib_rms_on_median, imu_resonance_hz_on_median, vib_duty_in_beat
```

- `imu_vib_rms_on_median`：その拍区間内のON窓におけるIMU合成振動RMSの中央値（機械ダンピングの代理）。
- `imu_resonance_hz_on_median`：ON窓のIMU PSDピーク周波数（共振）の中央値（端末で簡易推定 or 0埋めしてPC側算出）。
- `vib_duty_in_beat`：その拍区間でON窓が占めた割合（品質指標）。
- ※端末でのPSD算出が重い場合は0埋めし、**PC側でIMU_Dataから再計算**する方針でも可（その旨meta.jsonに記録）。

### 4.4 meta.json — 追加キー

```json
{
  "vibration": {
    "enabled": true,
    "mode": "burst",                 // "burst" | "dual_sweep"(フォールバック)
    "off_ms": 1500, "on_ms": 500, "guard_ms": 50,
    "amplitude": "device_max",
    "effect": "createWaveform|createOneShot",
    "actuator_type_hint": "LRA|ERM|unknown"
  },
  "imu": {
    "file": "{session}_IMU_Data.csv",
    "sample_rate_hint_hz": 0,         // 実測平均サンプルレート（端末で算出して記録）
    "sensors": "accel(m/s^2),gyro(rad/s)",
    "clock": "elapsedRealtimeNanos"
  },
  "clock": "elapsedRealtimeNanos"     // Wave_Data.t_elapsed_ns / IMU_Data.t_elapsed_ns 共通
}
```

### 4.5 後方互換

- 既存列順・既存ファイルは不変。新規列は**末尾追加**のみ。
- 旧セッション（新列なし）でも既存解析（merge/evaluate/LOSO）が従来通り動くこと。

---

## 5. realtime_sessions 取り込み仕様

### 5.1 端末側保存（セッション終了時・常時自動）

`/sdcard/Download/` に以下を保存：

- `{session}_Wave_Data.csv`（§4.1 拡張版）
- `{session}_IMU_Data.csv`（§4.2 新規）
- `{session}_Training_Data.csv`（§4.3 拡張版）
- 既存：`{session}_元データ.csv` / `RTBP` / `SinBP_*` / `{session}_meta.json`（§4.4 拡張版）

### 5.2 自動取り込み（`Analysis/realtime_pipeline/auto_import_sessions.py`）

- `PRIMARY_SUFFIXES` に **`_IMU_Data.csv` を追加**し、adb pull 対象に含める。
- 完了判定（既存）：`Wave_Data` と `Training_Data` の両方検出で完了。**IMUは任意扱い**（旧端末・OFFセッションでも取り込みが壊れないよう、無くても完了とする）。
- pull 先：`realtime_sessions/{session}/smartphone/{session}_IMU_Data.csv`。

### 5.3 merge への流入（`merge_session.py`）

- 既存の Training_Data×CNAP マージは**列非依存**（全列保持）のため、§4.3 追加列は自動で merged.csv に流入する。
- IMU高レートデータは per-beat マージに不要なため**mergeしない**。`smartphone/` に原本を保持し、機械チャネル解析PG（別途）が `IMU_Data.csv` を直接読む。
- 任意拡張：IMU_Data を `t_elapsed_ns` 基準で Wave_Data にダウンサンプル結合した `*_wave_imu.csv` を作るユーティリティを将来追加（必須ではない）。

---

## 6. 計測手順（オペレータ用）

1. CNAP装着・キャリブレーション。CNAPとスマホの時刻を**共通基準で記録**（既存の同期手順に従う）。
2. アプリ起動 → phase0：指でフロント/対象カメラ＋緑照射を覆い、品質ゲート通過を待つ。
3. phase1（安静, バイブOFF）：数十秒キープ。
4. phase2（押込, バーストバイブON）：ガイドの一定速度に合わせ接触面積を単調増加。OFF窓でPPG・面積、ON窓で振動応答が自動記録される。1セッションで押込を複数回（包絡再現性）。
5. phase3（解放）：ゆっくり戻す。
6. 寒冷昇圧相：別途、安静/昇圧の両相で同手順。
7. 終了 → 全CSV＋meta.jsonがDownloadへ自動保存 → adb接続で `auto_import_sessions.py` が自動取り込み。

---

## 7. 受け入れ基準

- ビルド成功。phase2でバイブがバースト動作（OFF/ガード/ON）し、`vib_state`/`burst_id`がWave_Data/IMU_Dataに正しく刻まれる。
- `_IMU_Data.csv` が生成され、accel/gyro が動き、`t_elapsed_ns` がWave_Dataと同一時計で整合（手動で数サンプル突合）。
- **OFF窓のPPGがクリーン**：OFF窓だけで `sys_upslope` が安静時と同等のノイズ床で取れる（実機FFT/微分で確認）。
- セッション終了で全ファイルがDownloadへ自動保存、`auto_import_sessions.py`で`realtime_sessions/{session}/smartphone/`に`_IMU_Data.csv`含め取り込まれる。
- 旧セッション（新列・IMUなし）でも既存解析PG（`merge_session.py`/`evaluate_session.py`/LOSO）が従来通り通る。

---

## 8. パイロットでの最小検証（機械チャネルが本物か）

実装後、まず以下だけを確認（GATE: デュアルチャネル成立可否）：

1. phase2押込中、押し込み量（接触面積）に対し **(a) IMU振動RMSの包絡** と **(b) 共振周波数シフト** を作図。
2. 同押込の **PPG振幅オシログラム** も作図。
3. **CNAP MAP** に対し、(a)/(b)/PPGの**ピーク/変曲が同じ押し込み点に出るか**（被験者内）。
4. 出れば「光学≡機械の収束MAPプローブ」成立 → デュアルチャネルを論文の軸へ。出なければバイブは VFE 再演（力専用）と判断し、機械チャネルは圧力軸補助に留める。

---

## 9. 実装タスク分割（CLAUDE.md準拠：Claude設計→Codex実装→本人実機）

- **タスクA（Android, 単一Codex）**：IMU収録（`SENSOR_DELAY_FASTEST`, elapsedRealtimeNanos）＋バーストバイブ・スケジューラ＋Wave_Data/Training_Data/meta.json拡張＋`_IMU_Data.csv`保存。`GreenValueAnalyzer.java`/`MainActivity.java` 等が重なるため**並列化しない**。
- **タスクB（Python, 別Codex・並列可）**：`auto_import_sessions.py` に `_IMU_Data.csv` 追加（任意扱い）。`merge_session.py` は非破壊確認のみ（IMUはmergeしない）。Androidと**ファイル非重複**。
- **実機検証は本人**（sandbox不可）：`cd RealTime-IBI-BP && ./gradlew :app:assembleDebug` ＋ §7 受け入れ基準の確認。
