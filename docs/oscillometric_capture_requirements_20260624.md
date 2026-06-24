# 押し込みオシロメトリ＋多チャネル取得 要件定義（2026-06-24）

## 0. 背景と目的
緑PPG（受動）は脈圧(PP)軸しか持てず、平均圧(MAP)/絶対値は原理的に取れないことを実証済み。
そこで「形を見る」から「**圧をかけて測る（押し込みオシロメトリ）**」へ転換し、MAP/絶対値軸を物理的に取りに行く。
あわせて将来解析のため、取れる生データ（RGB・接触面積・カメラパラメータ）を**漏れなく自動保存**する。

- 取得構成：**フロントカメラ**（パンチホール）＋**画面から緑照射**＋指の腹で覆って押し込む。
- 制約：**カフ等の外部ツール不可／ユーザー側のカフ較正なし**。CNAPは「研究用の正解参照」としてのみ使用（デプロイ時は不要）。
- 可搬性：**廉価版を除くほぼ全機種で動く**こと。Pixel固有（Pro温度計・画面下指紋API・力センサ）に依存しない。

## 1. 取得データ（ログ schema 拡張）
既存の per-frame / per-beat 記録基盤（`rec*` リスト→CSV）に以下を**追加**する。既存列は壊さない（後方互換）。

### 1.1 per-frame（Wave_Data 相当：30fps の生時系列）
| 列 | 内容 | 取得元 |
| --- | --- | --- |
| 経過時間_秒 | 既存 | 既存 |
| Green | 既存（緑平均） | 既存 |
| Red, Blue | **追加**：同一ROIの赤・青平均（YUV→RGB変換, `processImage`/`getGreen`と同じROI） | 新規 |
| DC_Green | **追加**：緑のDC（低周波/ベースライン）＝遮光量の指標 | 新規 |
| contact_area | **追加**：接触楕円面積の代理（`getTouchMajor*getTouchMinor` or `getSize`） | MotionEvent |
| touch_pressure | **追加**：`MotionEvent.getPressure()`（容量性由来の代理値） | MotionEvent |
| touch_cx, touch_cy | **追加**：接触重心座標（px） | MotionEvent |
| touch_major, touch_minor | **追加**：接触楕円の長短径 | MotionEvent |
| phase | **追加**：プロトコル相（0=配置,1=安静,2=押込,3=解放）※下記2参照 | アプリ状態 |
| press_target | **追加**：押し込みガイドの目標値（進捗0–1） | アプリ状態 |
| SinWave | 既存 | 既存 |

※ R/B/接触面積は**フレーム毎**に記録。タッチイベントが無いフレームは直近値を保持（前方補完）かつ `touch_valid`(0/1) を別列で持つ。

### 1.2 per-beat（Training_Data 相当：拍ごとの特徴＋カメラパラメータ）
- 既存の per-beat 列（M1/M2/M3, ISO, exposure, color_temperature 等）はそのまま。
- **追加**：その拍区間における contact_area / touch_pressure / Red / Blue / DC_Green の代表値（中央値）と phase。

### 1.3 注意（color_temperature 問題）
現状 `color_temperature` は `awbModes[0]` 由来で全0＝死に列。**RGB実測（Red/Green/Blue）を一次情報として残す**ことで色情報を担保する。awbGains が取得できるなら別列 `awb_r_gain/awb_b_gain` も追加（任意）。

## 2. セッション・プロトコル（相 phase）
1セッションを以下の連続フェーズで構成。各相を per-frame の `phase` 列に記録。

- **phase0 配置（placement）**：品質ゲートを満たすまで計測本体に進まない。
  - ゲート：(a) PPG SNR/拍動振幅 ≥ 閾値（カメラが覆われている証拠）。(b) 画像が飽和/暗すぎでない。(c) 接触重心が「カメラ穴の既知オフセット下」±許容内（位置）。
- **phase1 安静PPG（resting）**：数十秒。受動PP用（従来通り）。
- **phase2 押し込み（press）**：ガイドに従い**一定速度で徐々に押し込む**（10–20秒）。
  - 「一定速度」の与え方：**接触面積を均一速度で増やす**進捗バー（`press_target` 0→1）。被験者は自身の接触面積がバーに追従するよう押す（面積はリアルタイム表示）。※絶対mmHgは後で学習で復元（機種ごと力較正はしない方針）。
  - 安全：過圧防止（面積/圧の上限、痛み回避のため上限到達で停止）。
- **phase3 解放（release）**：ゆっくり戻す（任意、ヒステリシス確認用）。

各相の開始/終了時刻と品質ゲート結果をセッションメタ（JSON）に残す。

## 3. UI 要件（フロントカメラ＝ステータスバー帯の制約）
- カメラ穴の**上に直接ターゲットを描かない**（システムUI領域）。
- **穴の少し下に「指先ランディングゾーン」**＋周囲に**緑の照射リング**を表示して誘導。
- **真の判定は信号ベース**（UIは近似誘導）：
  - 覆えている＝PPG拍動が出ている。位置＝タッチ重心が「穴の既知オフセット下」に入る。
- **押し込みガイド**：phase2 で進捗バー（目標）＋現在の接触面積バーを並べ、追従を促す。
- 任意：immersive mode＋display-cutout で穴付近描画も可だが**依存しない**。
- カメラ穴↔タッチ重心の既知オフセットは**機種ごと定数1個**として保持（機種DBではない）。

## 4. RGB（多波長）取得方針
- **ベースライン（必須）**：緑照射のまま、カメラの **R/G/B 各平均を毎フレーム記録**（cheap, 後方互換）。緑照射下では主に緑が情報を持つが、R/Bも残す。
- **任意（phase-2 of design）**：画面の発色を **R→G→B（or 白）で順次切替**し、各色照射下のカメラ反射を取る「画面駆動マルチ波長」モード。設定フラグで切替。今回の必須実装はベースラインのみ、順次発光は将来拡張として設計余地を残す。

## 5. 出力ファイルと**自動エクスポート**
### 5.1 端末側の保存（常時・自動）
- セッション終了時、以下を**必ず** `/sdcard/Download/`（または MediaStore）へ保存：
  - `{session}_Wave_Data.csv`（§1.1, R/B/接触面積/phase 追加版）
  - `{session}_Training_Data.csv`（§1.2 追加版）
  - `{session}_元データ.csv`、既存の RTBP/SinBP_* CSV（従来通り）
  - `{session}_meta.json`（相の時刻・品質ゲート・端末/オフセット定数・座標系）
- **Wave_Data を含む全生データを、強制吸い出しなしで毎回保存**（今回の主要要望）。

### 5.2 解析側への自動流入（realtime_sessions へ）
端末→Mac は直接書けないため、**自動同期パイプライン**を用意：
- 方式：Mac 側に**監視＆取り込みスクリプト**（例 `Analysis/realtime_pipeline/auto_import_sessions.py`）。
  - `adb` 接続時に `/sdcard/Download/` の新規 `*_Wave_Data.csv` 等を検出 → `realtime_sessions/{session}/smartphone/` に pull → `merge_session.py` を呼んで `{session}_merged.csv` 生成 → 取り込み済みを記録（再取得防止）。
  - セッション終了の検出：`{session}_meta.json` の存在＋完了フラグをトリガにする。
- 将来：端末側からの自動アップロード（共有/クラウド）も設計余地を残すが、まずは adb 自動 pull で実現。

## 6. 後方互換・非破壊
- 既存 CSV 列順は変えず**末尾に追加**。`merge_session.py` / `session_filtered_input.py` / 既存解析（`load_reference_rows` 等）が壊れないこと。
- 既存セッションの再解析が従来通り通ること。

## 7. 受け入れ基準（実装後の確認）
- アプリがビルド成功。新規列が Wave_Data/Training_Data に出力される。
- 接触面積・R/B・phase が実機ログに記録される（値が動く）。
- セッション終了で全ファイルが Download に自動保存され、`auto_import_sessions.py` で `realtime_sessions/{session}/` に取り込まれ `{session}_merged.csv` が生成される。
- 既存解析PG（例 run_current_loso_base_variants.py）が引き続き動く。

## 8. de-risk 段階（解析の狙い）
1. まず **相対オシロメトリの成立**：押し込み包絡のピーク/脈波消失点が CNAP の MAP/SBP と被験者内で対応するか（接触面積の自己正規化＋光学アンカー、絶対化なし）。
2. 次に **絶対mmHg**：機種校正に頼らず、ガイド押し込みで再現性を上げた phone 特徴 → CNAP 学習で復元。
3. RGB・接触面積・DC遮光は補助軸として同時ログ。
