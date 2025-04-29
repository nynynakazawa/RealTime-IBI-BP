package com.example.realtimehribicontrol;

import android.media.Image;
import android.util.Log;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Locale;

public class Logic5 implements LogicProcessor{

    // 定数
    private static final int GREEN_VALUE_WINDOW_SIZE = 20;
    private static final int CORRECTED_GREEN_VALUE_WINDOW_SIZE = 20;
    private static final int SAMPLE_RATE = 60;
    private static final double LOW_PASS = 0.5;
    private static final double HIGH_PASS = 5.0;
    private static final int WINDOW_SIZE = 240;
    private static final int BPM_HISTORY_SIZE = 20;
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
    // 初期化
    // KalmanFilterクラスのインスタンスをフィールドとして保持（クラスの初期化時に作成）
    private KalmanFilter kalmanFilter = new KalmanFilter();

    // これらをLogic1の他のメンバ変数と同様に定義してください
    private static final long MIN_PEAK_INTERVAL_MS = 300; // 300ms以上の間隔を要求（例）
    private ArrayList<Double> peakHistory = new ArrayList<>();
    private static final int PEAK_HISTORY_SIZE = 5; // 動的閾値に利用するピーク履歴のサイズ

    // 心拍関連
    private double bpmValue = 0.0;
    private double IBI = 0.0;

    // ----- UI更新用コールバック（MainActivityなどが設定する前提） -----
    public interface UIUpdateCallback {
        void updateValueText(String text);
        void updateChartValue(float value);
        void updateIbiText(String text);
        void updateHeartRateText(String text);
        void updateSmoothedValuesText(String smoothedIbi, String smoothedBpm);
        void updateBpmSdText(String text);
    }
    private UIUpdateCallback uiCallback;
    public void setUIUpdateCallback(UIUpdateCallback callback) {
        this.uiCallback = callback;
    }

    // ---------------------------------------------------
    // 【メソッド：環境光に基づいた画像補正】
    // ---------------------------------------------------

    public double adjustImageBasedOnAmbientLight(Image img, double avgG) {
        ByteBuffer yBuffer = img.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = img.getPlanes()[1].getBuffer();

        yBuffer.rewind();
        uBuffer.rewind();

        double ySum = 0;
        int pixelCount = 0;
        int yCapacity = yBuffer.remaining();

        // 4ピクセルごとにY値を取得
        for (int i = 0; i < yCapacity; i += 12) {
            ySum += (yBuffer.get(i) & 0xFF);
            pixelCount++;
        }

        double avgY = ySum / pixelCount;

        double reductionFactor = Math.min(Math.max((256 - avgY) / 256, 0), 1);
        double scale = reductionFactor/2;

        int sumU = 0;
        pixelCount = 0;
        int uCapacity = uBuffer.remaining();

        // Uも4ピクセルごとに間引き
        for (int i = 0; i < uCapacity; i += 4) {
            int uValue = uBuffer.get(i) & 0xFF;
            sumU += (int)(uValue * scale);
            pixelCount++;
        }

        double correctedAvgG = (double) sumU / pixelCount;
        return correctedAvgG;
    }

    // ---------------------------------------------------
    // 【メソッド：統合処理：グリーン値データ処理】
    // ---------------------------------------------------
    public LogicResult processGreenValueData(double correctedAvgG) {

        synchronized (greenValues) {
            greenValues.add(correctedAvgG);
        }
        synchronized (recentGreenValues) {
            if (recentGreenValues.size() >= GREEN_VALUE_WINDOW_SIZE) {
                recentGreenValues.remove(0);
            }
            recentGreenValues.add(correctedAvgG);
        }

        double latestGreenValue = greenValues.get(greenValues.size() - 1) % 30;
        double hundGreenValue = (latestGreenValue / 30.0) * 100.0;

        double correctedGreenValue = hundGreenValue ;

        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
            recentCorrectedGreenValues.remove(0);
        }
        recentCorrectedGreenValues.add(correctedGreenValue);

        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {

            double twiceSmoothedValue = 0.0;
            int sgWindowSize = 7;  // 窓サイズは7 (奇数)
            double[] sgCoeffs = {-2, 3, 6, 7, 6, 3, -2};
            double coeffSum = 21;

            if (recentCorrectedGreenValues.size() >= sgWindowSize) {
                for (int i = 0; i < sgWindowSize; i++) {
                    int idx = recentCorrectedGreenValues.size() - sgWindowSize + i;
                    twiceSmoothedValue += recentCorrectedGreenValues.get(idx) * sgCoeffs[i];
                }
                twiceSmoothedValue /= coeffSum;
            } else {
                twiceSmoothedValue = recentCorrectedGreenValues.get(recentCorrectedGreenValues.size() - 1);
            }

            // smoothedCorrectedGreenValuesに保持（必要な場合のみ）
            smoothedCorrectedGreenValues.add(twiceSmoothedValue);
            if (smoothedCorrectedGreenValues.size() > CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
                smoothedCorrectedGreenValues.remove(0);
            }


            int longWindowSize = 8;
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

        updateValueText(correctedGreenValue);
        updateChart(correctedGreenValue);

        // ピーク検出と心拍数計算
        // 現在のウィンドウの値を取得
        double currentVal2 = window[(windowIndex + WINDOW_SIZE - 1) % WINDOW_SIZE];
        double currentVal1 = window[(windowIndex + WINDOW_SIZE - 2) % WINDOW_SIZE];
        double previous = window[(windowIndex + WINDOW_SIZE - 3) % WINDOW_SIZE];
        double previous1 = window[(windowIndex + WINDOW_SIZE - 4) % WINDOW_SIZE];
        double previous2 = window[(windowIndex + WINDOW_SIZE - 5) % WINDOW_SIZE];
        double previous3 = window[(windowIndex + WINDOW_SIZE - 6) % WINDOW_SIZE];


        if (previous > previous1 && previous1 > previous2 &&
                previous2 > previous3 && previous > currentVal1 && currentVal1 > currentVal2) {
            long currentTime = System.currentTimeMillis();
            // 最小ピーク間隔のチェック（既に条件文内でチェックされています）
            if (lastPeakTime != 0 && (currentTime - lastPeakTime) < MIN_PEAK_INTERVAL_MS) {
                return new LogicResult(correctedGreenValue, IBI, bpmValue, getStdDev(bpmHistory));
            }
            double interval = (currentTime - lastPeakTime) / 1000.0;
            if (lastPeakTime != 0 && interval > 0.25 && interval < 1.2) {
                double bpm = 60.0 / interval;
                double ibi = 60.0 / bpm * 1000.0;
                if (bpmHistory.size() >= BPM_HISTORY_SIZE) {
                    bpmHistory.remove(0);
                }
                bpmHistory.add(bpm);
                double meanBpm = getMean(bpmHistory);
                double bpmSD = getStdDev(bpmHistory);
                if (bpm >= meanBpm - meanBpm * 0.1 && bpm <= meanBpm + meanBpm * 0.1) {
                    bpmValue = bpm;
                    IBI = 60.0 / bpmValue * 1000.0;
                }
                // 記録用にピーク値を保存し、peakHistoryの更新
                peakHistory.add(previous);
                if (peakHistory.size() > PEAK_HISTORY_SIZE) {
                    peakHistory.remove(0);
                }
                lastPeakTime = currentTime;
                updateCount++;
                return new LogicResult(correctedGreenValue, IBI, bpmValue, bpmSD);
            }
            lastPeakTime = currentTime;
        }
        return new LogicResult(correctedGreenValue, IBI, bpmValue, getStdDev(bpmHistory));

    }


    public class KalmanFilter {
        // クラスフィールドとして状態を保持
        private double estimate = 0.0;
        private double errorCovariance = 1.0;
        private double processNoise = 1e-5;
        private double measurementNoise = 1e-2;

        /**
         * 測定値を与えたときにカルマンフィルターを更新する
         * @param measurement 最新の測定値
         * @return 更新されたフィルタ出力（推定値）
         */
        public double update(double measurement) {
            double kalmanGain = errorCovariance / (errorCovariance + measurementNoise);
            estimate = estimate + kalmanGain * (measurement - estimate);
            errorCovariance = (1 - kalmanGain) * errorCovariance + processNoise;
            return estimate;
        }
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