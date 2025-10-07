package com.nakazawa.realtimeibibp;

public class LogicResult {
    private double correctedGreenValue;
    private double ibi;
    private double heartRate;
    private double bpmSd;

    public LogicResult(double correctedGreenValue, double ibi, double heartRate, double bpmSd) {
        this.correctedGreenValue = correctedGreenValue;
        this.ibi = ibi;
        this.heartRate = heartRate;
        this.bpmSd = bpmSd;
    }

    public double getCorrectedGreenValue() {
        return correctedGreenValue;
    }

    public double getIbi() {
        return ibi;
    }

    public double getHeartRate() {
        return heartRate;
    }

    public double getBpmSd() {
        return bpmSd;
    }
}