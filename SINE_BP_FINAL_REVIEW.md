# SinBP実装 最終チェックと改善提案

## ✅ 実装計画の妥当性評価: **合格**

基本設計は良好です。以下の追加改善点を組み込むことで、より堅牢な実装になります。

---

## 🔧 **追加すべき重要な改善点**

### 1. **ISO感度連携の統一**
**問題**: 計画書にISO管理が明記されていない

**改善策**:
```java
public class SinBP {
    // ISO管理（BaseLogicと同様）
    private int currentISO = 600;
    
    /** ISO値を更新するメソッド */
    public void updateISO(int iso) {
        this.currentISO = iso;
    }
    
    /** 検出が有効かチェック */
    private boolean isDetectionValid() {
        return currentISO >= 500;
    }
    
    public void update(double correctedGreenValue, long timestampMs) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("SinBP-ISO", "Estimation skipped: ISO=" + currentISO);
            return;
        }
        // ... 処理続行
    }
}
```

**統合**:
```java
// BaseLogic内
public void updateISO(int iso) {
    this.currentISO = iso;
    if (realtimeBP != null) {
        realtimeBP.updateISO(iso);
    }
    if (sinBP != null) {
        sinBP.updateISO(iso);  // 追加
    }
}
```

---

### 2. **BaseLogicからのAI・relTTP値の再利用**
**問題**: SinBPで独自にAI計算するが、BaseLogicが既に計算している

**改善策**:
```java
// SinBPクラス
private BaseLogic logicRef;

public void setLogicRef(BaseLogic logic) {
    this.logicRef = logic;
}

private double getAIFromBaseLogic() {
    if (logicRef != null) {
        return logicRef.averageAI;  // 既存の値を再利用
    }
    return 0.0;
}

private double getRelTTPFromBaseLogic() {
    if (logicRef != null) {
        // 谷→山のrelTTPを使用（血管硬さと相関が高い）
        return logicRef.averageValleyToPeakRelTTP;
    }
    return 0.0;
}
```

**メリット**:
- 計算の重複を避ける
- BaseLogicの高精度な形態学的解析を活用
- RealtimeBPと同じデータソースを使用して一貫性を保つ

---

### 3. **フレームレートの動的取得**
**問題**: フレームレート30fpsをハードコーディング

**改善策**:
```java
public class SinBP {
    private int frameRate = 30;  // デフォルト
    
    /** フレームレートを更新するメソッド */
    public void setFrameRate(int fps) {
        this.frameRate = fps;
        Log.d("SinBP", "frameRate updated: " + fps);
    }
}
```

---

### 4. **ピーク検出の改良**
**問題**: 単純なピーク検出では誤検出が多い可能性

**改善策**:
```java
private boolean detectPeak(double currentValue) {
    // 不応期チェック（最後のピークから最低0.5秒）
    long currentTime = System.currentTimeMillis();
    if (currentTime - lastPeakTime < 500) {
        return false;
    }
    
    // 移動窓での最大値チェック（前後3フレーム）
    if (ppgBuffer.size() < 7) {
        return false;
    }
    
    // 中央値が両側より大きい場合のみピークとする
    Double[] recent = ppgBuffer.toArray(new Double[0]);
    int idx = recent.length - 4;  // 中央
    if (idx < 3 || idx >= recent.length - 3) {
        return false;
    }
    
    boolean isPeak = true;
    for (int i = idx - 3; i <= idx + 3; i++) {
        if (i != idx && recent[i] >= recent[idx]) {
            isPeak = false;
            break;
        }
    }
    
    return isPeak;
}
```

---

### 5. **サイン波フィットの数値安定性**
**問題**: ゼロ除算や異常値の可能性

**改善策**:
```java
private void fitSineWave(double[] x) {
    int N = x.length;
    double a = 0, b = 0;
    
    // 内積計算
    for (int n = 0; n < N; n++) {
        double angle = 2 * Math.PI * n / N;
        a += x[n] * Math.sin(angle);
        b += x[n] * Math.cos(angle);
    }
    
    // 正規化
    a = a * 2.0 / N;
    b = b * 2.0 / N;
    
    // 振幅計算（ゼロ除算チェック）
    currentA = Math.sqrt(a * a + b * b);
    if (currentA < 1e-6) {
        Log.w("SinBP", "Amplitude too small, skipping this beat");
        return;
    }
    
    // 位相計算
    currentPhi = Math.atan2(b, a);
    
    // 位相を[0, 2π]に正規化
    if (currentPhi < 0) {
        currentPhi += 2 * Math.PI;
    }
    
    Log.d("SinBP-Fit", String.format(
        "Fitted: A=%.3f, phi=%.3f rad (%.1f deg)",
        currentA, currentPhi, Math.toDegrees(currentPhi)));
}
```

---

### 6. **異常値検出とフィルタリング**
**問題**: 異常な拍や外れ値の処理がない

**改善策**:
```java
private boolean isValidBeat(double ibi, double amplitude) {
    // IBI範囲チェック（40-200 bpm相当）
    if (ibi < 300 || ibi > 1500) {
        Log.w("SinBP", "Invalid IBI: " + ibi + " ms");
        return false;
    }
    
    // 振幅範囲チェック
    if (amplitude < 5 || amplitude > 200) {
        Log.w("SinBP", "Invalid amplitude: " + amplitude);
        return false;
    }
    
    // 前回の拍と比較（急激な変化を除外）
    if (lastValidIBI > 0) {
        double ibiChange = Math.abs(ibi - lastValidIBI) / lastValidIBI;
        if (ibiChange > 0.3) {  // 30%以上の変化は異常
            Log.w("SinBP", "IBI changed too rapidly: " + (ibiChange * 100) + "%");
            return false;
        }
    }
    
    return true;
}

private double lastValidIBI = 0;
```

---

### 7. **血管特性の計算最適化**
**問題**: サイン波からAIを計算するより、BaseLogicの値を使う方が精度が高い

**改善策（推奨）**:
```java
private void estimateBP(double A, double ibi, double E) {
    double hr = 60000.0 / ibi;
    
    // BaseLogicから血管特性を取得（既存の高精度な値）
    double ai = (logicRef != null) ? logicRef.averageAI : 0.0;
    double relTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
    
    // 血管硬さ指標（サイン波の歪みから計算）
    double stiffness = E * Math.sqrt(A);
    
    // ベースBP
    double sbpBase = ALPHA0 + ALPHA1 * A + ALPHA2 * hr;
    double dbpBase = BETA0 + BETA1 * A + BETA2 * hr;
    
    // 血管特性補正
    double sbpVascular = sbpBase + ALPHA3 * ai + ALPHA4 * relTTP + ALPHA5 * stiffness;
    double dbpVascular = dbpBase + BETA3 * ai + BETA4 * relTTP + BETA5 * stiffness;
    
    // 歪み補正
    double sbpRefined = sbpVascular + C1 * E;
    double dbpRefined = dbpVascular + D1 * E;
    
    // 制約適用
    if (sbpRefined < dbpRefined + 10) {
        sbpRefined = dbpRefined + 10;
    }
    
    sbpRefined = clamp(sbpRefined, 60, 200);
    dbpRefined = clamp(dbpRefined, 40, 150);
    
    // 異常値チェック
    if (!isValidBP(sbpRefined, dbpRefined)) {
        Log.w("SinBP", "Invalid BP values, skipping");
        return;
    }
    
    // 履歴に追加
    updateHistory(sbpRefined, dbpRefined);
}

private boolean isValidBP(double sbp, double dbp) {
    // 生理学的妥当性チェック
    if (sbp < 60 || sbp > 200) return false;
    if (dbp < 40 || dbp > 150) return false;
    if (sbp <= dbp) return false;
    
    // 脈圧チェック（20-100 mmHg）
    double pp = sbp - dbp;
    if (pp < 20 || pp > 100) return false;
    
    return true;
}
```

---

### 8. **リセット機能の追加**
**問題**: 計画書にリセット機能がない

**改善策**:
```java
public void reset() {
    ppgBuffer.clear();
    timeBuffer.clear();
    sinSbpHist.clear();
    sinDbpHist.clear();
    
    lastPeakValue = 0;
    lastPeakTime = 0;
    currentA = 0;
    currentIBI = 0;
    currentPhi = 0;
    currentE = 0;
    lastSinSBP = 0;
    lastSinDBP = 0;
    lastSinSBPAvg = 0;
    lastSinDBPAvg = 0;
    lastValidIBI = 0;
    
    Log.d("SinBP", "SinBP reset");
}
```

---

### 9. **デバッグモードの追加**
**改善策**:
```java
public class SinBP {
    private boolean debugMode = false;
    
    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
    }
    
    private void debugLog(String tag, String message) {
        if (debugMode) {
            Log.d("SinBP-" + tag, message);
        }
    }
}
```

---

### 10. **補間方法の改良**
**問題**: 線形補間のみ

**改善策（オプション）**:
```java
private double[] resampleBeat(double[] beatSamples, int targetSize) {
    double[] resampled = new double[targetSize];
    int srcSize = beatSamples.length;
    
    for (int i = 0; i < targetSize; i++) {
        // 線形補間の改良版（エッジケース対応）
        double srcIdx = (double) i * (srcSize - 1) / (targetSize - 1);
        int idx0 = (int) Math.floor(srcIdx);
        int idx1 = Math.min(idx0 + 1, srcSize - 1);
        double t = srcIdx - idx0;
        
        resampled[i] = beatSamples[idx0] * (1 - t) + beatSamples[idx1] * t;
    }
    
    return resampled;
}
```

---

## 📊 **実装優先度**

### **必須（実装前に追加）**:
1. ✅ ISO管理の統一
2. ✅ BaseLogicからのAI・relTTP値の再利用
3. ✅ 異常値検出とフィルタリング
4. ✅ リセット機能

### **推奨（初期実装に含める）**:
5. ✅ ピーク検出の改良
6. ✅ サイン波フィットの数値安定性
7. ✅ フレームレート動的取得

### **オプション（後で追加可能）**:
8. ⭕ デバッグモード
9. ⭕ 補間方法の改良

---

## 🎯 **最終判定**

### **実装の妥当性**: ✅ **合格（改善点を組み込むこと）**

**理由**:
1. ✅ 基本設計は堅牢
2. ✅ RealtimeBPとの独立性が保たれている
3. ✅ 血管特性の統合により高精度が期待できる
4. ⚠️ 上記の改善点を組み込むことでさらに堅牢に

---

## 📋 **修正版実装チェックリスト**

### Phase 1: SinBP.java基本実装
- [ ] クラス定義とフィールド
- [ ] **ISO管理の追加**
- [ ] **BaseLogicへの参照（logicRef）**
- [ ] バッファリング（ppgBuffer, timeBuffer）
- [ ] リスナーインターフェース

### Phase 2: ピーク検出
- [ ] **改良版ピーク検出（不応期付き）**
- [ ] **異常値フィルタリング**
- [ ] processBeat()メソッド

### Phase 3: サイン波フィット
- [ ] 64点リサンプリング
- [ ] **数値安定性を考慮したフィット**
- [ ] 振幅・位相計算

### Phase 4: BP推定
- [ ] **BaseLogicからAI・relTTP取得**
- [ ] ベースBP計算
- [ ] 血管特性補正
- [ ] 歪み補正
- [ ] **生理学的妥当性チェック**

### Phase 5: BaseLogic統合
- [ ] SinBPインスタンス作成
- [ ] **logicRef設定**
- [ ] **ISO値の連携**
- [ ] update()呼び出し

### Phase 6: UI統合
- [ ] MainActivity修正
- [ ] レイアウトXML追加
- [ ] リスナー実装

### Phase 7: テスト
- [ ] ログ確認
- [ ] 値の妥当性
- [ ] **異常値処理の確認**
- [ ] RealtimeBPとの比較

---

## 🚀 **実装を開始する前の最終確認**

### **質問1**: BaseLogicの値を再利用する方針で良いですか？
- **推奨**: YES（計算効率と精度の両面で有利）

### **質問2**: デバッグモードは必要ですか？
- **推奨**: YES（開発中のログ制御に便利）

### **質問3**: 異常値フィルタリングの厳しさは？
- **推奨**: 中程度（IBI変化30%まで許容）

---

## ✅ **最終結論**

**この実装計画は、上記の改善点を組み込めば実装に進んで問題ありません。**

特に重要な追加事項:
1. **ISO管理の統一** - 必須
2. **BaseLogicからの値再利用** - 精度向上に重要
3. **異常値フィルタリング** - 安定性に必須

これらを組み込んだ実装を進めることを推奨します。

