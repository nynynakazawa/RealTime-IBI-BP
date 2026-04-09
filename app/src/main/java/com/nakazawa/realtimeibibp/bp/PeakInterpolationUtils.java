package com.nakazawa.realtimeibibp.bp;

public final class PeakInterpolationUtils {
    private PeakInterpolationUtils() {
    }

    public static long interpolatePeakTimeMs(
            double left,
            double center,
            double right,
            long baseTimeMs,
            double frameRate) {
        double denominator = left - 2.0 * center + right;
        double deltaFrames = 0.0;
        if (Math.abs(denominator) > 1e-9) {
            deltaFrames = 0.5 * (left - right) / denominator;
            if (!Double.isFinite(deltaFrames)) {
                deltaFrames = 0.0;
            }
        }
        deltaFrames = Math.max(-0.5, Math.min(0.5, deltaFrames));
        double frameDurationMs = 1000.0 / Math.max(frameRate, 1.0);
        return Math.round(baseTimeMs + deltaFrames * frameDurationMs);
    }
}
