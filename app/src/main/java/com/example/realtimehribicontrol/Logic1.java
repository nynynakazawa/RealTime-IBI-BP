package com.example.realtimehribicontrol;

import android.util.Log;

public class Logic1 extends BaseLogic {
    @Override
    public LogicResult processGreenValueData(double avgG) {
        // Logic1特有の処理（例: smoothingWindowSize1=6, correctedGreenValue*3 など）
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
        double correctedGreenValue = hundGreenValue * 3;
        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
            recentCorrectedGreenValues.remove(0);
        }
        recentCorrectedGreenValues.add(correctedGreenValue);
        if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
            double smoothedCorrectedGreenValue = 0.0;
            int smoothingWindowSize1 = 6;
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
            correctedGreenValue = twiceSmoothedValue;
            window[windowIndex] = correctedGreenValue;
            windowIndex = (windowIndex + 1) % WINDOW_SIZE;
        }
        updateValueText(correctedGreenValue);
        updateChart(correctedGreenValue);

        // ISO値を更新（実際のISO値は外部から設定される想定）
        // updateISO(actualISO); // 実際のISO値が取得できる場合はここで更新

        // ISOチェック
        if (!isDetectionValid()) {
            Log.d("Logic1", "Detection disabled due to ISO < 500, using last valid values");
            return new LogicResult(correctedGreenValue, IBI, lastValidBpm, lastValidSd);
        }

        // 心拍数検出（既存の処理を保持）
        LogicResult heartRateResult = detectHeartRateAndUpdate();
        if (heartRateResult != null) {
            return heartRateResult;
        }

        // BaseLogicの非同期検出メソッドを使用
        detectPeakAndValleyAsync(IBI);

        return new LogicResult(correctedGreenValue, IBI, bpmValue, getStdDev(bpmHistory));
    }
}