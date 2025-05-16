package com.example.realtimehribicontrol;

public interface LogicProcessor {
    LogicResult processGreenValueData(double avgG);
    void calculateSmoothedValueRealTime(double ibi, double bpmSd);
    double getLastSmoothedIbi();
}