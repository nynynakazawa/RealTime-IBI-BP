package com.example.realtimehribicontrol;

import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

public abstract class BaseLogic implements LogicProcessor {
    // 定数
    protected static final int GREEN_VALUE_WINDOW_SIZE = 20;
    protected static final int CORRECTED_GREEN_VALUE_WINDOW_SIZE = 20;
    protected static final int WINDOW_SIZE = 240;
    protected static final int BPM_HISTORY_SIZE = 20;
    protected static final int REFRACTORY_FRAMES = 8;

    // メンバ変数
    protected ArrayList<Double> greenValues = new ArrayList<>();
    protected ArrayList<Double> filteredValues = new ArrayList<>();
    protected ArrayList<Double> recentGreenValues = new ArrayList<>();
    protected ArrayList<Double> recentCorrectedGreenValues = new ArrayList<>();
    protected ArrayList<Double> smoothedCorrectedGreenValues = new ArrayList<>();
    protected ArrayList<Double> smoothedIbi = new ArrayList<>();
    protected ArrayList<Double> smoothedBpmSd = new ArrayList<>();
    protected double[] window = new double[WINDOW_SIZE];
    protected int windowIndex = 0;
    protected ArrayList<Double> bpmHistory = new ArrayList<>();
    protected long lastPeakTime = 0;
    protected int updateCount = 0;
    protected double lastIbiValue = -1;
    protected int framesSinceLastPeak = REFRACTORY_FRAMES;
    protected double bpmValue = 0.0;
    protected double IBI = 0.0;

    // ----- UI更新用コールバック -----
    public interface UIUpdateCallback {
        void updateValueText(String text);
        void updateChartValue(float value);
        void updateSmoothedValuesText(String smoothedIbi, String smoothedBpm);
    }
    protected UIUpdateCallback uiCallback;
    public void setUIUpdateCallback(UIUpdateCallback callback) {
        this.uiCallback = callback;
    }

    // ----- BP推定用コールバック -----
    public interface BPFrameCallback {
        void onFrame(double correctedGreenValue, double smoothedIbiMs);
    }
    protected BPFrameCallback bpCallback;
    public void setBPFrameCallback(BPFrameCallback cb){ this.bpCallback = cb; }

    // 共通: リアルタイム平滑化補間
    @Override
    public void calculateSmoothedValueRealTime(double newIbi, double newBpmSd) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "Smoothed values update skipped: ISO=" + currentISO);
            return;
        }

        if (newIbi != lastIbiValue) {
            double smoothedIbiValue;
            if (!smoothedIbi.isEmpty()) {
                double lastSmoothedIbi = smoothedIbi.get(smoothedIbi.size() - 1);
                smoothedIbiValue = (lastSmoothedIbi + newIbi) / 2.0;
            } else {
                smoothedIbiValue = newIbi;
            }
            double smoothedBpmValue = (60 * 1000) / smoothedIbiValue;
            smoothedIbi.add(smoothedIbiValue);
            smoothedBpmSd.add(newBpmSd);
            updateSmoothedValues(smoothedIbiValue, smoothedBpmValue);
            lastIbiValue = newIbi;
        }
    }

    // 共通: 平均・標準偏差
    protected double getMean(ArrayList<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }
    protected double getStdDev(ArrayList<Double> values) {
        double mean = getMean(values);
        double squareSum = 0.0;
        for (double value : values) {
            squareSum += Math.pow(value - mean, 2);
        }
        return Math.sqrt(squareSum / values.size());
    }

    // 共通: UI更新
    protected void updateValueText(double value) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "Value text update skipped: ISO=" + currentISO);
            return;
        }

        if(uiCallback != null) {
            uiCallback.updateValueText("Value : " + String.format(Locale.getDefault(), "%.2f", value));
        }
    }
    protected void updateChart(double value) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "Chart update skipped: ISO=" + currentISO);
            return;
        }

        if(uiCallback != null) {
            uiCallback.updateChartValue((float) value);
        }
    }
    protected void updateSmoothedValues(double smoothedIbi, double smoothedBpm) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "UI update skipped: ISO=" + currentISO);
            return;
        }

        if(uiCallback != null) {
            uiCallback.updateSmoothedValuesText("IBI(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedIbi),
                    "HR(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedBpm));
        }
    }

    @Override
    public double getLastSmoothedIbi() {
        if (smoothedIbi.isEmpty()) {
            return 0.0;
        }
        return smoothedIbi.get(smoothedIbi.size() - 1);
    }
    
    /**
     * 最新の平滑化された心拍数を取得
     */
    public double getLastSmoothedBpm() {
        return smoothedBpm;
    }

    public void reset() {
        greenValues.clear();
        filteredValues.clear();
        recentGreenValues.clear();
        recentCorrectedGreenValues.clear();
        smoothedCorrectedGreenValues.clear();
        smoothedIbi.clear();
        smoothedBpmSd.clear();
        smoothedBpm = 0.0;
        lastValidBpm = 0.0;
        lastValidSd = 0.0;
        window = new double[WINDOW_SIZE];
        windowIndex = 0;
        bpmValue = 0.0;
        IBI = 0.0;
        lastPeakTime = 0;
        updateCount = 0;
        bpmHistory.clear();
    }

    // 谷・山イベント記録用クラス
    protected static class PeakValleyEvent {
        public final long timestamp;
        public final double value;
        public final int index;
        public PeakValleyEvent(long timestamp, double value, int index) {
            this.timestamp = timestamp;
            this.value = value;
            this.index = index;
        }
    }
    // 直近の谷・山イベントを記録


    // 独立検出用の履歴（直近5回）
    protected final java.util.ArrayList<PeakValleyEvent> valleyToPeakHistory = new java.util.ArrayList<>();
    protected final java.util.ArrayList<PeakValleyEvent> peakToValleyHistory = new java.util.ArrayList<>();

    // 平均値（非同期計算結果）
    protected volatile double averageValleyToPeakRelTTP = 0.0;
    protected volatile double averagePeakToValleyRelTTP = 0.0;
    protected volatile double averageValleyToPeakAmplitude = 0.0;
    protected volatile double averagePeakToValleyAmplitude = 0.0;
    protected volatile double averageAI = 0.0;
    protected volatile long lastUpdateTime = 0;

    // 検出重複防止用
    protected long lastV2PDetectionTime = 0;
    protected long lastP2VDetectionTime = 0;
    protected long lastV2PDetectionId = 0;
    protected long lastP2VDetectionId = 0;
    protected boolean needsAverageUpdate = false;

    // ISO管理
    protected int currentISO = 600; // デフォルト値
    protected boolean isDetectionEnabled = true; // 検出有効フラグ
    
    // 直前の有効な値を保持（ISO < 500の時に使用）
    protected double lastValidBpm = 0.0;
    protected double lastValidSd = 0.0;
    
    // 平滑化された心拍数
    protected double smoothedBpm = 0.0;

    /**
     * ISO値を更新し、検出の有効/無効を制御
     */
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
    }

    /**
     * 検出が有効かチェック
     */
    protected boolean isDetectionValid() {
        return isDetectionEnabled && currentISO >= 500;
    }

    /**
     * 非同期で谷→山と山→谷を独立して検出し、条件チェックを行う
     */
    protected void detectPeakAndValleyAsync(double ibiMs) {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "Async detection skipped: ISO=" + currentISO);
            return;
        }

        long currentTime = System.currentTimeMillis();
        boolean hasNewDetection = false;

        // デバッグログを追加（検出時のみ）
        Log.d("BaseLogic-Async", String.format("=== detectPeakAndValleyAsync START: ibiMs=%.3f ===", ibiMs));

        // // 谷→山パターンの独立検出
        PeakValleyEvent valleyForV2P = detectValleyForValleyToPeak(ibiMs);
        PeakValleyEvent peakForV2P = detectPeakForValleyToPeak(ibiMs);
        
        if (valleyForV2P != null && peakForV2P != null) {
            Log.d("BaseLogic-Async", String.format("V2P candidates found: valley(%.3f, idx=%d) -> peak(%.3f, idx=%d)", 
                valleyForV2P.value, valleyForV2P.index, peakForV2P.value, peakForV2P.index));
        } else {
            Log.d("BaseLogic-Async", String.format("V2P detection failed: valley=%s, peak=%s", 
                valleyForV2P != null ? String.format("%.3f", valleyForV2P.value) : "null",
                peakForV2P != null ? String.format("%.3f", peakForV2P.value) : "null"));
        }
        
        if (valleyForV2P != null && peakForV2P != null) {
            long timeDiff = Math.abs(peakForV2P.timestamp - valleyForV2P.timestamp);
            double minDt = ibiMs * 0.125; // IBIの1/8
            
            // 谷→山の条件チェック（条件を緩和）
            boolean isValidAmplitude = Math.abs(peakForV2P.value - valleyForV2P.value) > 0.1; // 0.3 → 0.1
            boolean isValidTiming = timeDiff >= 10 && timeDiff <= ibiMs * 5; // 15 → 10, 4 → 5
            int indexDiff = Math.abs(valleyForV2P.index - peakForV2P.index);
            boolean isValidIndexDiff = indexDiff <= 150; // 120 → 150
            boolean isValidPattern = peakForV2P.value > valleyForV2P.value - 1.0; // -0.5 → -1.0
            boolean isValidHrTiming = timeDiff >= minDt;
            
            // 独立性チェック：V2Pの谷と山が異なるインデックスを持つ
            boolean isIndependent = valleyForV2P.index != peakForV2P.index;
            
            // 追加の独立性チェック：位置の差が十分にある
            int positionDiff = Math.abs(valleyForV2P.index - peakForV2P.index);
            boolean hasSufficientPositionDiff = positionDiff >= 3; // 最低3フレームの差
            
            boolean isValid = isValidAmplitude && isValidTiming && isValidIndexDiff && isValidPattern && isValidHrTiming && isIndependent && hasSufficientPositionDiff;
            
            // 重複検出防止: 前回の検出から一定時間経過しているかチェック
            boolean isNewDetection = (currentTime - lastV2PDetectionTime) > 100; // 100ms以上経過
            
            if (isValid && isNewDetection) {
                // 重複チェック：同じamplitudeかつindexDiffが連続した場合は2回目以降を弾く
                boolean isDuplicate = false;
                if (valleyToPeakHistory.size() >= 2) {
                    PeakValleyEvent lastValley = valleyToPeakHistory.get(valleyToPeakHistory.size() - 2);
                    PeakValleyEvent lastPeak = valleyToPeakHistory.get(valleyToPeakHistory.size() - 1);
                    double lastAmplitude = Math.abs(lastPeak.value - lastValley.value);
                    int lastIndexDiff = Math.abs(lastValley.index - lastPeak.index);
                    double currentAmplitude = Math.abs(peakForV2P.value - valleyForV2P.value);
                    int currentIndexDiff = Math.abs(valleyForV2P.index - peakForV2P.index);
                    
                    // 振幅とインデックス差が同じ場合は重複とみなす
                    if (Math.abs(lastAmplitude - currentAmplitude) < 0.01 && lastIndexDiff == currentIndexDiff) {
                        isDuplicate = true;
                        Log.d("BaseLogic-Async", "V2P duplicate detected and skipped");
                    }
                }
                
                if (!isDuplicate) {
                    Log.d("BaseLogic-Async", String.format("V2P validation: amplitude=%s(%.3f), timing=%s(%dms), indexDiff=%s(%d), pattern=%s, hrTiming=%s(%dms), independent=%s, posDiff=%s(%d), valid=%s",
                        isValidAmplitude, Math.abs(peakForV2P.value - valleyForV2P.value),
                        isValidTiming, timeDiff, isValidIndexDiff, indexDiff, isValidPattern, isValidHrTiming, timeDiff, isIndependent, hasSufficientPositionDiff, positionDiff, isValid));
                    Log.d("BaseLogic-Async", String.format("V2P details: valley(%.3f, idx=%d) -> peak(%.3f, idx=%d)", 
                        valleyForV2P.value, valleyForV2P.index, peakForV2P.value, peakForV2P.index));
                    
                    // 谷→山の履歴に追加（谷と山の両方を保存）
                    valleyToPeakHistory.add(valleyForV2P);
                    valleyToPeakHistory.add(peakForV2P);
                    if (valleyToPeakHistory.size() > 10) { // 5ペア分保持
                        valleyToPeakHistory.remove(0);
                        valleyToPeakHistory.remove(0);
                    }
                    
                    lastV2PDetectionTime = currentTime;
                    lastV2PDetectionId++;
                    hasNewDetection = true;
                    needsAverageUpdate = true;
                    
                    Log.d("BaseLogic-Async", String.format("✓ Valley→Peak detected: valley=%.3f, peak=%.3f, dt=%dms", 
                        valleyForV2P.value, peakForV2P.value, timeDiff));
                }
            }
        }

        // 山→谷パターンの独立検出（異なるイベントを使用）
        PeakValleyEvent peakForP2V = detectPeakForPeakToValley(ibiMs);
        PeakValleyEvent valleyForP2V = detectValleyForPeakToValley(ibiMs);
        
        if (peakForP2V != null && valleyForP2V != null) {
            Log.d("BaseLogic-Async", String.format("P2V candidates found: peak(%.3f, idx=%d) -> valley(%.3f, idx=%d)", 
                peakForP2V.value, peakForP2V.index, valleyForP2V.value, valleyForP2V.index));
        } else {
            Log.d("BaseLogic-Async", String.format("P2V detection failed: peak=%s, valley=%s", 
                peakForP2V != null ? String.format("%.3f", peakForP2V.value) : "null",
                valleyForP2V != null ? String.format("%.3f", valleyForP2V.value) : "null"));
        }
        
        if (peakForP2V != null && valleyForP2V != null) {
            long timeDiff = Math.abs(valleyForP2V.timestamp - peakForP2V.timestamp);
            double maxDt = ibiMs * 0.875; // IBIの7/8
            
            // 山→谷の条件チェック（条件を緩和）
            boolean isValidAmplitude = Math.abs(peakForP2V.value - valleyForP2V.value) > 0.1; // 0.5 → 0.1
            boolean isValidTiming = timeDiff >= 10 && timeDiff <= ibiMs * 4; // 20 → 10, 3 → 4
            int indexDiff = Math.abs(peakForP2V.index - valleyForP2V.index);
            boolean isValidIndexDiff = indexDiff <= 120; // 90 → 120
            boolean isValidPattern = peakForP2V.value > valleyForP2V.value - 0.5; // 0 → -0.5
            boolean isValidHrTiming = timeDiff <= maxDt;
            
            // 独立性チェック：P2Vの山と谷が異なるインデックスを持つ
            boolean isIndependent = peakForP2V.index != valleyForP2V.index;
            
            // 追加の独立性チェック：位置の差が十分にある
            int positionDiff = Math.abs(peakForP2V.index - valleyForP2V.index);
            boolean hasSufficientPositionDiff = positionDiff >= 3; // 最低3フレームの差
            
            boolean isValid = isValidAmplitude && isValidTiming && isValidIndexDiff && isValidPattern && isValidHrTiming && isIndependent && hasSufficientPositionDiff;
            
            // 重複検出防止: 前回の検出から一定時間経過しているかチェック
            boolean isNewDetection = (currentTime - lastP2VDetectionTime) > 100; // 100ms以上経過
            
            if (isValid && isNewDetection) {
                // 重複チェック：同じamplitudeかつindexDiffが連続した場合は2回目以降を弾く
                boolean isDuplicate = false;
                if (peakToValleyHistory.size() >= 2) {
                    PeakValleyEvent lastPeak = peakToValleyHistory.get(peakToValleyHistory.size() - 2);
                    PeakValleyEvent lastValley = peakToValleyHistory.get(peakToValleyHistory.size() - 1);
                    double lastAmplitude = Math.abs(lastPeak.value - lastValley.value);
                    int lastIndexDiff = Math.abs(lastPeak.index - lastValley.index);
                    double currentAmplitude = Math.abs(peakForP2V.value - valleyForP2V.value);
                    int currentIndexDiff = Math.abs(peakForP2V.index - valleyForP2V.index);
                    
                    // 振幅とインデックス差が同じ場合は重複とみなす
                    if (Math.abs(lastAmplitude - currentAmplitude) < 0.01 && lastIndexDiff == currentIndexDiff) {
                        isDuplicate = true;
                        Log.d("BaseLogic-Async", "P2V duplicate detected and skipped");
                    }
                }
                
                if (!isDuplicate) {
                    Log.d("BaseLogic-Async", String.format("P2V validation: amplitude=%s(%.3f), timing=%s(%dms), indexDiff=%s(%d), pattern=%s, hrTiming=%s(%dms), independent=%s, posDiff=%s(%d), valid=%s",
                        isValidAmplitude, Math.abs(peakForP2V.value - valleyForP2V.value),
                        isValidTiming, timeDiff, isValidIndexDiff, indexDiff, isValidPattern, isValidHrTiming, timeDiff, isIndependent, hasSufficientPositionDiff, positionDiff, isValid));
                    Log.d("BaseLogic-Async", String.format("P2V details: peak(%.3f, idx=%d) -> valley(%.3f, idx=%d)", 
                        peakForP2V.value, peakForP2V.index, valleyForP2V.value, valleyForP2V.index));
                    
                    // 山→谷の履歴に追加（山と谷の両方を保存）
                    peakToValleyHistory.add(peakForP2V);
                    peakToValleyHistory.add(valleyForP2V);
                    if (peakToValleyHistory.size() > 10) { // 5ペア分保持
                        peakToValleyHistory.remove(0);
                        peakToValleyHistory.remove(0);
                    }
                    
                    lastP2VDetectionTime = currentTime;
                    lastP2VDetectionId++;
                    hasNewDetection = true;
                    needsAverageUpdate = true;
                    
                    Log.d("BaseLogic-Async", String.format("✓ Peak→Valley detected: peak=%.3f, valley=%.3f, dt=%dms", 
                        peakForP2V.value, valleyForP2V.value, timeDiff));
                }
            }
        }

        // 新しい検出があった場合のみ平均値を計算
        if (hasNewDetection && needsAverageUpdate) {
            updateAverageValuesAsync(ibiMs);
            needsAverageUpdate = false;
        }
        
        Log.d("BaseLogic-Async", "=== detectPeakAndValleyAsync END ===");
    }

    /**
     * 谷→山パターン用の谷検出（前半の範囲を検索）
     */
    private PeakValleyEvent detectValleyForValleyToPeak(double ibiMs) {
        int frameRate = 30;
        int N = Math.min((int)Math.round(ibiMs / (1000.0 / frameRate)) + 10, WINDOW_SIZE - 5);
        
        // V2P用：前半の範囲を検索（谷→山の谷は前半に現れる）
        int searchStart = 2;
        int searchEnd = N / 2; // 前半のみ検索

        int bestValleyIdx = -1;
        double minValue = Double.POSITIVE_INFINITY;
        int bestValleyPosition = -1;

        // 前半の範囲で局所最小値を谷として検出
        for (int i = searchStart; i < searchEnd; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v_prev1 = window[(idx - 1 + WINDOW_SIZE) % WINDOW_SIZE];
            double v_curr = window[idx];
            double v_next1 = window[(idx + 1) % WINDOW_SIZE];

            // 局所最小値の条件：前後の値より小さい
            if (v_curr < v_prev1 && v_curr < v_next1 && v_curr < minValue) {
                minValue = v_curr;
                bestValleyIdx = idx;
                bestValleyPosition = i;
            }
        }

        if (bestValleyIdx != -1) {
            long currentTime = System.currentTimeMillis();
            long dataTimestamp = currentTime - (bestValleyPosition * (1000 / frameRate));
            Log.d("BaseLogic-Async", String.format("V2P Valley found: value=%.3f, idx=%d, pos=%d, N=%d, range=%d-%d", 
                minValue, bestValleyIdx, bestValleyPosition, N, searchStart, searchEnd));
            return new PeakValleyEvent(dataTimestamp, minValue, bestValleyIdx);
        }

        Log.d("BaseLogic-Async", String.format("V2P Valley not found: N=%d, searchRange=%d-%d", N, searchStart, searchEnd));
        return null;
    }

    /**
     * 谷→山パターン用の山検出（後半の範囲を検索）
     */
    private PeakValleyEvent detectPeakForValleyToPeak(double ibiMs) {
        int frameRate = 30;
        int N = Math.min((int)Math.round(ibiMs / (1000.0 / frameRate)) + 10, WINDOW_SIZE - 5);
        
        // V2P用：後半の範囲を検索（谷→山の山は後半に現れる）
        int searchStart = N / 2; // 後半から検索開始
        int searchEnd = N - 2;

        int bestPeakIdx = -1;
        double maxValue = Double.NEGATIVE_INFINITY;
        int bestPeakPosition = -1;

        // 後半の範囲で局所最大値を山として検出
        for (int i = searchStart; i < searchEnd; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v_prev1 = window[(idx - 1 + WINDOW_SIZE) % WINDOW_SIZE];
            double v_curr = window[idx];
            double v_next1 = window[(idx + 1) % WINDOW_SIZE];

            // 局所最大値の条件：前後の値より大きい
            if (v_curr > v_prev1 && v_curr > v_next1 && v_curr > maxValue) {
                maxValue = v_curr;
                bestPeakIdx = idx;
                bestPeakPosition = i;
            }
        }

        if (bestPeakIdx != -1) {
            long currentTime = System.currentTimeMillis();
            long dataTimestamp = currentTime - (bestPeakPosition * (1000 / frameRate));
            Log.d("BaseLogic-Async", String.format("V2P Peak found: value=%.3f, idx=%d, pos=%d, N=%d, range=%d-%d", 
                maxValue, bestPeakIdx, bestPeakPosition, N, searchStart, searchEnd));
            return new PeakValleyEvent(dataTimestamp, maxValue, bestPeakIdx);
        }

        Log.d("BaseLogic-Async", String.format("V2P Peak not found: N=%d, searchRange=%d-%d", N, searchStart, searchEnd));
        return null;
    }

    /**
     * 山→谷パターン用の山検出（前半の範囲を検索、V2Pとは異なる範囲）
     */
    private PeakValleyEvent detectPeakForPeakToValley(double ibiMs) {
        int frameRate = 30;
        int N = Math.min((int)Math.round(ibiMs / (1000.0 / frameRate)) + 10, WINDOW_SIZE - 5);
        
        // P2V用：前半の範囲を検索（山→谷の山は前半に現れる）
        // V2Pとは異なる範囲を使用して独立性を確保
        int searchStart = 2;
        int searchEnd = (N / 2) - 2; // V2Pの谷検出範囲より少し狭く

        int bestPeakIdx = -1;
        double maxValue = Double.NEGATIVE_INFINITY;
        int bestPeakPosition = -1;

        // 前半の範囲で局所最大値を山として検出
        for (int i = searchStart; i < searchEnd; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v_prev1 = window[(idx - 1 + WINDOW_SIZE) % WINDOW_SIZE];
            double v_curr = window[idx];
            double v_next1 = window[(idx + 1) % WINDOW_SIZE];

            // 局所最大値の条件：前後の値より大きい
            if (v_curr > v_prev1 && v_curr > v_next1 && v_curr > maxValue) {
                maxValue = v_curr;
                bestPeakIdx = idx;
                bestPeakPosition = i;
            }
        }

        if (bestPeakIdx != -1) {
            long currentTime = System.currentTimeMillis();
            long dataTimestamp = currentTime - (bestPeakPosition * (1000 / frameRate));
            Log.d("BaseLogic-Async", String.format("P2V Peak found: value=%.3f, idx=%d, pos=%d, N=%d, range=%d-%d", 
                maxValue, bestPeakIdx, bestPeakPosition, N, searchStart, searchEnd));
            return new PeakValleyEvent(dataTimestamp, maxValue, bestPeakIdx);
        }

        Log.d("BaseLogic-Async", String.format("P2V Peak not found: N=%d, searchRange=%d-%d", N, searchStart, searchEnd));
        return null;
    }

    /**
     * 山→谷パターン用の谷検出（後半の範囲を検索、V2Pとは異なる範囲）
     */
    private PeakValleyEvent detectValleyForPeakToValley(double ibiMs) {
        int frameRate = 30;
        int N = Math.min((int)Math.round(ibiMs / (1000.0 / frameRate)) + 10, WINDOW_SIZE - 5);
        
        // P2V用：後半の範囲を検索（山→谷の谷は後半に現れる）
        // V2Pとは異なる範囲を使用して独立性を確保
        int searchStart = (N / 2) + 2; // V2Pの山検出範囲より少し後ろから
        int searchEnd = N - 2;

        int bestValleyIdx = -1;
        double minValue = Double.POSITIVE_INFINITY;
        int bestValleyPosition = -1;

        // 後半の範囲で局所最小値を谷として検出
        for (int i = searchStart; i < searchEnd; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v_prev1 = window[(idx - 1 + WINDOW_SIZE) % WINDOW_SIZE];
            double v_curr = window[idx];
            double v_next1 = window[(idx + 1) % WINDOW_SIZE];

            // 局所最小値の条件：前後の値より小さい
            if (v_curr < v_prev1 && v_curr < v_next1 && v_curr < minValue) {
                minValue = v_curr;
                bestValleyIdx = idx;
                bestValleyPosition = i;
            }
        }

        if (bestValleyIdx != -1) {
            long currentTime = System.currentTimeMillis();
            long dataTimestamp = currentTime - (bestValleyPosition * (1000 / frameRate));
            Log.d("BaseLogic-Async", String.format("P2V Valley found: value=%.3f, idx=%d, pos=%d, N=%d, range=%d-%d", 
                minValue, bestValleyIdx, bestValleyPosition, N, searchStart, searchEnd));
            return new PeakValleyEvent(dataTimestamp, minValue, bestValleyIdx);
        }

        Log.d("BaseLogic-Async", String.format("P2V Valley not found: N=%d, searchRange=%d-%d", N, searchStart, searchEnd));
        return null;
    }

    /**
     * 非同期で平均値を計算（新しい検出があった場合のみ実行）
     */
    private void updateAverageValuesAsync(double ibiMs) {
        Log.d("BaseLogic-Async", "=== updateAverageValuesAsync START ===");
        
        // 履歴の内容確認ログ
        Log.d("BaseLogic-Async", String.format("History status: V2P_history=%d, P2V_history=%d", 
            valleyToPeakHistory.size(), peakToValleyHistory.size()));
        
        // 最新のP2VとV2Pの平均を計算
        if (valleyToPeakHistory.size() >= 2) {
            // 最新の谷→山ペアを取得（時系列順）
            PeakValleyEvent latestValley = valleyToPeakHistory.get(valleyToPeakHistory.size() - 2);
            PeakValleyEvent latestPeak = valleyToPeakHistory.get(valleyToPeakHistory.size() - 1);
            
            // タイムスタンプ比較を削除し、配列順序で処理
            double amplitude = Math.abs(latestPeak.value - latestValley.value);
            double timeDiff = latestPeak.timestamp - latestValley.timestamp;
            double relTTP = timeDiff / ibiMs;
            
            averageValleyToPeakRelTTP = relTTP;
            averageValleyToPeakAmplitude = amplitude;
            
            Log.d("BaseLogic-Async", String.format("V2P latest: valley(%.3f) -> peak(%.3f), amp=%.3f, relTTP=%.3f", 
                latestValley.value, latestPeak.value, amplitude, relTTP));
        } else {
            Log.d("BaseLogic-Async", "V2P history insufficient: size=" + valleyToPeakHistory.size());
        }
        
        if (peakToValleyHistory.size() >= 2) {
            // 最新の山→谷ペアを取得（時系列順）
            PeakValleyEvent latestPeak = peakToValleyHistory.get(peakToValleyHistory.size() - 2);
            PeakValleyEvent latestValley = peakToValleyHistory.get(peakToValleyHistory.size() - 1);
            
            // タイムスタンプ比較を削除し、配列順序で処理
            double amplitude = Math.abs(latestPeak.value - latestValley.value);
            double timeDiff = latestValley.timestamp - latestPeak.timestamp;
            double relTTP = timeDiff / ibiMs;
            
            averagePeakToValleyRelTTP = relTTP;
            averagePeakToValleyAmplitude = amplitude;
            
            Log.d("BaseLogic-Async", String.format("P2V latest: peak(%.3f) -> valley(%.3f), amp=%.3f, relTTP=%.3f", 
                latestPeak.value, latestValley.value, amplitude, relTTP));
        } else {
            Log.d("BaseLogic-Async", "P2V history insufficient: size=" + peakToValleyHistory.size());
        }
        
        // 正しく追加できているかの確認ログ
        Log.d("BaseLogic-Async", String.format("Before AI calculation: V2P_amp=%.3f, P2V_amp=%.3f, V2P_relTTP=%.3f, P2V_relTTP=%.3f", 
            averageValleyToPeakAmplitude, averagePeakToValleyAmplitude, 
            averageValleyToPeakRelTTP, averagePeakToValleyRelTTP));
        
        // AIの計算
        double totalAmplitude = averageValleyToPeakAmplitude + averagePeakToValleyAmplitude;
        if (totalAmplitude > 0) {
            averageAI = (averageValleyToPeakAmplitude / totalAmplitude) * 100.0;
        } else if (averageValleyToPeakAmplitude > 0) {
            // V2Pのみの場合
            averageAI = 100.0;
        } else if (averagePeakToValleyAmplitude > 0) {
            // P2Vのみの場合
            averageAI = 0.0;
        }
        
        lastUpdateTime = System.currentTimeMillis();
        
        Log.d("BaseLogic-Async", String.format("✓ Average updated: V2P_relTTP=%.3f, P2V_relTTP=%.3f, V2P_amp=%.3f, P2V_amp=%.3f, AI=%.3f%% (V2P_history:%d, P2V_history:%d)",
            averageValleyToPeakRelTTP, averagePeakToValleyRelTTP, 
            averageValleyToPeakAmplitude, averagePeakToValleyAmplitude, averageAI,
            valleyToPeakHistory.size(), peakToValleyHistory.size()));
        
        Log.d("BaseLogic-Async", "=== updateAverageValuesAsync END ===");
    }

    /**
     * 心拍数検出メソッド（既存の処理を保持）
     */
    protected LogicResult detectHeartRateAndUpdate() {
        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("BaseLogic-ISO", "Heart rate detection skipped: ISO=" + currentISO);
            return null;
        }

        double currentVal = window[(windowIndex + WINDOW_SIZE - 1) % WINDOW_SIZE];
        double previous1  = window[(windowIndex + WINDOW_SIZE - 2) % WINDOW_SIZE];
        double previous2  = window[(windowIndex + WINDOW_SIZE - 3) % WINDOW_SIZE];
        double previous3  = window[(windowIndex + WINDOW_SIZE - 4) % WINDOW_SIZE];
        double previous4  = window[(windowIndex + WINDOW_SIZE - 5) % WINDOW_SIZE];
        
        if (framesSinceLastPeak >= REFRACTORY_FRAMES
                && previous1 > previous2
                && previous2 > previous3
                && previous3 > previous4
                && previous1 > currentVal) {
            framesSinceLastPeak = 0;
            long currentTime = System.currentTimeMillis();
            if (lastPeakTime != 0) {
                double interval = (currentTime - lastPeakTime) / 1000.0;
                if (interval > 0.25 && interval < 1.2) {
                    double bpm = 60.0 / interval;
                    if (bpmHistory.size() >= BPM_HISTORY_SIZE) {
                        bpmHistory.remove(0);
                    }
                    bpmHistory.add(bpm);
                    double meanBpm = getMean(bpmHistory);
                    double bpmSD   = getStdDev(bpmHistory);
                    if (bpm >= meanBpm - meanBpm * 0.1
                            && bpm <= meanBpm + meanBpm * 0.1) {
                        bpmValue = bpm;
                        IBI      = 60.0 / bpmValue * 1000.0;
                    }
                    lastPeakTime = currentTime;
                    updateCount++;
                    if (bpCallback != null) {
                        bpCallback.onFrame(currentVal, IBI);
                    }
                    // 心拍数とSDを更新
                    if (bpmValue > 0) {
                        smoothedBpm = bpmValue;
                        smoothedBpmSd.add(bpmSD);
                        if (smoothedBpmSd.size() > 5) {
                            smoothedBpmSd.remove(0);
                        }
                        
                        // 有効な値を保存（ISO < 500の時に使用）
                        lastValidBpm = bpmValue;
                        lastValidSd = bpmSD;
                        
                        Log.d("BaseLogic-HR", String.format("✓ HR updated: BPM=%.1f, SD=%.1f, IBI=%.1fms", bpmValue, bpmSD, IBI));
                    }
                    return new LogicResult(currentVal, IBI, bpmValue, bpmSD);
                }
            }
            lastPeakTime = System.currentTimeMillis();
        }
        framesSinceLastPeak++;
        return null; // 心拍数が検出されなかった場合
    }



    // 各Logicで異なる部分は抽象メソッドで定義
    @Override
    public abstract LogicResult processGreenValueData(double avgG);
}