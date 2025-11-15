# サイン波ベースBP推定の実装計画

## 概要
correctedGreenValueの擬似PPGからサイン波近似を用いて血圧を推定する新しいアルゴリズムを実装します。
既存の`RealtimeBP`と並行して動作し、UIには`SinSBP`と`SinDBP`として表示されます。

## アーキテクチャ

### 1. 新規クラス構成

```
sinBPDistortion.java
├── 拍検出・切り出し
├── サイン波フィッティング
├── ベースBP推定
├── 歪み指標計算
└── 詳細補正BP推定
```

### 2. データフロー

```
BaseLogic (correctedGreenValue, 30fps)
    ↓
    ├─→ RealtimeBP (既存) → SBP/DBP
    └─→ SinBP (新規) → SinSBP/SinDBP
         ↓
    MainActivity (UI更新)
```

## 改良案: 血管特性を反映したSinBP

### 血管の硬さ・血流特性の取り込み方法

#### 1. **オーグメンテーション指数（AI）の簡易計算**
```java
// サイン波フィット後に追加計算
double peakValue = A;  // サイン波の最大値
double valleyValue = A * Math.cos(phi);  // サイン波の最小値
double ai = (peakValue - valleyValue) / peakValue;  // 簡易AI
```

#### 2. **相対TTP（Time-to-Peak）の推定**
```java
// サイン波の位相からTTPを推定
double ttp = (phi / (2 * Math.PI)) * ibiMs;  // 位相から時間に変換
double relTTP = ttp / ibiMs;  // 相対TTP
```

#### 3. **血管硬さ指標の追加**
```java
// サイン波の歪みから血管特性を推定
double stiffness = E * Math.sqrt(A);  // 歪み×振幅の組み合わせ
```

### 改良されたSinBPアルゴリズム

#### 特徴量の拡張
```java
// 基本パラメータ（既存）
double A;      // 振幅
double ibi;    // IBI
double phi;    // 位相
double E;      // 歪み

// 血管特性パラメータ（新規追加）
double ai;         // オーグメンテーション指数
double relTTP;     // 相対TTP
double stiffness;  // 血管硬さ指標
```

#### 改良されたBP推定式
```java
// ベースBP（基本パラメータ）
double sbpBase = ALPHA0 + ALPHA1 * A + ALPHA2 * hr;
double dbpBase = BETA0 + BETA1 * A + BETA2 * hr;

// 血管特性補正
double sbpVascular = sbpBase + ALPHA3 * ai + ALPHA4 * relTTP + ALPHA5 * stiffness;
double dbpVascular = dbpBase + BETA3 * ai + BETA4 * relTTP + BETA5 * stiffness;

// 歪み補正（既存）
double sbpRefined = sbpVascular + C1 * E;
double dbpRefined = dbpVascular + D1 * E;
```

## 実装手順

### Phase 1: sinBPDistortion.javaクラスの作成

**ファイル**: `app/src/main/java/com/nakazawa/realtimeibibp/sinBPDistortion.java`

#### 主要フィールド
```java
public class SinBP {
    // リングバッファ（30fps × 3秒 = 90サンプル程度）
    private Deque<Double> ppgBuffer = new ArrayDeque<>(90);
    private Deque<Long> timeBuffer = new ArrayDeque<>(90);
    
    // 拍検出用
    private double lastPeakValue = 0;
    private long lastPeakTime = 0;
    private double lastValidIBI = 0;  // 異常値検出用
    
    // 拍ごとの結果
    private double currentA = 0;      // 振幅
    private double currentIBI = 0;    // IBI (ms)
    private double currentPhi = 0;    // 位相
    private double currentE = 0;      // 歪み指標
    
    // BP推定結果
    private double lastSinSBP = 0;
    private double lastSinDBP = 0;
    private double lastSinSBPAvg = 0;
    private double lastSinDBPAvg = 0;
    
    // 平均用履歴（10拍）
    private Deque<Double> sinSbpHist = new ArrayDeque<>(10);
    private Deque<Double> sinDbpHist = new ArrayDeque<>(10);
    
    // ISO管理（RealtimeBPと同様）
    private int currentISO = 600;
    
    // BaseLogicへの参照（AI・relTTPの取得用）
    private BaseLogic logicRef;
    
    // フレームレート
    private int frameRate = 30;
    
    // 固定係数（ベースBP推定）
    private static final double ALPHA0 = 80.0;
    private static final double ALPHA1 = 0.5;
    private static final double ALPHA2 = 0.3;
    private static final double BETA0 = 60.0;
    private static final double BETA1 = 0.3;
    private static final double BETA2 = 0.15;
    
    // 血管特性補正係数（新規追加）
    private static final double ALPHA3 = 0.8;   // AI係数
    private static final double ALPHA4 = 0.2;   // relTTP係数
    private static final double ALPHA5 = 0.1;   // stiffness係数
    private static final double BETA3 = 0.4;    // AI係数
    private static final double BETA4 = 0.1;    // relTTP係数
    private static final double BETA5 = 0.05;   // stiffness係数
    
    // 固定係数（詳細補正）
    private static final double C1 = 0.01;
    private static final double D1 = 0.005;
    
    // リスナー
    public interface SinBPListener {
        void onSinBPUpdated(double sinSbp, double sinDbp,
                           double sinSbpAvg, double sinDbpAvg);
    }
    private SinBPListener listener;
}
```

#### 主要メソッド

1. **update(double correctedGreenValue, long timestampMs)**
   - BaseLogicから30fpsで呼ばれる
   - ISOチェック（ISO < 500で処理スキップ）
   - バッファにPPG値を追加
   - ピーク検出を実行

2. **updateISO(int iso)**
   - ISO値を更新
   - RealtimeBPと同様の管理

3. **setLogicRef(BaseLogic logic)**
   - BaseLogicへの参照を設定
   - AI・relTTP値の取得に使用

4. **detectPeak()**
   - 改良版ピーク検出（移動窓の最大値 + 不応期）
   - 最低0.5秒間隔を保証
   - ピーク検出時に`processBeat()`を呼ぶ

5. **processBeat()**
   - 前回ピークから今回ピークまでの1拍を処理
   - IBI計算と異常値チェック
   - `fitSineWave()`を呼ぶ
   - `estimateBP()`を呼ぶ

6. **isValidBeat(double ibi, double amplitude)**
   - IBI範囲チェック（300-1500ms）
   - 振幅範囲チェック（5-200）
   - 前回の拍との変化率チェック（30%以内）

7. **fitSineWave(double[] beatSamples)**
   - 1拍を64点に正規化補間
   - DFT風の内積でA, phiを計算
   ```java
   // s[n] = sin(2πn/N)
   double a = 0, b = 0;
   for (int n = 0; n < N; n++) {
       double angle = 2 * Math.PI * n / N;
       a += x[n] * Math.sin(angle);
       b += x[n] * Math.cos(angle);
   }
   phi = Math.atan2(b, a);
   A = Math.sqrt(a*a + b*b) / N * 2;
   ```

8. **calculateDistortion(double[] beatSamples, double A, double phi, double ibiMs)**
   - 連続時間軸でサイン波再構成
   - 残差r(t) = ppg(t) - s_cont(t)
   - E = mean(r(t)^2)

9. **estimateBP(double A, double ibi, double E)**
   - BaseLogicからAI・relTTP取得（重要！）
   ```java
   double ai = (logicRef != null) ? logicRef.averageAI : 0.0;
   double relTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
   double stiffness = E * Math.sqrt(A);  // 血管硬さ指標
   ```
   - ベースBP計算
   ```java
   double hr = 60000.0 / ibi;
   double sbpBase = ALPHA0 + ALPHA1 * A + ALPHA2 * hr;
   double dbpBase = BETA0 + BETA1 * A + BETA2 * hr;
   ```
   - 血管特性補正
   ```java
   double sbpVascular = sbpBase + ALPHA3 * ai + ALPHA4 * relTTP + ALPHA5 * stiffness;
   double dbpVascular = dbpBase + BETA3 * ai + BETA4 * relTTP + BETA5 * stiffness;
   ```
   - 歪み補正
   ```java
   double deltaSBP = C1 * E;
   double deltaDBP = D1 * E;
   double sbpRefined = sbpVascular + deltaSBP;
   double dbpRefined = dbpVascular + deltaDBP;
   ```
   - 制約適用
   ```java
   if (sbpRefined < dbpRefined + 10) {
       sbpRefined = dbpRefined + 10;
   }
   sbpRefined = clamp(sbpRefined, 60, 200);
   dbpRefined = clamp(dbpRefined, 40, 150);
   ```
   - 生理学的妥当性チェック
   ```java
   if (!isValidBP(sbpRefined, dbpRefined)) return;
   ```

10. **isValidBP(double sbp, double dbp)**
   - 範囲チェック
   - 脈圧チェック（20-100 mmHg）

11. **updateAverage()**
   - 履歴に追加
   - ロバスト平均計算（RealtimeBPと同様）

12. **reset()**
   - 全バッファとフィールドをクリア

### Phase 2: BaseLogicへの統合

**ファイル**: `app/src/main/java/com/nakazawa/realtimeibibp/BaseLogic.java`

#### 変更点

1. **フィールド追加**
```java
private SinBP SinBP;
```

2. **初期化（コンストラクタまたはinitメソッド）**
```java
SinBP = new SinBP();
sinBPDistortion.setLogicRef(this);  // BaseLogicの参照を設定（重要！）
sinBPDistortion.setListener((sinSbp, sinDbp, sinSbpAvg, sinDbpAvg) -> {
    // MainActivityのリスナーに転送
    if (mainActivityListener != null) {
        mainActivityListener.onSinBPUpdated(sinSbp, sinDbp, sinSbpAvg, sinDbpAvg);
    }
});
```

3. **correctedGreenValue更新時に呼び出し**
```java
// processGreenValue()内など、correctedGreenValueを計算した直後
if (SinBP != null) {
    sinBPDistortion.update(correctedGreenValue, System.currentTimeMillis());
}
```

4. **ISO値更新時に連携**
```java
public void updateISO(int iso) {
    this.currentISO = iso;
    boolean shouldEnable = iso >= 500;
    
    if (isDetectionEnabled != shouldEnable) {
        isDetectionEnabled = shouldEnable;
        if (shouldEnable) {
            Log.d("BaseLogic-ISO", "Detection enabled: ISO=" + iso);
        } else {
            Log.d("BaseLogic-ISO", "Detection disabled: ISO=" + iso);
        }
    }
    
    // RealtimeBPとSinBPの両方に通知
    if (realtimeBP != null) {
        realtimeBP.updateISO(iso);
    }
    if (SinBP != null) {
        sinBPDistortion.updateISO(iso);  // 追加
    }
}
```

5. **新しいリスナーインターフェース追加**
```java
public interface MainActivityListener {
    void onBpUpdated(double sbp, double dbp, double sbpAvg, double dbpAvg);
    void onSinBPUpdated(double sinSbp, double sinDbp, 
                         double sinSbpAvg, double sinDbpAvg); // 新規
}
```

### Phase 3: MainActivity UIの更新

**ファイル**: `app/src/main/java/com/nakazawa/realtimeibibp/MainActivity.java`

#### 変更点

1. **TextViewの追加（レイアウトファイルに追加後）**
```java
private TextView sinSbpText;
private TextView sinDbpText;
private TextView sinSbpAvgText;
private TextView sinDbpAvgText;
```

2. **リスナーの実装**
```java
@Override
public void onSinBPUpdated(double sinSbp, double sinDbp, 
                           double sinSbpAvg, double sinDbpAvg) {
    runOnUiThread(() -> {
        sinSbpText.setText(String.format(Locale.US, "%.1f", sinSbp));
        sinDbpText.setText(String.format(Locale.US, "%.1f", sinDbp));
        sinSbpAvgText.setText(String.format(Locale.US, "%.1f", sinSbpAvg));
        sinDbpAvgText.setText(String.format(Locale.US, "%.1f", sinDbpAvg));
    });
}
```

### Phase 4: レイアウトXMLの更新

**ファイル**: `app/src/main/res/layout/activity_main.xml` (または該当レイアウト)

#### 追加要素

既存のBP表示の下に追加：

```xml
<!-- サイン波ベースBP -->
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="SinSBP: "
    android:textSize="16sp" />

<TextView
    android:id="@+id/sin_sbp_text"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="0.0"
    android:textSize="16sp"
    android:textStyle="bold" />

<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="SinDBP: "
    android:textSize="16sp" />

<TextView
    android:id="@+id/sin_dbp_text"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="0.0"
    android:textSize="16sp"
    android:textStyle="bold" />

<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="SinSBP(Avg): "
    android:textSize="16sp" />

<TextView
    android:id="@+id/sin_sbp_avg_text"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="0.0"
    android:textSize="16sp"
    android:textStyle="bold" />

<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="SinDBP(Avg): "
    android:textSize="16sp" />

<TextView
    android:id="@+id/sin_dbp_avg_text"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="0.0"
    android:textSize="16sp"
    android:textStyle="bold" />
```

## 非同期・ノンブロッキング設計

### 1. スレッドセーフティ
- `sinBPDistortion.update()`はBaseLogicのスレッドから呼ばれる
- リスナーコールバックは同じスレッドで実行
- UI更新は`runOnUiThread()`で行う

### 2. パフォーマンス考慮
- バッファサイズを制限（90サンプル = 3秒分）
- ピーク検出は軽量な移動窓アルゴリズム
- サイン波フィットは64点のみ（高速）
- 既存の`RealtimeBP`と独立して動作

### 3. 処理時間見積もり
- ピーク検出: O(1) - 毎フレーム
- サイン波フィット: O(64) - 拍ごと（約1秒に1回）
- BP推定: O(1) - 拍ごと
- 合計: 30fpsで問題なし

## テスト・デバッグ

### 1. ログ出力
```java
Log.d("SinBP", String.format(
    "Beat detected: IBI=%.1fms, A=%.2f, phi=%.2f, E=%.4f",
    currentIBI, currentA, currentPhi, currentE));

Log.d("SinBP", String.format(
    "BP: SinSBP=%.1f, SinDBP=%.1f (Avg: %.1f/%.1f)",
    lastSinSBP, lastSinDBP, lastSinSBPAvg, lastSinDBPAvg));
```

### 2. 検証項目
- [ ] correctedGreenValueが正しく渡される
- [ ] ピーク検出が適切に動作（約60-100 bpm）
- [ ] IBI値が妥当（600-1000ms程度）
- [ ] 振幅Aが正の値
- [ ] BP値が生理的範囲内（60-200 / 40-150）
- [ ] 既存のRealtimeBPと独立して動作
- [ ] UIが正しく更新される

## 係数チューニング

### 初期値（仮設定）
```java
// ベースBP推定
ALPHA0 = 80.0;  // SBP切片
ALPHA1 = 0.5;   // SBP振幅係数
ALPHA2 = 0.3;   // SBP心拍数係数
BETA0 = 60.0;   // DBP切片
BETA1 = 0.3;    // DBP振幅係数
BETA2 = 0.15;   // DBP心拍数係数

// 血管特性補正（新規）
ALPHA3 = 0.8;   // SBP AI係数
ALPHA4 = 0.2;   // SBP relTTP係数
ALPHA5 = 0.1;   // SBP stiffness係数
BETA3 = 0.4;    // DBP AI係数
BETA4 = 0.1;    // DBP relTTP係数
BETA5 = 0.05;   // DBP stiffness係数

// 詳細補正
C1 = 0.01;      // SBP歪み補正
D1 = 0.005;     // DBP歪み補正
```

### チューニング方針
1. 実データで既存BPと比較
2. 差が大きい場合は係数を調整
3. まずベース推定を合わせる（ALPHA0-2, BETA0-2）
4. 次に血管特性補正を調整（ALPHA3-5, BETA3-5）
5. 最後に歪み補正を調整（C1, D1）

## 実装順序

1. **sinBPDistortion.javaの基本構造**
   - クラス定義、フィールド、コンストラクタ
   
2. **バッファリングとピーク検出**
   - update(), detectPeak()
   
3. **サイン波フィッティング**
   - processBeat(), fitSineWave(), calculateDistortion()
   
4. **BP推定**
   - estimateBP(), updateAverage()
   
5. **BaseLogic統合**
   - SinBPインスタンス作成、update()呼び出し
   
6. **UI更新**
   - レイアウトXML追加、MainActivity修正

7. **テスト・デバッグ**
   - ログ確認、値の妥当性チェック

8. **係数チューニング**
   - 実データで調整

## ファイル一覧

### 新規作成
- `app/src/main/java/com/nakazawa/realtimeibibp/sinBPDistortion.java`

### 修正
- `app/src/main/java/com/nakazawa/realtimeibibp/BaseLogic.java`
- `app/src/main/java/com/nakazawa/realtimeibibp/MainActivity.java`
- `app/src/main/res/layout/activity_main.xml` (または該当レイアウト)

### 参照（変更なし）
- `app/src/main/java/com/nakazawa/realtimeibibp/RealtimeBP.java` (設計参考)

## 注意事項

1. **既存機能への影響なし**
   - RealtimeBPは一切変更しない
   - BaseLogicの既存ロジックは維持

2. **エラーハンドリング**
   - バッファが空の場合の処理
   - IBI=0の場合の除算エラー防止
   - 異常値のクリッピング

3. **メモリ管理**
   - バッファサイズの上限設定
   - 古いデータの自動削除

4. **将来の拡張性**
   - H2/H1等の高調波指標追加可能
   - 係数の動的調整機能追加可能
   - 複数拍の統計処理追加可能

