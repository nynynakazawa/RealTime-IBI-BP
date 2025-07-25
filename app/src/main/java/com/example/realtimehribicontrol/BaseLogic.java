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
        if(uiCallback != null) {
            uiCallback.updateValueText("Value : " + String.format(Locale.getDefault(), "%.2f", value));
        }
    }
    protected void updateChart(double value) {
        if(uiCallback != null) {
            uiCallback.updateChartValue((float) value);
        }
    }
    protected void updateSmoothedValues(double smoothedIbi, double smoothedBpm) {
        if(uiCallback != null) {
            uiCallback.updateSmoothedValuesText("IBI(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedIbi),
                    "HR(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedBpm));
        }
    }

    @Override
    public double getLastSmoothedIbi() {
        if (!smoothedIbi.isEmpty()) {
            return smoothedIbi.get(smoothedIbi.size() - 1);
        }
        return 0;
    }

    public void reset() {
        greenValues.clear();
        filteredValues.clear();
        recentGreenValues.clear();
        recentCorrectedGreenValues.clear();
        smoothedCorrectedGreenValues.clear();
        smoothedIbi.clear();
        smoothedBpmSd.clear();
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
    protected PeakValleyEvent lastValleyEvent = null;
    protected PeakValleyEvent lastPeakEvent = null;

    /**
     * window内の最小値を必ず谷として記録
     */
    protected int detectValleyIndexAndRecord(int windowSize) {
        int minIdx = -1;
        double minVal = Double.POSITIVE_INFINITY;
        for (int i = 0; i < windowSize; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v = window[idx];
            if (v < minVal) {
                minVal = v;
                minIdx = idx;
            }
        }
        if (minIdx != -1) {
            lastValleyEvent = new PeakValleyEvent(System.currentTimeMillis(), window[minIdx], minIdx);
        }
        return minIdx;
    }

    /**
     * window内の最大値を必ず山として記録
     */
    protected int detectPeakIndexAndRecord(int windowSize) {
        int maxIdx = -1;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < windowSize; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v = window[idx];
            if (v > maxVal) {
                maxVal = v;
                maxIdx = idx;
            }
        }
        if (maxIdx != -1) {
            lastPeakEvent = new PeakValleyEvent(System.currentTimeMillis(), window[maxIdx], maxIdx);
        }
        return maxIdx;
    }

    /**
     * window配列から指定インデックスの値を取得
     */
    protected double getWindowValue(int idx) {
        return window[idx % WINDOW_SIZE];
    }

    /**
     * window配列の直近N点の平均を返す
     */
    protected double getMeanFromWindow(int windowSize) {
        double sum = 0.0;
        for (int i = 0; i < windowSize; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            sum += window[idx];
        }
        return sum / windowSize;
    }

    /**
     * 直近N点（N=最新のIBI/30+5）で最新に現れた谷を検出し記録
     */
    protected int detectLatestValleyAndRecord() {
        int frameRate = 30;
        double ibiMs = getLastSmoothedIbi();
        int N = (int)Math.round(ibiMs / (1000.0 / frameRate)) + 7;
        if (N > WINDOW_SIZE) N = WINDOW_SIZE;
        for (int i = 0; i < N - 4; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v0 = window[(idx + 0) % WINDOW_SIZE];
            double v1 = window[(idx + 1) % WINDOW_SIZE];
            double v2 = window[(idx + 2) % WINDOW_SIZE];
            double v3 = window[(idx + 3) % WINDOW_SIZE];
            double v4 = window[(idx + 4) % WINDOW_SIZE];
            if (v1 < v0 && v2 < v1 && v3 < v2 && v4 < v3 && v1 < v2) {
                int foundIdx = (idx + 1) % WINDOW_SIZE;
                double foundVal = window[foundIdx];
                lastValleyEvent = new PeakValleyEvent(System.currentTimeMillis(), foundVal, foundIdx);
                return foundIdx;
            }
        }
        return -1;
    }

    /**
     * 直近N点（N=最新のIBI/30+5）で最新に現れた山を検出し記録
     */
    protected int detectLatestPeakAndRecord() {
        int frameRate = 30;
        double ibiMs = getLastSmoothedIbi();
        int N = (int)Math.round(ibiMs / (1000.0 / frameRate)) + 7;
        if (N > WINDOW_SIZE) N = WINDOW_SIZE;
        for (int i = 0; i < N - 4; i++) {
            int idx = (windowIndex + WINDOW_SIZE - 1 - i) % WINDOW_SIZE;
            double v0 = window[(idx + 0) % WINDOW_SIZE];
            double v1 = window[(idx + 1) % WINDOW_SIZE];
            double v2 = window[(idx + 2) % WINDOW_SIZE];
            double v3 = window[(idx + 3) % WINDOW_SIZE];
            double v4 = window[(idx + 4) % WINDOW_SIZE];
            if (v1 > v0 && v2 > v1 && v3 > v2 && v4 > v3 && v1 > v2) {
                int foundIdx = (idx + 1) % WINDOW_SIZE;
                double foundVal = window[foundIdx];
                lastPeakEvent = new PeakValleyEvent(System.currentTimeMillis(), foundVal, foundIdx);
                return foundIdx;
            }
        }
        return -1;
    }

    // 各Logicで異なる部分は抽象メソッドで定義
    @Override
    public abstract LogicResult processGreenValueData(double avgG);
}