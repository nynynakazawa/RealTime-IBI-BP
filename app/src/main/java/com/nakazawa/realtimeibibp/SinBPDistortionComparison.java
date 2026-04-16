package com.nakazawa.realtimeibibp;

import com.nakazawa.realtimeibibp.bp.FeatureClampUtils;
import java.util.Locale;

/**
 * SinBP(D) の説明可能な比較枝。
 *
 * 本体の SinBP(D) は維持したまま、CSV 比較用に
 * 1. residual E only
 * 2. residual E + E^2
 * 3. amplitude source を beat-local range に置き換えた LocalA
 * を計算する。
 *
 * これらは live app の主表示系列ではなく、Training_Data.csv と
 * offline analysis での説明・比較のために残している。
 */
public final class SinBPDistortionComparison {
    public static final String METHOD_E_ONLY = "SinBP_D_EOnly";
    public static final String METHOD_E2 = "SinBP_D_E2";
    public static final String METHOD_LOCAL_A = "SinBP_D_LocalA";

    public static final String[] E_ONLY_LABELS = {
            "intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP", "E"
    };
    public static final String[] E2_LABELS = {
            "intercept", "A", "HR", "V2P_relTTP", "P2V_relTTP", "E", "E2"
    };
    public static final String[] LOCAL_A_LABELS = {
            "intercept", "A_local", "HR", "V2P_relTTP", "P2V_relTTP", "Stiffness", "E"
    };

    private static final double[] RTBP_SBP_BASE = RealtimeBP.getSbpCoefficients();
    private static final double[] RTBP_DBP_BASE = RealtimeBP.getDbpCoefficients();

    private static final double EONLY_G0 = -2.970573070691868;
    private static final double EONLY_G1 = 5.014036508465492;
    private static final double EONLY_H0 = 6.5424392803210765;
    private static final double EONLY_H1 = 2.9509714180132147;

    private static final double E2_G0 = 12.337313912062301;
    private static final double E2_G1 = -5.598260424987691;
    private static final double E2_G2 = 1.698612080360009;
    private static final double E2_H0 = 15.665638744041587;
    private static final double E2_H1 = -3.3737487606239798;
    private static final double E2_H2 = 1.012339380220683;

    private static final double LOCAL_A_G0 = 2.690652753209871;
    private static final double LOCAL_A_G1 = 2.537295192342401;
    private static final double LOCAL_A_G2 = -1.807192089982415;
    private static final double LOCAL_A_H0 = 13.061043372954417;
    private static final double LOCAL_A_H1 = 2.632014752144330;
    private static final double LOCAL_A_H2 = -4.290118500197587;

    private static final double A_SUPPORT_MIN = 1.396092;
    private static final double A_SUPPORT_MAX = 5.098340;
    private static final double HR_SUPPORT_MIN = 49.652532;
    private static final double HR_SUPPORT_MAX = 105.523184;
    private static final double V2P_SUPPORT_MIN = -0.928772;
    private static final double V2P_SUPPORT_MAX = -0.270672;
    private static final double P2V_SUPPORT_MIN = -0.859316;
    private static final double P2V_SUPPORT_MAX = -0.272260;
    private static final double E_SUPPORT_MAX = 4.810248;
    private static final double STIFFNESS_SUPPORT_MAX = 10.860692;
    private static final int MAX_ALLOWED_FEATURE_CLAMPS = 1;

    private SinBPDistortionComparison() {
    }

    public static VariantResult estimateEOnly(
            double regressionAmplitude,
            double hr,
            double valleyToPeakRelTTP,
            double peakToValleyRelTTP,
            double distortion) {
        return estimate(
                METHOD_E_ONLY,
                E_ONLY_LABELS,
                regressionAmplitude,
                hr,
                valleyToPeakRelTTP,
                peakToValleyRelTTP,
                distortion,
                false);
    }

    public static VariantResult estimateE2(
            double regressionAmplitude,
            double hr,
            double valleyToPeakRelTTP,
            double peakToValleyRelTTP,
            double distortion) {
        return estimate(
                METHOD_E2,
                E2_LABELS,
                regressionAmplitude,
                hr,
                valleyToPeakRelTTP,
                peakToValleyRelTTP,
                distortion,
                false);
    }

    public static VariantResult estimateLocalA(
            double beatLocalAmplitude,
            double hr,
            double valleyToPeakRelTTP,
            double peakToValleyRelTTP,
            double distortion) {
        return estimate(
                METHOD_LOCAL_A,
                LOCAL_A_LABELS,
                beatLocalAmplitude,
                hr,
                valleyToPeakRelTTP,
                peakToValleyRelTTP,
                distortion,
                true);
    }

    private static VariantResult estimate(
            String methodName,
            String[] labels,
            double amplitude,
            double hr,
            double valleyToPeakRelTTP,
            double peakToValleyRelTTP,
            double distortion,
            boolean useStiffness) {
        String relTtpReason = SignalProcessingUtils.getRelTtpConsistencyReason(
                valleyToPeakRelTTP, peakToValleyRelTTP);
        if (!"ok".equals(relTtpReason)) {
            return rejected(methodName, labels, relTtpReason);
        }

        StringBuilder featureClampReason = new StringBuilder();
        double usedAmplitude = FeatureClampUtils.clampFeature(
                "A", amplitude, A_SUPPORT_MIN, A_SUPPORT_MAX, featureClampReason);
        double usedHr = FeatureClampUtils.clampFeature(
                "HR", hr, HR_SUPPORT_MIN, HR_SUPPORT_MAX, featureClampReason);
        double usedV2p = FeatureClampUtils.clampFeature(
                "V2P_relTTP", valleyToPeakRelTTP, V2P_SUPPORT_MIN, V2P_SUPPORT_MAX, featureClampReason);
        double usedP2v = FeatureClampUtils.clampFeature(
                "P2V_relTTP", peakToValleyRelTTP, P2V_SUPPORT_MIN, P2V_SUPPORT_MAX, featureClampReason);
        double usedE = FeatureClampUtils.clampUpperFeature(
                "E", distortion, E_SUPPORT_MAX, featureClampReason);
        double usedStiffness = 0.0;
        if (useStiffness) {
            usedStiffness = FeatureClampUtils.clampUpperFeature(
                    "Stiffness",
                    usedE * Math.sqrt(Math.max(usedAmplitude, 0.0)),
                    STIFFNESS_SUPPORT_MAX,
                    featureClampReason);
        }
        int featureClampApplied = featureClampReason.length() > 0 ? 1 : 0;
        String featureClampText = featureClampReason.length() > 0 ? featureClampReason.toString() : "ok";
        if (FeatureClampUtils.countFeatureClampSegments(featureClampReason) > MAX_ALLOWED_FEATURE_CLAMPS) {
            return rejected(methodName, labels, "feature_support_violation", featureClampApplied, featureClampText);
        }

        double sbpBase = RTBP_SBP_BASE[0]
                + RTBP_SBP_BASE[1] * usedAmplitude
                + RTBP_SBP_BASE[2] * usedHr
                + RTBP_SBP_BASE[3] * usedV2p
                + RTBP_SBP_BASE[4] * usedP2v;
        double dbpBase = RTBP_DBP_BASE[0]
                + RTBP_DBP_BASE[1] * usedAmplitude
                + RTBP_DBP_BASE[2] * usedHr
                + RTBP_DBP_BASE[3] * usedV2p
                + RTBP_DBP_BASE[4] * usedP2v;

        double sbpCorrection;
        double dbpCorrection;
        double[] sbpCoefficients;
        double[] dbpCoefficients;
        double[] sbpTerms;
        double[] dbpTerms;

        if (METHOD_E_ONLY.equals(methodName)) {
            sbpCorrection = EONLY_G0 + EONLY_G1 * usedE;
            dbpCorrection = EONLY_H0 + EONLY_H1 * usedE;
            sbpCoefficients = new double[] {
                    RTBP_SBP_BASE[0] + EONLY_G0,
                    RTBP_SBP_BASE[1],
                    RTBP_SBP_BASE[2],
                    RTBP_SBP_BASE[3],
                    RTBP_SBP_BASE[4],
                    EONLY_G1
            };
            dbpCoefficients = new double[] {
                    RTBP_DBP_BASE[0] + EONLY_H0,
                    RTBP_DBP_BASE[1],
                    RTBP_DBP_BASE[2],
                    RTBP_DBP_BASE[3],
                    RTBP_DBP_BASE[4],
                    EONLY_H1
            };
            sbpTerms = computeLinearTerms(sbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedE
            });
            dbpTerms = computeLinearTerms(dbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedE
            });
        } else if (METHOD_E2.equals(methodName)) {
            double e2 = usedE * usedE;
            sbpCorrection = E2_G0 + E2_G1 * usedE + E2_G2 * e2;
            dbpCorrection = E2_H0 + E2_H1 * usedE + E2_H2 * e2;
            sbpCoefficients = new double[] {
                    RTBP_SBP_BASE[0] + E2_G0,
                    RTBP_SBP_BASE[1],
                    RTBP_SBP_BASE[2],
                    RTBP_SBP_BASE[3],
                    RTBP_SBP_BASE[4],
                    E2_G1,
                    E2_G2
            };
            dbpCoefficients = new double[] {
                    RTBP_DBP_BASE[0] + E2_H0,
                    RTBP_DBP_BASE[1],
                    RTBP_DBP_BASE[2],
                    RTBP_DBP_BASE[3],
                    RTBP_DBP_BASE[4],
                    E2_H1,
                    E2_H2
            };
            sbpTerms = computeLinearTerms(sbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedE, e2
            });
            dbpTerms = computeLinearTerms(dbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedE, e2
            });
        } else if (METHOD_LOCAL_A.equals(methodName)) {
            sbpCorrection = LOCAL_A_G0 + LOCAL_A_G1 * usedStiffness + LOCAL_A_G2 * usedE;
            dbpCorrection = LOCAL_A_H0 + LOCAL_A_H1 * usedStiffness + LOCAL_A_H2 * usedE;
            sbpCoefficients = new double[] {
                    RTBP_SBP_BASE[0] + LOCAL_A_G0,
                    RTBP_SBP_BASE[1],
                    RTBP_SBP_BASE[2],
                    RTBP_SBP_BASE[3],
                    RTBP_SBP_BASE[4],
                    LOCAL_A_G1,
                    LOCAL_A_G2
            };
            dbpCoefficients = new double[] {
                    RTBP_DBP_BASE[0] + LOCAL_A_H0,
                    RTBP_DBP_BASE[1],
                    RTBP_DBP_BASE[2],
                    RTBP_DBP_BASE[3],
                    RTBP_DBP_BASE[4],
                    LOCAL_A_H1,
                    LOCAL_A_H2
            };
            sbpTerms = computeLinearTerms(sbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedStiffness, usedE
            });
            dbpTerms = computeLinearTerms(dbpCoefficients, new double[] {
                    usedAmplitude, usedHr, usedV2p, usedP2v, usedStiffness, usedE
            });
        } else {
            throw new IllegalArgumentException(String.format(Locale.US, "Unknown method: %s", methodName));
        }

        double sbp = sbpBase + sbpCorrection;
        double dbp = dbpBase + dbpCorrection;
        int constraintApplied = 0;
        if (sbp < dbp + 20.0) {
            sbp = dbp + 20.0;
            constraintApplied = 1;
        }
        double constrainedSbp = sbp;
        double constrainedDbp = dbp;
        double clampedSbp = SignalProcessingUtils.clamp(sbp, 60.0, 200.0);
        double clampedDbp = SignalProcessingUtils.clamp(dbp, 40.0, 150.0);
        // Keep "raw" columns aligned with clamp-applied runtime outputs.
        double rawSbp = clampedSbp;
        double rawDbp = clampedDbp;
        int clampApplied =
                (Math.abs(clampedSbp - sbp) > 1e-9 || Math.abs(clampedDbp - dbp) > 1e-9) ? 1 : 0;

        String invalidBpReason = SignalProcessingUtils.getInvalidBPReason(clampedSbp, clampedDbp);
        if (!"ok".equals(invalidBpReason)) {
            return rejected(
                    methodName,
                    labels,
                    invalidBpReason,
                    featureClampApplied,
                    featureClampText,
                    rawSbp,
                    rawDbp,
                    sbpBase,
                    dbpBase,
                    sbpCorrection,
                    dbpCorrection,
                    constrainedSbp,
                    constrainedDbp,
                    usedAmplitude,
                    usedE,
                    usedStiffness,
                    constraintApplied,
                    clampApplied,
                    sbpCoefficients,
                    dbpCoefficients,
                    sbpTerms,
                    dbpTerms);
        }

        return new VariantResult(
                methodName,
                labels,
                clampedSbp,
                clampedDbp,
                rawSbp,
                rawDbp,
                sbpBase,
                dbpBase,
                sbpCorrection,
                dbpCorrection,
                constrainedSbp,
                constrainedDbp,
                usedAmplitude,
                usedE,
                usedStiffness,
                constraintApplied,
                clampApplied,
                featureClampApplied,
                1,
                featureClampText,
                "ok",
                sbpCoefficients,
                dbpCoefficients,
                sbpTerms,
                dbpTerms);
    }

    private static VariantResult rejected(String methodName, String[] labels, String reason) {
        return rejected(methodName, labels, reason, 0, "ok");
    }

    private static VariantResult rejected(
            String methodName,
            String[] labels,
            String reason,
            int featureClampApplied,
            String featureClampReason) {
        return rejected(
                methodName,
                labels,
                reason,
                featureClampApplied,
                featureClampReason,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0,
                0,
                new double[labels.length],
                new double[labels.length],
                new double[labels.length],
                new double[labels.length]);
    }

    private static VariantResult rejected(
            String methodName,
            String[] labels,
            String reason,
            int featureClampApplied,
            String featureClampReason,
            double rawSbp,
            double rawDbp,
            double sbpBase,
            double dbpBase,
            double sbpCorrection,
            double dbpCorrection,
            double constrainedSbp,
            double constrainedDbp,
            double usedAmplitude,
            double usedE,
            double usedStiffness,
            int constraintApplied,
            int clampApplied,
            double[] sbpCoefficients,
            double[] dbpCoefficients,
            double[] sbpTerms,
            double[] dbpTerms) {
        return new VariantResult(
                methodName,
                labels,
                0.0,
                0.0,
                rawSbp,
                rawDbp,
                sbpBase,
                dbpBase,
                sbpCorrection,
                dbpCorrection,
                constrainedSbp,
                constrainedDbp,
                usedAmplitude,
                usedE,
                usedStiffness,
                constraintApplied,
                clampApplied,
                featureClampApplied,
                0,
                featureClampReason,
                reason,
                sbpCoefficients,
                dbpCoefficients,
                sbpTerms,
                dbpTerms);
    }

    private static double[] computeLinearTerms(double[] coefficients, double[] features) {
        double[] terms = new double[coefficients.length];
        terms[0] = coefficients[0];
        for (int i = 1; i < coefficients.length && i - 1 < features.length; i++) {
            terms[i] = coefficients[i] * features[i - 1];
        }
        return terms;
    }

    public static final class VariantResult {
        public final String methodName;
        public final String[] featureLabels;
        public final double sbp;
        public final double dbp;
        public final double rawSbp;
        public final double rawDbp;
        public final double baseSbp;
        public final double baseDbp;
        public final double sbpCorrection;
        public final double dbpCorrection;
        public final double constrainedSbp;
        public final double constrainedDbp;
        public final double amplitudeUsed;
        public final double distortionUsed;
        public final double stiffnessUsed;
        public final int constraintApplied;
        public final int clampApplied;
        public final int featureClampApplied;
        public final int outputValid;
        public final String featureClampReason;
        public final String rejectReason;
        public final double[] sbpCoefficients;
        public final double[] dbpCoefficients;
        public final double[] sbpTerms;
        public final double[] dbpTerms;

        private VariantResult(
                String methodName,
                String[] featureLabels,
                double sbp,
                double dbp,
                double rawSbp,
                double rawDbp,
                double baseSbp,
                double baseDbp,
                double sbpCorrection,
                double dbpCorrection,
                double constrainedSbp,
                double constrainedDbp,
                double amplitudeUsed,
                double distortionUsed,
                double stiffnessUsed,
                int constraintApplied,
                int clampApplied,
                int featureClampApplied,
                int outputValid,
                String featureClampReason,
                String rejectReason,
                double[] sbpCoefficients,
                double[] dbpCoefficients,
                double[] sbpTerms,
                double[] dbpTerms) {
            this.methodName = methodName;
            this.featureLabels = featureLabels;
            this.sbp = sbp;
            this.dbp = dbp;
            this.rawSbp = rawSbp;
            this.rawDbp = rawDbp;
            this.baseSbp = baseSbp;
            this.baseDbp = baseDbp;
            this.sbpCorrection = sbpCorrection;
            this.dbpCorrection = dbpCorrection;
            this.constrainedSbp = constrainedSbp;
            this.constrainedDbp = constrainedDbp;
            this.amplitudeUsed = amplitudeUsed;
            this.distortionUsed = distortionUsed;
            this.stiffnessUsed = stiffnessUsed;
            this.constraintApplied = constraintApplied;
            this.clampApplied = clampApplied;
            this.featureClampApplied = featureClampApplied;
            this.outputValid = outputValid;
            this.featureClampReason = featureClampReason;
            this.rejectReason = rejectReason;
            this.sbpCoefficients = sbpCoefficients;
            this.dbpCoefficients = dbpCoefficients;
            this.sbpTerms = sbpTerms;
            this.dbpTerms = dbpTerms;
        }
    }
}
