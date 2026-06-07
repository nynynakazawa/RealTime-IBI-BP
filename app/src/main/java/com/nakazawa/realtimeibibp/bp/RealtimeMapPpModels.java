package com.nakazawa.realtimeibibp.bp;

/**
 * Live app runtime coefficients.
 *
 * This class intentionally contains only the app-side mainline models:
 * RTBP / SinBP_D / SinBP_M.
 */
public final class RealtimeMapPpModels {
    private static final String[] RTBP_LABELS = {"intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP"};
    private static final String[] SINBPD_LABELS = {"intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP", "E"};
    private static final String[] SINBPM_LABELS = {"intercept", "A", "HR", "Mean", "sinPhi", "cosPhi"};

    private static final double[] RTBP_MAP = {
            98.17933671736458,
            0.00517585270648243,
            -0.0011788672226907752,
            0.04921591953183601,
            -0.12096408757505715
    };
    private static final double[] RTBP_PP = {
            36.93711538452443,
            1.7028474361413386,
            -0.37065794721668943,
            8.653490892925587,
            -33.7685987171883
    };

    private static final double[] SINBPD_RESIDUAL_MAP = {
            -0.0019547906819300243,
            0.006434048607158859
    };
    private static final double[] SINBPD_RESIDUAL_PP = {
            0.43101704424653153,
            1.8230015472167056
    };

    private static final double[] SINBPM_MAP = {
            98.11484641408586,
            0.019010642113272858,
            -0.0011211410980227579,
            0.014776920392505458,
            -0.025498238054972498,
            -0.05445725490886895
    };
    private static final double[] SINBPM_PP = {
            21.41909245667339,
            5.991918366443132,
            -0.2912741893542163,
            3.3541096069693386,
            -9.002646049541775,
            -11.165543654161441
    };

    private RealtimeMapPpModels() {
    }

    public static MapPpPrediction predictRtbp(double amplitude, double hr, double v2pRelTtp, double p2vRelTtp) {
        double[] features = {amplitude, hr, v2pRelTtp, p2vRelTtp};
        return buildPrediction(RTBP_LABELS, RTBP_MAP, RTBP_PP, features, false);
    }

    public static MapPpPrediction predictSinBpD(
            double amplitude,
            double hr,
            double v2pRelTtp,
            double p2vRelTtp,
            double e) {
        double[] baseFeatures = {amplitude, hr, v2pRelTtp, p2vRelTtp};
        double[] residualFeatures = {e};

        double[] mapCoefficients = new double[SINBPD_LABELS.length];
        double[] ppCoefficients = new double[SINBPD_LABELS.length];
        mapCoefficients[0] = RTBP_MAP[0] + SINBPD_RESIDUAL_MAP[0];
        ppCoefficients[0] = RTBP_PP[0] + SINBPD_RESIDUAL_PP[0];
        System.arraycopy(RTBP_MAP, 1, mapCoefficients, 1, RTBP_MAP.length - 1);
        System.arraycopy(RTBP_PP, 1, ppCoefficients, 1, RTBP_PP.length - 1);
        mapCoefficients[5] = SINBPD_RESIDUAL_MAP[1];
        ppCoefficients[5] = SINBPD_RESIDUAL_PP[1];

        double[] mapTerms = new double[SINBPD_LABELS.length];
        double[] ppTerms = new double[SINBPD_LABELS.length];
        mapTerms[0] = mapCoefficients[0];
        ppTerms[0] = ppCoefficients[0];
        for (int i = 0; i < baseFeatures.length; i++) {
            mapTerms[i + 1] = mapCoefficients[i + 1] * baseFeatures[i];
            ppTerms[i + 1] = ppCoefficients[i + 1] * baseFeatures[i];
        }
        mapTerms[5] = mapCoefficients[5] * residualFeatures[0];
        ppTerms[5] = ppCoefficients[5] * residualFeatures[0];
        return buildPrediction(SINBPD_LABELS, mapCoefficients, ppCoefficients, mapTerms, ppTerms, true);
    }

    public static MapPpPrediction predictSinBpM(
            double amplitude,
            double hr,
            double mean,
            double sinPhi,
            double cosPhi) {
        double[] features = {amplitude, hr, mean, sinPhi, cosPhi};
        return buildPrediction(SINBPM_LABELS, SINBPM_MAP, SINBPM_PP, features, true);
    }

    public static double[] getRtbpMapCoefficients() {
        return RTBP_MAP.clone();
    }

    public static double[] getRtbpPpCoefficients() {
        return RTBP_PP.clone();
    }

    public static double[] getRtbpSbpCoefficients() {
        return mapPpToSbp(RTBP_MAP, RTBP_PP);
    }

    public static double[] getRtbpDbpCoefficients() {
        return mapPpToDbp(RTBP_MAP, RTBP_PP);
    }

    public static double[] getSinBpDCombinedMapCoefficients() {
        double[] coefficients = new double[SINBPD_LABELS.length];
        coefficients[0] = RTBP_MAP[0] + SINBPD_RESIDUAL_MAP[0];
        System.arraycopy(RTBP_MAP, 1, coefficients, 1, RTBP_MAP.length - 1);
        coefficients[5] = SINBPD_RESIDUAL_MAP[1];
        return coefficients;
    }

    public static double[] getSinBpDCombinedPpCoefficients() {
        double[] coefficients = new double[SINBPD_LABELS.length];
        coefficients[0] = RTBP_PP[0] + SINBPD_RESIDUAL_PP[0];
        System.arraycopy(RTBP_PP, 1, coefficients, 1, RTBP_PP.length - 1);
        coefficients[5] = SINBPD_RESIDUAL_PP[1];
        return coefficients;
    }

    public static double[] getSinBpDCombinedSbpCoefficients() {
        return mapPpToSbp(getSinBpDCombinedMapCoefficients(), getSinBpDCombinedPpCoefficients());
    }

    public static double[] getSinBpDCombinedDbpCoefficients() {
        return mapPpToDbp(getSinBpDCombinedMapCoefficients(), getSinBpDCombinedPpCoefficients());
    }

    public static double[] getSinBpDResidualMapCoefficients() {
        return SINBPD_RESIDUAL_MAP.clone();
    }

    public static double[] getSinBpDResidualPpCoefficients() {
        return SINBPD_RESIDUAL_PP.clone();
    }

    public static double[] getSinBpMMapCoefficients() {
        return SINBPM_MAP.clone();
    }

    public static double[] getSinBpMPpCoefficients() {
        return SINBPM_PP.clone();
    }

    public static double[] getSinBpMSbpCoefficients() {
        return mapPpToSbp(SINBPM_MAP, SINBPM_PP);
    }

    public static double[] getSinBpMDbpCoefficients() {
        return mapPpToDbp(SINBPM_MAP, SINBPM_PP);
    }

    private static MapPpPrediction buildPrediction(
            String[] labels,
            double[] mapCoefficients,
            double[] ppCoefficients,
            double[] features,
            boolean applyMinimumPulsePressure) {
        double[] mapTerms = linearTerms(labels.length, mapCoefficients, features);
        double[] ppTerms = linearTerms(labels.length, ppCoefficients, features);
        return buildPrediction(labels, mapCoefficients, ppCoefficients, mapTerms, ppTerms, applyMinimumPulsePressure);
    }

    private static MapPpPrediction buildPrediction(
            String[] labels,
            double[] mapCoefficients,
            double[] ppCoefficients,
            double[] mapTerms,
            double[] ppTerms,
            boolean applyMinimumPulsePressure) {
        double mapModelRaw = sum(mapTerms);
        double ppModelRaw = sum(ppTerms);
        double dbpModelRaw = mapModelRaw - ppModelRaw / 3.0;
        double sbpModelRaw = dbpModelRaw + ppModelRaw;
        double sbpFinal = sbpModelRaw;
        double dbpFinal = dbpModelRaw;
        if (applyMinimumPulsePressure && sbpFinal < dbpFinal + 20.0) {
            sbpFinal = dbpFinal + 20.0;
        }
        sbpFinal = clamp(sbpFinal, 60.0, 200.0);
        dbpFinal = clamp(dbpFinal, 40.0, 150.0);
        double[] sbpCoefficients = mapPpToSbp(mapCoefficients, ppCoefficients);
        double[] dbpCoefficients = mapPpToDbp(mapCoefficients, ppCoefficients);
        double[] sbpTerms = mapPpToSbp(mapTerms, ppTerms);
        double[] dbpTerms = mapPpToDbp(mapTerms, ppTerms);
        return new MapPpPrediction(
                labels.clone(),
                mapCoefficients.clone(),
                ppCoefficients.clone(),
                sbpCoefficients,
                dbpCoefficients,
                mapTerms.clone(),
                ppTerms.clone(),
                sbpTerms,
                dbpTerms,
                mapModelRaw,
                ppModelRaw,
                sbpModelRaw,
                dbpModelRaw,
                sbpFinal,
                dbpFinal);
    }

    private static double[] linearTerms(int length, double[] coefficients, double[] features) {
        double[] terms = new double[length];
        if (coefficients.length == 0) {
            return terms;
        }
        terms[0] = coefficients[0];
        int usable = Math.min(length - 1, Math.min(features.length, coefficients.length - 1));
        for (int i = 0; i < usable; i++) {
            terms[i + 1] = coefficients[i + 1] * features[i];
        }
        return terms;
    }

    private static double[] mapPpToSbp(double[] mapValues, double[] ppValues) {
        double[] converted = new double[Math.min(mapValues.length, ppValues.length)];
        for (int i = 0; i < converted.length; i++) {
            converted[i] = mapValues[i] + (2.0 / 3.0) * ppValues[i];
        }
        return converted;
    }

    private static double[] mapPpToDbp(double[] mapValues, double[] ppValues) {
        double[] converted = new double[Math.min(mapValues.length, ppValues.length)];
        for (int i = 0; i < converted.length; i++) {
            converted[i] = mapValues[i] - (1.0 / 3.0) * ppValues[i];
        }
        return converted;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total;
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
