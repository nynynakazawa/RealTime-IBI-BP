package com.nakazawa.realtimeibibp;

public interface LogicProcessor {
    LogicResult processGreenValueData(double avgG);
    void calculateSmoothedValueRealTime(double ibi, double bpmSd);
    double getLastSmoothedIbi();
}