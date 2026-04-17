package com.nakazawa.realtimeibibp.bp;

/**
 * Live app runtime coefficients.
 *
 * This class intentionally contains only the app-side mainline models:
 * RTBP / SinBP_D / SinBP_M.
 */
public final class RealtimeMapPpModels {
    private static final String[] RTBP_LABELS = {"intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP"};
    private static final String[] SINBPD_LABELS = {"intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP", "Stiffness", "E"};
    private static final String[] SINBPM_LABELS = {"intercept", "A", "HR", "Mean", "sinPhi", "cosPhi"};

    private static final double[] RTBP_MAP = {
            98.08688269140994,
            0.002965635095376108,
            0.00032353116840502143,
            0.04987753444398114,
            -0.11836984277992194
    };
    private static final double[] RTBP_PP = {
            29.274858157485745,
            0.053067232129562936,
            -0.018849409364261306,
            14.181843619640842,
            -31.013481286966044
    };

    private static final double[] SINBPD_RESIDUAL_MAP = {
            0.0018108361514461314,
            0.004785206984238616,
            -0.008775274758135023
    };
    private static final double[] SINBPD_RESIDUAL_PP = {
            3.7641981371729827,
            2.7863673071152406,
            -7.537033293641209
    };

    private static final double[] SINBPM_MAP = {
            98.0927217680995,
            0.007178979670796855,
            -0.000028845998853111546,
            0.010336498127541083,
            -0.010449744520172211,
            -0.056991738552018334
    };
    private static final double[] SINBPM_PP = {
            31.876001152054585,
            -0.48797460623521965,
            -0.08521598306987167,
            1.867867139138918,
            1.0644448324112812,
            -13.556532472597219
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
            double stiffness,
            double e) {
        double[] baseFeatures = {amplitude, hr, v2pRelTtp, p2vRelTtp};
        double[] residualFeatures = {stiffness, e};

        double[] mapCoefficients = new double[SINBPD_LABELS.length];
        double[] ppCoefficients = new double[SINBPD_LABELS.length];
        mapCoefficients[0] = RTBP_MAP[0] + SINBPD_RESIDUAL_MAP[0];
        ppCoefficients[0] = RTBP_PP[0] + SINBPD_RESIDUAL_PP[0];
        System.arraycopy(RTBP_MAP, 1, mapCoefficients, 1, RTBP_MAP.length - 1);
        System.arraycopy(RTBP_PP, 1, ppCoefficients, 1, RTBP_PP.length - 1);
        mapCoefficients[5] = SINBPD_RESIDUAL_MAP[1];
        mapCoefficients[6] = SINBPD_RESIDUAL_MAP[2];
        ppCoefficients[5] = SINBPD_RESIDUAL_PP[1];
        ppCoefficients[6] = SINBPD_RESIDUAL_PP[2];

        double[] mapTerms = new double[SINBPD_LABELS.length];
        double[] ppTerms = new double[SINBPD_LABELS.length];
        mapTerms[0] = mapCoefficients[0];
        ppTerms[0] = ppCoefficients[0];
        for (int i = 0; i < baseFeatures.length; i++) {
            mapTerms[i + 1] = mapCoefficients[i + 1] * baseFeatures[i];
            ppTerms[i + 1] = ppCoefficients[i + 1] * baseFeatures[i];
        }
        mapTerms[5] = mapCoefficients[5] * residualFeatures[0];
        mapTerms[6] = mapCoefficients[6] * residualFeatures[1];
        ppTerms[5] = ppCoefficients[5] * residualFeatures[0];
        ppTerms[6] = ppCoefficients[6] * residualFeatures[1];
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
        coefficients[6] = SINBPD_RESIDUAL_MAP[2];
        return coefficients;
    }

    public static double[] getSinBpDCombinedPpCoefficients() {
        double[] coefficients = new double[SINBPD_LABELS.length];
        coefficients[0] = RTBP_PP[0] + SINBPD_RESIDUAL_PP[0];
        System.arraycopy(RTBP_PP, 1, coefficients, 1, RTBP_PP.length - 1);
        coefficients[5] = SINBPD_RESIDUAL_PP[1];
        coefficients[6] = SINBPD_RESIDUAL_PP[2];
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
