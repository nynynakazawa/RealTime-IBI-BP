package com.example.realtimehribicontrol;

import android.media.Image;

public interface LogicProcessor {
    double adjustImageBasedOnAmbientLight(Image img, double avgG);
    LogicResult processGreenValueData(double avgG);
    void calculateSmoothedValueRealTime(double ibi, double bpmSd);
    double getLastSmoothedIbi();
}