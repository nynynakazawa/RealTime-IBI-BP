package com.nakazawa.realtimeibibp.bp;

import com.nakazawa.realtimeibibp.SignalProcessingUtils;
import java.util.Locale;

public final class FeatureClampUtils {
    private FeatureClampUtils() {
    }

    public static double clampFeature(String label, double value, double lower, double upper, StringBuilder reason) {
        double clamped = SignalProcessingUtils.clamp(value, lower, upper);
        if (Math.abs(clamped - value) > 1e-9) {
            appendReason(reason, label, value, clamped);
        }
        return clamped;
    }

    public static double clampUpperFeature(String label, double value, double upper, StringBuilder reason) {
        double clamped = Math.min(value, upper);
        if (Math.abs(clamped - value) > 1e-9) {
            appendReason(reason, label, value, clamped);
        }
        return clamped;
    }

    public static int countFeatureClampSegments(CharSequence reason) {
        if (reason == null || reason.length() == 0) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < reason.length(); i++) {
            if (reason.charAt(i) == '|') {
                count++;
            }
        }
        return count;
    }

    private static void appendReason(StringBuilder reason, String label, double before, double after) {
        if (reason.length() > 0) {
            reason.append("|");
        }
        reason.append(label)
                .append(":")
                .append(String.format(Locale.US, "%.4f->%.4f", before, after));
    }
}
