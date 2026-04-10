package com.nakazawa.realtimeibibp;

/**
 * BP 推定値の共通後段処理。
 *
 * 目的:
 * 1. SBP/DBP をそのまま平滑化せず、MAP/PP に分解して causal smoothing する
 * 2. recent session から求めた軽い bias 補正を MAP/PP にだけ適用する
 *
 * 補正は method-specific だが形は共通。
 * 2026-04-09 時点では multi-session で十分に安定した postprocess 係数がまだないため、
 * calibration は identity に戻している。将来 Analysis 側で再推定しても wire shape を
 * 変えずに差し替えられるよう、系列自体は残しておく。
 */
public final class BPPostProcessor {
    public enum Method {
        RTBP,
        SIN_BP_D,
        SIN_BP_M,
    }

    private static final double ALPHA_MAP = 0.30;
    private static final double ALPHA_PP = 0.50;
    private static final double MIN_PULSE_PRESSURE = 20.0;
    private static final double MAX_PULSE_PRESSURE = 100.0;

    private final Method method;
    private final Calibration calibration;
    private boolean initialized = false;
    private double lastMapSmoothed = 0.0;
    private double lastPpSmoothed = 0.0;

    private static final class Calibration {
        final double mapIntercept;
        final double mapSlope;
        final double ppIntercept;
        final double ppSlope;

        Calibration(double mapIntercept, double mapSlope, double ppIntercept, double ppSlope) {
            this.mapIntercept = mapIntercept;
            this.mapSlope = mapSlope;
            this.ppIntercept = ppIntercept;
            this.ppSlope = ppSlope;
        }
    }

    public static final class Result {
        public final double mapRaw;
        public final double ppRaw;
        public final double mapSmoothed;
        public final double ppSmoothed;
        public final double mapCalibrated;
        public final double ppCalibrated;
        public final double sbpSmoothed;
        public final double dbpSmoothed;
        public final double sbpCalibrated;
        public final double dbpCalibrated;
        public final int postprocessApplied;

        Result(
                double mapRaw,
                double ppRaw,
                double mapSmoothed,
                double ppSmoothed,
                double mapCalibrated,
                double ppCalibrated,
                double sbpSmoothed,
                double dbpSmoothed,
                double sbpCalibrated,
                double dbpCalibrated,
                int postprocessApplied) {
            this.mapRaw = mapRaw;
            this.ppRaw = ppRaw;
            this.mapSmoothed = mapSmoothed;
            this.ppSmoothed = ppSmoothed;
            this.mapCalibrated = mapCalibrated;
            this.ppCalibrated = ppCalibrated;
            this.sbpSmoothed = sbpSmoothed;
            this.dbpSmoothed = dbpSmoothed;
            this.sbpCalibrated = sbpCalibrated;
            this.dbpCalibrated = dbpCalibrated;
            this.postprocessApplied = postprocessApplied;
        }

        public static Result empty() {
            return new Result(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);
        }
    }

    public BPPostProcessor(Method method) {
        this.method = method;
        this.calibration = calibrationFor(method);
    }

    public Result apply(double sbpRaw, double dbpRaw) {
        if (!Double.isFinite(sbpRaw) || !Double.isFinite(dbpRaw)) {
            return Result.empty();
        }

        double mapRaw = (sbpRaw + 2.0 * dbpRaw) / 3.0;
        double ppRaw = sbpRaw - dbpRaw;

        double mapSmoothed;
        double ppSmoothed;
        if (!initialized) {
            mapSmoothed = mapRaw;
            ppSmoothed = ppRaw;
            initialized = true;
        } else {
            mapSmoothed = ALPHA_MAP * mapRaw + (1.0 - ALPHA_MAP) * lastMapSmoothed;
            ppSmoothed = ALPHA_PP * ppRaw + (1.0 - ALPHA_PP) * lastPpSmoothed;
        }
        lastMapSmoothed = mapSmoothed;
        lastPpSmoothed = ppSmoothed;

        double mapCalibrated = calibration.mapIntercept + calibration.mapSlope * mapSmoothed;
        double ppCalibrated = calibration.ppIntercept + calibration.ppSlope * ppSmoothed;

        double dbpSmoothed = mapSmoothed - ppSmoothed / 3.0;
        double sbpSmoothed = dbpSmoothed + ppSmoothed;

        double dbpCalibrated = mapCalibrated - ppCalibrated / 3.0;
        double sbpCalibrated = dbpCalibrated + ppCalibrated;

        return new Result(
                mapRaw,
                ppRaw,
                mapSmoothed,
                ppSmoothed,
                mapCalibrated,
                ppCalibrated,
                sbpSmoothed,
                dbpSmoothed,
                sbpCalibrated,
                dbpCalibrated,
                1);
    }

    public void reset() {
        initialized = false;
        lastMapSmoothed = 0.0;
        lastPpSmoothed = 0.0;
    }

    public static double getAlphaMap() {
        return ALPHA_MAP;
    }

    public static double getAlphaPp() {
        return ALPHA_PP;
    }

    public static double[] getCalibrationCoefficients(Method method) {
        Calibration calibration = calibrationFor(method);
        return new double[] {
                calibration.mapIntercept,
                calibration.mapSlope,
                calibration.ppIntercept,
                calibration.ppSlope,
        };
    }

    private static Calibration calibrationFor(Method method) {
        switch (method) {
            case RTBP:
                return new Calibration(0.0, 1.0, 0.0, 1.0);
            case SIN_BP_D:
                return new Calibration(0.0, 1.0, 0.0, 1.0);
            case SIN_BP_M:
                return new Calibration(0.0, 1.0, 0.0, 1.0);
            default:
                throw new IllegalArgumentException("Unsupported method: " + method);
        }
    }

    private static double clamp(double value, double lower, double upper) {
        if (value < lower) {
            return lower;
        }
        if (value > upper) {
            return upper;
        }
        return value;
    }
}
