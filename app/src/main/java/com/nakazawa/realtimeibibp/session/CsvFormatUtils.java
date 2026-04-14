package com.nakazawa.realtimeibibp.session;

import com.nakazawa.realtimeibibp.BPPostProcessor;
import com.nakazawa.realtimeibibp.SinBPDistortionComparison;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CsvFormatUtils {
    private CsvFormatUtils() {
    }

    public static String formatCoefficients(double[] coefficients) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < coefficients.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.US, "%.10f", coefficients[i]));
        }
        return builder.toString();
    }

    public static String formatValues(double[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.US, "%.10f", values[i]));
        }
        return builder.toString();
    }

    public static String formatValuesSemicolon(double[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(";");
            }
            builder.append(String.format(Locale.US, "%.10f", values[i]));
        }
        return builder.toString();
    }

    public static void appendVariantHeader(StringBuilder csvContent, String prefix, String[] labels) {
        List<String> columns = new ArrayList<>();
        columns.add(prefix + "_SBP");
        columns.add(prefix + "_DBP");
        columns.add(prefix + "_SBP_raw");
        columns.add(prefix + "_DBP_raw");
        columns.add(prefix + "_SBP_base");
        columns.add(prefix + "_DBP_base");
        columns.add(prefix + "_SBP_correction");
        columns.add(prefix + "_DBP_correction");
        columns.add(prefix + "_A_used");
        columns.add(prefix + "_E_used");
        columns.add(prefix + "_Stiffness_used");
        columns.add(prefix + "_constraint_applied");
        columns.add(prefix + "_clamp_applied");
        columns.add(prefix + "_feature_clamp_applied");
        columns.add(prefix + "_output_valid");
        columns.add(prefix + "_feature_clamp_reason");
        columns.add(prefix + "_reject_reason");
        columns.add(prefix + "_MAP_raw");
        columns.add(prefix + "_PP_raw");
        columns.add(prefix + "_MAP_smoothed");
        columns.add(prefix + "_PP_smoothed");
        columns.add(prefix + "_MAP_calibrated");
        columns.add(prefix + "_PP_calibrated");
        columns.add(prefix + "_SBP_smoothed");
        columns.add(prefix + "_DBP_smoothed");
        columns.add(prefix + "_SBP_calibrated");
        columns.add(prefix + "_DBP_calibrated");
        columns.add(prefix + "_postprocess_applied");
        columns.add(prefix + "_POST_map_a");
        columns.add(prefix + "_POST_map_b");
        columns.add(prefix + "_POST_pp_a");
        columns.add(prefix + "_POST_pp_b");
        columns.add(prefix + "_POST_alpha_map");
        columns.add(prefix + "_POST_alpha_pp");
        for (String label : labels) {
            columns.add(prefix + "_SBP_coef_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_DBP_coef_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_SBP_term_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_DBP_term_" + label);
        }
        csvContent.append(String.join(", ", columns));
    }

    public static void appendVariantValues(
            StringBuilder csvContent,
            SinBPDistortionComparison.VariantResult variant,
            BPPostProcessor.Result postResult,
            double[] postCoefficients) {
        List<String> values = new ArrayList<>();
        values.add(String.format(Locale.getDefault(), "%.2f", variant.sbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.dbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.rawSbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.rawDbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.baseSbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.baseDbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.sbpCorrection));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.dbpCorrection));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.amplitudeUsed));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.distortionUsed));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.stiffnessUsed));
        values.add(String.valueOf(variant.constraintApplied));
        values.add(String.valueOf(variant.clampApplied));
        values.add(String.valueOf(variant.featureClampApplied));
        values.add(String.valueOf(variant.outputValid));
        values.add(sanitizeCsvText(normalizeRejectReason(variant.featureClampReason)));
        values.add(sanitizeCsvText(normalizeRejectReason(variant.rejectReason)));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.mapRaw));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.ppRaw));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.mapSmoothed));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.ppSmoothed));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.mapCalibrated));
        values.add(String.format(Locale.getDefault(), "%.4f", postResult.ppCalibrated));
        values.add(String.format(Locale.getDefault(), "%.2f", postResult.sbpSmoothed));
        values.add(String.format(Locale.getDefault(), "%.2f", postResult.dbpSmoothed));
        values.add(String.format(Locale.getDefault(), "%.2f", postResult.sbpCalibrated));
        values.add(String.format(Locale.getDefault(), "%.2f", postResult.dbpCalibrated));
        values.add(String.valueOf(postResult.postprocessApplied));
        values.add(String.format(Locale.getDefault(), "%.6f", postCoefficients[0]));
        values.add(String.format(Locale.getDefault(), "%.6f", postCoefficients[1]));
        values.add(String.format(Locale.getDefault(), "%.6f", postCoefficients[2]));
        values.add(String.format(Locale.getDefault(), "%.6f", postCoefficients[3]));
        values.add(String.format(Locale.getDefault(), "%.6f", BPPostProcessor.getAlphaMap()));
        values.add(String.format(Locale.getDefault(), "%.6f", BPPostProcessor.getAlphaPp()));
        values.add(formatCoefficients(variant.sbpCoefficients));
        values.add(formatCoefficients(variant.dbpCoefficients));
        values.add(formatValues(variant.sbpTerms));
        values.add(formatValues(variant.dbpTerms));
        csvContent.append(String.join(", ", values));
    }

    public static BPPostProcessor.Result buildVariantPostprocessResult(
            BPPostProcessor processor,
            SinBPDistortionComparison.VariantResult variant) {
        if (processor == null || variant == null) {
            return BPPostProcessor.Result.empty();
        }
        String rejectReason = normalizeRejectReason(variant.rejectReason);
        if (variant.outputValid != 1 || !"ok".equals(rejectReason)) {
            return BPPostProcessor.Result.empty();
        }
        if (!Double.isFinite(variant.sbp) || !Double.isFinite(variant.dbp) || variant.sbp <= 0.0 || variant.dbp <= 0.0) {
            return BPPostProcessor.Result.empty();
        }
        return processor.apply(variant.sbp, variant.dbp);
    }

    public static double[] computeLinearTerms(double intercept, double[] coefficients, double[] features) {
        double[] terms = new double[coefficients.length + 1];
        terms[0] = intercept;
        for (int i = 0; i < coefficients.length && i < features.length; i++) {
            terms[i + 1] = coefficients[i] * features[i];
        }
        return terms;
    }

    public static String normalizeRejectReason(String reason) {
        if (reason == null) {
            return "missing";
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? "ok" : trimmed;
    }

    public static String sanitizeCsvText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(",", "_").replace("\n", "_").replace("\r", "_");
    }
}
