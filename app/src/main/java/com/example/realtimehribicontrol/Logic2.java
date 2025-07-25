package com.example.realtimehribicontrol;

public class Logic2 extends BaseLogic {
    @Override
    public LogicResult processGreenValueData(double avgG) {
        // Logic2特有の処理（例: smoothingWindowSize1=4, range正規化, windowへの格納値など）
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
            // range正規化
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
                range = 1.0;
            }
            correctedGreenValue = ((twiceSmoothedValue - localMin) / range) * 100.0;
            correctedGreenValue = Math.max(0, Math.min(100, correctedGreenValue));
            window[windowIndex] = correctedGreenValue;
            windowIndex = (windowIndex + 1) % WINDOW_SIZE;
        }
        if (bpCallback != null) {
            bpCallback.onFrame(correctedGreenValue, IBI);
        }
        updateValueText(correctedGreenValue);
        updateChart(correctedGreenValue);
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
                        bpCallback.onFrame(correctedGreenValue, IBI);
                    }
                    return new LogicResult(correctedGreenValue, IBI, bpmValue, bpmSD);
                }
            }
            lastPeakTime = System.currentTimeMillis();
        }
        framesSinceLastPeak++;
        return new LogicResult(correctedGreenValue, IBI, bpmValue, getStdDev(bpmHistory));
    }
}