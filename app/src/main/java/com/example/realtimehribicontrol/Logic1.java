package com.example.realtimehribicontrol;

import android.util.Log;
import java.util.ArrayList;
import java.util.Locale;

public class Logic1 implements LogicProcessor {

    // 定数
    private static final int GREEN_VALUE_WINDOW_SIZE = 20;
    private static final int CORRECTED_GREEN_VALUE_WINDOW_SIZE = 20;
    private static final int SAMPLE_RATE = 60;
    private static final double LOW_PASS = 0.5;
    private static final double HIGH_PASS = 5.0;
    private static final int WINDOW_SIZE = 240;
    private static final int BPM_HISTORY_SIZE = 20;

    // メンバー変数
    private float averageAmbientLight = -1f;
    private float reductionFactor = 0.50f;  // Logic1用: 0.50f
    private ArrayList<Double> greenValues = new ArrayList<>();
    private ArrayList<Double> filteredValues = new ArrayList<>();
    private ArrayList<Double> recentGreenValues = new ArrayList<>();
    private ArrayList<Double> recentCorrectedGreenValues = new ArrayList<>();
    private ArrayList<Double> smoothedCorrectedGreenValues = new ArrayList<>();
    private ArrayList<Double> smoothedIbi = new ArrayList<>();
    private ArrayList<Double> smoothedBpmSd = new ArrayList<>();
    private double[] window = new double[WINDOW_SIZE];
    private int windowIndex = 0;
    private ArrayList<Double> bpmHistory = new ArrayList<>();
    private long lastPeakTime = 0;
    private int updateCount = 0;
    private double lastIbiValue = -1;
    private static final int REFRACTORY_FRAMES = 8;
    private int framesSinceLastPeak = REFRACTORY_FRAMES;

    // 心拍関連
    private double bpmValue = 0.0;
    private double IBI = 0.0;

    // ----- UI更新用コールバック（MainActivityなどが設定する前提） -----
    public interface UIUpdateCallback {
        void updateValueText(String text);
        void updateChartValue(float value);
        void updateSmoothedValuesText(String smoothedIbi, String smoothedBpm);
    }
    private UIUpdateCallback uiCallback;
    public void setUIUpdateCallback(UIUpdateCallback callback) {
        this.uiCallback = callback;
    }

    // ---------------------------------------------------
    // 【メソッド：統合処理：グリーン値データ処理】
    // ---------------------------------------------------
    public LogicResult processGreenValueData(double avgG) {
        Log.d("Logic1", "processGreenValueData() start with avgG = " + avgG);
        synchronized (greenValues) {
            greenValues.add(avgG);
        }
        synchronized (recentGreenValues) {
            if (recentGreenValues.size() >= GREEN_VALUE_WINDOW_SIZE) {
                recentGreenValues.remove(0);
            }
            recentGreenValues.add(avgG);
        }
        double latestGreenValue = greenValues.get(greenValues.size() - 1) % 30;
        double hundGreenValue = (latestGreenValue / 30.0) * 100.0;

        double correctedGreenValue = hundGreenValue * 3 ;

        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
            recentCorrectedGreenValues.remove(0);
        }
        recentCorrectedGreenValues.add(correctedGreenValue);

        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
            double smoothedCorrectedGreenValue = 0.0;
            int smoothingWindowSize1 = 4;
            for (int i = 0; i < smoothingWindowSize1; i++) {
                int index = recentCorrectedGreenValues.size() - 1 - i;
                if (index >= 0) {
                    smoothedCorrectedGreenValue += recentCorrectedGreenValues.get(index);
                }
            }
            smoothedCorrectedGreenValue /= Math.min(smoothingWindowSize1, recentCorrectedGreenValues.size());

            if (smoothedCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
                smoothedCorrectedGreenValues.remove(0);
            }
            smoothedCorrectedGreenValues.add(smoothedCorrectedGreenValue);

            double twiceSmoothedValue = 0.0;
            int smoothingWindowSize2 = 4;
            for (int i = 0; i < smoothingWindowSize2; i++) {
                int index = smoothedCorrectedGreenValues.size() - 1 - i;
                if (index >= 0) {
                    twiceSmoothedValue += smoothedCorrectedGreenValues.get(index);
                }
            }
            twiceSmoothedValue /= Math.min(smoothingWindowSize2, smoothedCorrectedGreenValues.size());

            // 長めのウィンドウを使って局所的な最小値と最大値を計算
            // 例として、直近40サンプルから局所的な最小値と最大値を計算
            int longWindowSize = 40;
            int startIdx = Math.max(0, smoothedCorrectedGreenValues.size() - longWindowSize);
            double localMin = Double.POSITIVE_INFINITY;
            double localMax = Double.NEGATIVE_INFINITY;
            for (int i = startIdx; i < smoothedCorrectedGreenValues.size(); i++) {
                double v = smoothedCorrectedGreenValues.get(i);
                if (v < localMin) localMin = v;
                if (v > localMax) localMax = v;
            }
            double range = localMax - localMin;
            if (range < 1.0) {
                range = 1.0;  // ゼロ除算防止
            }
            correctedGreenValue = ((twiceSmoothedValue - localMin) / range) * 100.0;
            correctedGreenValue = Math.max(0, Math.min(100, correctedGreenValue));

            // window 配列に correctedGreenValue を記録し、ピーク検出に利用する
            window[windowIndex] = correctedGreenValue;
            windowIndex = (windowIndex + 1) % WINDOW_SIZE;
        }

        Log.d("Logic1", "normalizeAndSmoothData() final correctedGreenValue = " + correctedGreenValue);
        updateValueText(correctedGreenValue);
        updateChart(correctedGreenValue);

        // ピーク検出と心拍数計算
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

                    return new LogicResult(correctedGreenValue, IBI, bpmValue, bpmSD);
                }
            }
            lastPeakTime = System.currentTimeMillis();
        }

        framesSinceLastPeak++;
        return new LogicResult(correctedGreenValue, IBI, bpmValue, getStdDev(bpmHistory));
    }

    // ---------------------------------------------------
    // 【メソッド：リアルタイム平滑化補間】
    // ---------------------------------------------------
    public void calculateSmoothedValueRealTime(double newIbi, double newBpmSd) {
        // 前回のIBIと比較し、異なる場合だけ平滑化を実行
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
            smoothedBpmSd.add(newBpmSd);  // bpmSD は補間せず、そのまま追加でOK

            updateSmoothedValues(smoothedIbiValue, smoothedBpmValue);

            lastIbiValue = newIbi;  // 最新のIBIを保持
        }
        // 前回と同じIBIの場合、何もしない
    }

    // ---------------------------------------------------
    // 【ヘルパーメソッド：平均・標準偏差計算】
    // ---------------------------------------------------
    private double getMean(ArrayList<Double> values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private double getStdDev(ArrayList<Double> values) {
        double mean = getMean(values);
        double squareSum = 0.0;
        for (double value : values) {
            squareSum += Math.pow(value - mean, 2);
        }
        return Math.sqrt(squareSum / values.size());
    }

    private void updateValueText(double value) {
        Log.d("Logic1", "updateValueText() called with value = " + value);
        if(uiCallback != null) {
            uiCallback.updateValueText("Value : " + String.format(Locale.getDefault(), "%.2f", value));
        }
    }

    private void updateChart(double value) {
        Log.d("Logic1", "Chart updated with value: " + value);
        if(uiCallback != null) {
            uiCallback.updateChartValue((float) value);
        }
    }

    private void updateSmoothedValues(double smoothedIbi, double smoothedBpm) {
        Log.d("Logic1", "Smoothed IBI: " + smoothedIbi + ", Smoothed BPM: " + smoothedBpm);
        if(uiCallback != null) {
            uiCallback.updateSmoothedValuesText("IBI(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedIbi),
                    "HR(Smooth) : " + String.format(Locale.getDefault(), "%.2f", smoothedBpm));
        }
    }

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

}