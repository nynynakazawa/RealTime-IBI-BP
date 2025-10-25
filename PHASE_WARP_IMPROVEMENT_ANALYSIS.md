# 位相ワープを活かした拍ごとがたつき軽減方法の分析

## 現在の位相ワープ実装の確認

現在の実装では以下の位相ワープ処理が行われています：

```java
// ピーク検出時に理想曲線の位相を再同期
// シンプルに：lastPeakTime を理想曲線のピーク位置に合わせる
// 理想曲線のピーク位置を探索して、その時刻をlastPeakTimeに対応させる
double maxValue = -1.0;
double peakPhaseTime = 0;
int searchSteps = 100;
for (int i = 0; i < searchSteps; i++) {
    double t = (double) i * T / searchSteps;
    double val = asymmetricSineBasis(t, T);
    if (val > maxValue) {
        maxValue = val;
        peakPhaseTime = t;
    }
}
// 僅かに遅れているため、位相を前進（ピーク位置を5%早める）
peakPhaseTime *= 1.8;

// lastPeakTime = idealCurveStartTime + peakPhaseTime
// → idealCurveStartTime = lastPeakTime - peakPhaseTime
idealCurveStartTime = lastPeakTime - (long)peakPhaseTime;
```

## 方法1: 段階的位相調整（Gradual Phase Adjustment）

### コンセプト
位相ワープを一度に適用するのではなく、複数フレームにわたって段階的に調整

### 実装方法
```java
public class GradualPhaseAdjustment {
    private double targetPhaseShift = 0.0;
    private double currentPhaseShift = 0.0;
    private double adjustmentSpeed = 0.1; // 調整速度
    
    public double getAdjustedPhaseShift() {
        // 段階的に目標位相シフトに近づける
        double difference = targetPhaseShift - currentPhaseShift;
        currentPhaseShift += difference * adjustmentSpeed;
        return currentPhaseShift;
    }
    
    public void updateTargetPhaseShift(double newTarget) {
        this.targetPhaseShift = newTarget;
    }
}
```

### 利点
- 急激な位相変化を防止
- 滑らかな位相遷移
- 既存の位相ワープロジックを保持

### 欠点
- リアルタイム性に若干の遅延
- 調整速度の最適化が必要

## 方法2: 位相フィルタリング（Phase Filtering）

### コンセプト
位相ワープの結果をフィルタリングして、急激な変化を平滑化

### 実装方法
```java
public class PhaseFilter {
    private final Deque<Double> phaseHistory = new ArrayDeque<>(5);
    private double smoothedPhase = 0.0;
    
    public double filterPhase(double newPhase) {
        phaseHistory.addLast(newPhase);
        if (phaseHistory.size() > 5) {
            phaseHistory.pollFirst();
        }
        
        // 移動平均フィルタ
        double sum = 0.0;
        for (double phase : phaseHistory) {
            sum += phase;
        }
        smoothedPhase = sum / phaseHistory.size();
        
        return smoothedPhase;
    }
}
```

### 利点
- 既存の位相ワープロジックをそのまま使用
- シンプルな実装
- リアルタイム性を保持

### 欠点
- 位相変化の応答性が若干低下
- フィルタサイズの調整が必要

## 方法3: 位相予測補正（Phase Prediction Correction）

### コンセプト
前回の位相変化パターンを学習して、次の位相変化を予測・補正

### 実装方法
```java
public class PhasePredictionCorrection {
    private final Deque<Double> phaseHistory = new ArrayDeque<>(10);
    private double predictedPhase = 0.0;
    
    public double getCorrectedPhase(double currentPhase) {
        // 位相変化の傾向を分析
        if (phaseHistory.size() >= 3) {
            double trend = calculatePhaseTrend();
            predictedPhase = currentPhase + trend * 0.5; // 予測補正
        } else {
            predictedPhase = currentPhase;
        }
        
        phaseHistory.addLast(currentPhase);
        if (phaseHistory.size() > 10) {
            phaseHistory.pollFirst();
        }
        
        return predictedPhase;
    }
    
    private double calculatePhaseTrend() {
        // 最近の位相変化の傾向を計算
        Double[] phases = phaseHistory.toArray(new Double[0]);
        double sum = 0.0;
        for (int i = 1; i < phases.length; i++) {
            sum += phases[i] - phases[i-1];
        }
        return sum / (phases.length - 1);
    }
}
```

### 利点
- 位相変化の予測により滑らかな遷移
- 学習機能により適応性向上
- 既存ロジックとの統合が容易

### 欠点
- 実装が複雑
- 学習期間が必要

## 方法4: 位相重み付け調整（Phase Weighted Adjustment）

### コンセプト
位相ワープの強度を動的に調整し、急激な変化を抑制

### 実装方法
```java
public class PhaseWeightedAdjustment {
    private double lastPhaseShift = 0.0;
    private double maxPhaseChange = Math.PI / 4; // 最大位相変化量
    
    public double getWeightedPhaseShift(double rawPhaseShift) {
        // 前回の位相シフトとの差分を計算
        double phaseDifference = rawPhaseShift - lastPhaseShift;
        
        // 急激な変化を制限
        if (Math.abs(phaseDifference) > maxPhaseChange) {
            double sign = Math.signum(phaseDifference);
            phaseDifference = sign * maxPhaseChange;
        }
        
        // 重み付け調整
        double adjustedPhaseShift = lastPhaseShift + phaseDifference * 0.7; // 70%の重み
        lastPhaseShift = adjustedPhaseShift;
        
        return adjustedPhaseShift;
    }
}
```

### 利点
- 位相ワープの強度を制御可能
- 急激な変化を確実に抑制
- パラメータ調整が容易

### 欠点
- 位相調整の応答性が低下
- 重み係数の最適化が必要

## 方法5: 位相補間スムージング（Phase Interpolation Smoothing）

### コンセプト
位相ワープの結果を補間関数で滑らかにする

### 実装方法
```java
public class PhaseInterpolationSmoothing {
    private final Deque<Double> phaseBuffer = new ArrayDeque<>(3);
    private double smoothedPhase = 0.0;
    
    public double getSmoothedPhase(double newPhase) {
        phaseBuffer.addLast(newPhase);
        if (phaseBuffer.size() > 3) {
            phaseBuffer.pollFirst();
        }
        
        if (phaseBuffer.size() == 3) {
            // 3点補間による滑らかな位相計算
            Double[] phases = phaseBuffer.toArray(new Double[0]);
            smoothedPhase = interpolatePhase(phases[0], phases[1], phases[2]);
        } else {
            smoothedPhase = newPhase;
        }
        
        return smoothedPhase;
    }
    
    private double interpolatePhase(double p1, double p2, double p3) {
        // 3点を通る滑らかな曲線を計算
        // 中央点に重みを置いた補間
        return 0.25 * p1 + 0.5 * p2 + 0.25 * p3;
    }
}
```

### 利点
- 数学的に滑らかな補間
- 位相の連続性を保証
- 実装が比較的シンプル

### 欠点
- 若干の遅延が発生
- 補間アルゴリズムの選択が重要

## 方法6: ハイブリッド位相調整（Hybrid Phase Adjustment）

### コンセプト
複数の方法を組み合わせて最適な位相調整を実現

### 実装方法
```java
public class HybridPhaseAdjustment {
    private final PhaseFilter phaseFilter = new PhaseFilter();
    private final PhaseWeightedAdjustment weightedAdjustment = new PhaseWeightedAdjustment();
    private final PhaseInterpolationSmoothing interpolationSmoothing = new PhaseInterpolationSmoothing();
    
    public double getHybridAdjustedPhase(double rawPhase) {
        // 1. 重み付け調整
        double weightedPhase = weightedAdjustment.getWeightedPhaseShift(rawPhase);
        
        // 2. フィルタリング
        double filteredPhase = phaseFilter.filterPhase(weightedPhase);
        
        // 3. 補間スムージング
        double smoothedPhase = interpolationSmoothing.getSmoothedPhase(filteredPhase);
        
        return smoothedPhase;
    }
}
```

### 利点
- 複数の手法の利点を組み合わせ
- 高い安定性と滑らかさ
- パラメータ調整の柔軟性

### 欠点
- 実装が複雑
- 計算コストが高い
- パラメータ調整が困難

## 推奨実装順序

### Phase 1: シンプルな改善
1. **位相フィルタリング** - 最もシンプルで効果的
2. **位相重み付け調整** - 急激な変化を抑制

### Phase 2: 高度な改善
3. **段階的位相調整** - より滑らかな遷移
4. **位相補間スムージング** - 数学的な滑らかさ

### Phase 3: 最適化
5. **位相予測補正** - 学習機能による適応
6. **ハイブリッド位相調整** - 統合的な解決策

## 結論

位相ワープの現在の方法を活かしつつ拍ごとのがたつきを減らすには、**位相フィルタリング**と**位相重み付け調整**の組み合わせが最も実用的です。これらは既存の実装を最小限の変更で改善でき、リアルタイム性を保ちながら滑らかな位相遷移を実現できます。
