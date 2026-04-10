package com.nakazawa.realtimeibibp.session;

import android.content.Context;
import com.nakazawa.realtimeibibp.R;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Replays smartphone-only MAP/PP baseline candidates into the training CSV.
 *
 * The fitted coefficients were trained offline with CNAP as labels, but runtime
 * inputs are smartphone features only. This keeps the app auditable without
 * changing the existing RTBP / SinBP(D) / SinBP(M) display paths.
 */
public final class RealtimeBaselineReplay {
    private static final String[] METHODS = {"RTBP", "SinBP_D", "SinBP_M"};
    private static final String[] METHOD_PREFIXES = {"M1", "M2", "M3"};
    private static final String[] SERIES = {
            "INITIAL_BASELINE",
            "RICH_BASELINE",
            "SHARED_D_BASELINE",
            "RICH_DYNAMIC",
    };
    private static final String[] SERIES_JSON_KEYS = {
            "experimental_smartphone_initial_baseline",
            "experimental_smartphone_rich_baseline",
            "experimental_smartphone_shared_sinbpd_baseline",
            "experimental_smartphone_rich_dynamic_blend",
    };

    // 0.5 followed motion more strongly offline, but 0.25 was the safer first app default.
    private static final double DEFAULT_DYNAMIC_GAIN_MAP = 0.25;
    private static final double DEFAULT_DYNAMIC_GAIN_PP = 0.25;
    private static final double ALPHA_MAP = 0.30;
    private static final double ALPHA_PP = 0.50;
    private static final double EMPTY = 0.0;

    private RealtimeBaselineReplay() {
    }

    public static final class Row {
        private final Map<String, Double> values = new HashMap<>();
        private final Map<String, Integer> outputValid = new HashMap<>();
        private final Map<String, String> rejectReasons = new HashMap<>();

        public Row put(String column, double value) {
            values.put(column, Double.isFinite(value) ? value : 0.0);
            return this;
        }

        public double get(String column) {
            Double value = values.get(column);
            return value != null && Double.isFinite(value) ? value : 0.0;
        }

        public Row setStatus(String method, int valid, String rejectReason) {
            outputValid.put(method, valid);
            rejectReasons.put(method, CsvFormatUtils.normalizeRejectReason(rejectReason));
            return this;
        }

        public boolean isValid(String method) {
            Integer valid = outputValid.get(method);
            String reason = rejectReasons.get(method);
            return valid != null && valid == 1 && "ok".equals(reason);
        }

        public String rejectReason(String method) {
            String reason = rejectReasons.get(method);
            return reason == null ? "missing" : reason;
        }
    }

    public static final class ResultSet {
        private final int size;
        private final Map<String, List<Result>> results = new HashMap<>();

        ResultSet(int size) {
            this.size = size;
        }

        void put(String series, String method, List<Result> seriesResults) {
            results.put(key(series, method), seriesResults);
        }

        Result get(String series, String method, int index) {
            List<Result> seriesResults = results.get(key(series, method));
            if (seriesResults == null || index < 0 || index >= seriesResults.size()) {
                return Result.empty();
            }
            Result result = seriesResults.get(index);
            return result == null ? Result.empty() : result;
        }

        private static String key(String series, String method) {
            return series + ":" + method;
        }
    }

    static final class Result {
        final double sbp;
        final double dbp;
        final double map;
        final double pp;
        final double mapRaw;
        final double ppRaw;
        final double baselineMapRaw;
        final double baselinePpRaw;
        final double baselineMap;
        final double baselinePp;
        final double deltaMap;
        final double deltaPp;
        final double richMap;
        final double richPp;
        final double dynamicMap;
        final double dynamicPp;
        final double dynamicDeltaMap;
        final double dynamicDeltaPp;
        final double dynamicGainMap;
        final double dynamicGainPp;
        final int initialBaselineBeats;
        final double baselineShrinkage;
        final int outputValid;
        final String rejectReason;
        final AdaptiveModel model;
        final double[] baselineMapTerms;
        final double[] baselinePpTerms;
        final double[] deltaMapTerms;
        final double[] deltaPpTerms;
        final double[] dynamicMapTerms;
        final double[] dynamicPpTerms;

        Result(
                double mapRaw,
                double ppRaw,
                double map,
                double pp,
                double baselineMapRaw,
                double baselinePpRaw,
                double baselineMap,
                double baselinePp,
                double deltaMap,
                double deltaPp,
                double richMap,
                double richPp,
                double dynamicMap,
                double dynamicPp,
                double dynamicDeltaMap,
                double dynamicDeltaPp,
                double dynamicGainMap,
                double dynamicGainPp,
                int initialBaselineBeats,
                double baselineShrinkage,
                int outputValid,
                String rejectReason,
                AdaptiveModel model,
                double[] baselineMapTerms,
                double[] baselinePpTerms,
                double[] deltaMapTerms,
                double[] deltaPpTerms,
                double[] dynamicMapTerms,
                double[] dynamicPpTerms) {
            this.mapRaw = mapRaw;
            this.ppRaw = ppRaw;
            this.map = map;
            this.pp = pp;
            this.dbp = map - pp / 3.0;
            this.sbp = this.dbp + pp;
            this.baselineMapRaw = baselineMapRaw;
            this.baselinePpRaw = baselinePpRaw;
            this.baselineMap = baselineMap;
            this.baselinePp = baselinePp;
            this.deltaMap = deltaMap;
            this.deltaPp = deltaPp;
            this.richMap = richMap;
            this.richPp = richPp;
            this.dynamicMap = dynamicMap;
            this.dynamicPp = dynamicPp;
            this.dynamicDeltaMap = dynamicDeltaMap;
            this.dynamicDeltaPp = dynamicDeltaPp;
            this.dynamicGainMap = dynamicGainMap;
            this.dynamicGainPp = dynamicGainPp;
            this.initialBaselineBeats = initialBaselineBeats;
            this.baselineShrinkage = baselineShrinkage;
            this.outputValid = outputValid;
            this.rejectReason = rejectReason;
            this.model = model;
            this.baselineMapTerms = baselineMapTerms;
            this.baselinePpTerms = baselinePpTerms;
            this.deltaMapTerms = deltaMapTerms;
            this.deltaPpTerms = deltaPpTerms;
            this.dynamicMapTerms = dynamicMapTerms;
            this.dynamicPpTerms = dynamicPpTerms;
        }

        static Result empty() {
            return new Result(
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    0,
                    EMPTY,
                    0,
                    "missing",
                    null,
                    new double[0],
                    new double[0],
                    new double[0],
                    new double[0],
                    new double[0],
                    new double[0]);
        }
    }

    private static final class Config {
        final Map<String, AdaptiveModel> adaptiveModels = new HashMap<>();
        final Map<String, DynamicModel> dynamicModels = new HashMap<>();
    }

    private static final class AdaptiveModel {
        final String method;
        final String[] featureNames;
        final String[] summarySourceColumns;
        final int initialBaselineBeats;
        final double baselineShrinkage;
        final double populationMapAnchor;
        final double populationPpAnchor;
        final double[] baselineMap;
        final double[] baselinePp;
        final double[] deltaMap;
        final double[] deltaPp;
        final boolean columnMajorSummary;

        AdaptiveModel(JSONObject json, String method) throws JSONException {
            this.method = method;
            this.featureNames = readStringArray(json.getJSONArray("feature_names"));
            this.summarySourceColumns = readStringArray(json.getJSONArray("summary_source_columns"));
            this.initialBaselineBeats = json.optInt("initial_baseline_beats", 30);
            this.baselineShrinkage = json.optDouble("baseline_shrinkage", 1.0);
            this.populationMapAnchor = json.optDouble("population_MAP_anchor", 0.0);
            this.populationPpAnchor = json.optDouble("population_PP_anchor", 0.0);
            this.baselineMap = readDoubleArray(json.getJSONArray("baseline_MAP"));
            this.baselinePp = readDoubleArray(json.getJSONArray("baseline_PP"));
            this.deltaMap = readDoubleArray(json.getJSONArray("delta_MAP"));
            this.deltaPp = readDoubleArray(json.getJSONArray("delta_PP"));
            this.columnMajorSummary = baselineMap.length == summarySourceColumns.length * 5 + 1;
        }
    }

    private static final class DynamicModel {
        final String[] features;
        final double[] map;
        final double[] pp;
        final String[] residualFeatures;
        final double[] residualMap;
        final double[] residualPp;

        DynamicModel(String[] features, double[] map, double[] pp) {
            this(features, map, pp, new String[0], new double[0], new double[0]);
        }

        DynamicModel(
                String[] features,
                double[] map,
                double[] pp,
                String[] residualFeatures,
                double[] residualMap,
                double[] residualPp) {
            this.features = features;
            this.map = map;
            this.pp = pp;
            this.residualFeatures = residualFeatures;
            this.residualMap = residualMap;
            this.residualPp = residualPp;
        }
    }

    private static final class Summary {
        final double[] summaryFeatures;
        final double[] anchorFeatures;
        final int n;

        Summary(double[] summaryFeatures, double[] anchorFeatures, int n) {
            this.summaryFeatures = summaryFeatures;
            this.anchorFeatures = anchorFeatures;
            this.n = n;
        }
    }

    private static final class RawDynamic {
        final double map;
        final double pp;
        final double[] mapTerms;
        final double[] ppTerms;

        RawDynamic(double map, double pp, double[] mapTerms, double[] ppTerms) {
            this.map = map;
            this.pp = pp;
            this.mapTerms = mapTerms;
            this.ppTerms = ppTerms;
        }
    }

    public static ResultSet compute(Context context, List<Row> rows) {
        ResultSet resultSet = new ResultSet(rows.size());
        Config config;
        try {
            config = loadConfig(context);
        } catch (Exception e) {
            for (String method : METHODS) {
                for (String series : SERIES) {
                    resultSet.put(series, method, emptySeries(rows.size()));
                }
            }
            return resultSet;
        }

        for (int i = 0; i < SERIES.length - 1; i++) {
            String series = SERIES[i];
            String jsonKey = SERIES_JSON_KEYS[i];
            for (String method : METHODS) {
                resultSet.put(series, method, computeAdaptiveSeries(rows, config.adaptiveModels.get(jsonKey + ":" + method), method));
            }
        }

        for (String method : METHODS) {
            resultSet.put("RICH_DYNAMIC", method, computeRichDynamicSeries(rows, config, resultSet, method));
        }
        return resultSet;
    }

    public static void appendHeader(StringBuilder csvContent) {
        List<String> columns = new ArrayList<>();
        for (int methodIndex = 0; methodIndex < METHODS.length; methodIndex++) {
            String prefix = METHOD_PREFIXES[methodIndex];
            for (String series : SERIES) {
                String base = prefix + "_" + series;
                columns.add(base + "_SBP");
                columns.add(base + "_DBP");
                columns.add(base + "_MAP");
                columns.add(base + "_PP");
                columns.add(base + "_MAP_raw");
                columns.add(base + "_PP_raw");
                columns.add(base + "_baseline_MAP_raw");
                columns.add(base + "_baseline_PP_raw");
                columns.add(base + "_baseline_MAP");
                columns.add(base + "_baseline_PP");
                columns.add(base + "_delta_MAP");
                columns.add(base + "_delta_PP");
                columns.add(base + "_rich_MAP");
                columns.add(base + "_rich_PP");
                columns.add(base + "_dynamic_MAP");
                columns.add(base + "_dynamic_PP");
                columns.add(base + "_dynamic_delta_MAP");
                columns.add(base + "_dynamic_delta_PP");
                columns.add(base + "_dynamic_gain_MAP");
                columns.add(base + "_dynamic_gain_PP");
                columns.add(base + "_initial_baseline_beats");
                columns.add(base + "_baseline_shrinkage");
                columns.add(base + "_output_valid");
                columns.add(base + "_reject_reason");
                columns.add(base + "_feature_names");
                columns.add(base + "_summary_source_columns");
                columns.add(base + "_baseline_MAP_coefficients");
                columns.add(base + "_baseline_PP_coefficients");
                columns.add(base + "_delta_MAP_coefficients");
                columns.add(base + "_delta_PP_coefficients");
                columns.add(base + "_baseline_MAP_terms");
                columns.add(base + "_baseline_PP_terms");
                columns.add(base + "_delta_MAP_terms");
                columns.add(base + "_delta_PP_terms");
                columns.add(base + "_dynamic_MAP_terms");
                columns.add(base + "_dynamic_PP_terms");
            }
        }
        csvContent.append(String.join(", ", columns));
    }

    public static void appendValues(StringBuilder csvContent, ResultSet resultSet, int rowIndex) {
        List<String> values = new ArrayList<>();
        for (String method : METHODS) {
            for (String series : SERIES) {
                Result result = resultSet.get(series, method, rowIndex);
                values.add(String.format(Locale.getDefault(), "%.2f", result.sbp));
                values.add(String.format(Locale.getDefault(), "%.2f", result.dbp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.map));
                values.add(String.format(Locale.getDefault(), "%.4f", result.pp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.mapRaw));
                values.add(String.format(Locale.getDefault(), "%.4f", result.ppRaw));
                values.add(String.format(Locale.getDefault(), "%.4f", result.baselineMapRaw));
                values.add(String.format(Locale.getDefault(), "%.4f", result.baselinePpRaw));
                values.add(String.format(Locale.getDefault(), "%.4f", result.baselineMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.baselinePp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.deltaMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.deltaPp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.richMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.richPp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicPp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicDeltaMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicDeltaPp));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicGainMap));
                values.add(String.format(Locale.getDefault(), "%.4f", result.dynamicGainPp));
                values.add(String.valueOf(result.initialBaselineBeats));
                values.add(String.format(Locale.getDefault(), "%.4f", result.baselineShrinkage));
                values.add(String.valueOf(result.outputValid));
                values.add(CsvFormatUtils.sanitizeCsvText(result.rejectReason));
                values.add(result.model == null ? "" : joinNames(result.model.featureNames));
                values.add(result.model == null ? "" : joinNames(result.model.summarySourceColumns));
                values.add(result.model == null ? "" : CsvFormatUtils.formatValuesSemicolon(result.model.baselineMap));
                values.add(result.model == null ? "" : CsvFormatUtils.formatValuesSemicolon(result.model.baselinePp));
                values.add(result.model == null ? "" : CsvFormatUtils.formatValuesSemicolon(result.model.deltaMap));
                values.add(result.model == null ? "" : CsvFormatUtils.formatValuesSemicolon(result.model.deltaPp));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.baselineMapTerms));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.baselinePpTerms));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.deltaMapTerms));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.deltaPpTerms));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.dynamicMapTerms));
                values.add(CsvFormatUtils.formatValuesSemicolon(result.dynamicPpTerms));
            }
        }
        csvContent.append(String.join(", ", values));
    }

    private static List<Result> computeAdaptiveSeries(List<Row> rows, AdaptiveModel model, String method) {
        if (model == null) {
            return emptySeries(rows.size());
        }
        Summary summary = buildSummary(rows, model, method);
        if (summary == null) {
            return emptySeries(rows.size());
        }

        double baselineMapRaw = predict(model.baselineMap, summary.summaryFeatures);
        double baselinePpRaw = predict(model.baselinePp, summary.summaryFeatures);
        double baselineMap = model.populationMapAnchor + model.baselineShrinkage * (baselineMapRaw - model.populationMapAnchor);
        double baselinePp = model.populationPpAnchor + model.baselineShrinkage * (baselinePpRaw - model.populationPpAnchor);
        double[] baselineMapTerms = linearTerms(model.baselineMap, summary.summaryFeatures);
        double[] baselinePpTerms = linearTerms(model.baselinePp, summary.summaryFeatures);

        List<Result> results = new ArrayList<>(rows.size());
        double lastMap = Double.NaN;
        double lastPp = Double.NaN;
        for (Row row : rows) {
            if (!row.isValid(method)) {
                results.add(emptyFor(model, row.rejectReason(method)));
                continue;
            }
            double[] features = featureValues(row, model.featureNames);
            double[] centered = subtract(features, summary.anchorFeatures);
            double deltaMap = predict(model.deltaMap, centered);
            double deltaPp = predict(model.deltaPp, centered);
            double mapRaw = baselineMap + deltaMap;
            double ppRaw = baselinePp + deltaPp;
            double map = Double.isNaN(lastMap) ? mapRaw : ALPHA_MAP * mapRaw + (1.0 - ALPHA_MAP) * lastMap;
            double pp = Double.isNaN(lastPp) ? ppRaw : ALPHA_PP * ppRaw + (1.0 - ALPHA_PP) * lastPp;
            lastMap = map;
            lastPp = pp;
            results.add(new Result(
                    mapRaw,
                    ppRaw,
                    map,
                    pp,
                    baselineMapRaw,
                    baselinePpRaw,
                    baselineMap,
                    baselinePp,
                    deltaMap,
                    deltaPp,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    EMPTY,
                    summary.n,
                    model.baselineShrinkage,
                    1,
                    "ok",
                    model,
                    baselineMapTerms,
                    baselinePpTerms,
                    linearTerms(model.deltaMap, centered),
                    linearTerms(model.deltaPp, centered),
                    new double[0],
                    new double[0]));
        }
        return results;
    }

    private static List<Result> computeRichDynamicSeries(
            List<Row> rows,
            Config config,
            ResultSet resultSet,
            String method) {
        AdaptiveModel model = config.adaptiveModels.get("experimental_smartphone_rich_baseline:" + method);
        if (model == null) {
            return emptySeries(rows.size());
        }

        List<Result> richSeries = new ArrayList<>(rows.size());
        List<RawDynamic> rawDynamic = new ArrayList<>();
        List<Double> rawValidMaps = new ArrayList<>();
        List<Double> rawValidPps = new ArrayList<>();
        List<Double> smoothedValidMaps = new ArrayList<>();
        List<Double> smoothedValidPps = new ArrayList<>();

        double lastMap = Double.NaN;
        double lastPp = Double.NaN;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Result rich = resultSet.get("RICH_BASELINE", method, i);
            richSeries.add(rich);
            if (!row.isValid(method) || rich.outputValid != 1) {
                rawDynamic.add(null);
                continue;
            }
            RawDynamic dynamic = predictDynamic(config, method, row);
            rawDynamic.add(dynamic);
            double map = Double.isNaN(lastMap) ? dynamic.map : ALPHA_MAP * dynamic.map + (1.0 - ALPHA_MAP) * lastMap;
            double pp = Double.isNaN(lastPp) ? dynamic.pp : ALPHA_PP * dynamic.pp + (1.0 - ALPHA_PP) * lastPp;
            lastMap = map;
            lastPp = pp;
            rawValidMaps.add(dynamic.map);
            rawValidPps.add(dynamic.pp);
            smoothedValidMaps.add(map);
            smoothedValidPps.add(pp);
        }

        if (smoothedValidMaps.isEmpty()) {
            return emptySeries(rows.size());
        }
        int anchorCount = Math.min(30, smoothedValidMaps.size());
        double rawAnchorMap = median(rawValidMaps.subList(0, anchorCount));
        double rawAnchorPp = median(rawValidPps.subList(0, anchorCount));
        double anchorMap = median(smoothedValidMaps.subList(0, anchorCount));
        double anchorPp = median(smoothedValidPps.subList(0, anchorCount));

        List<Result> results = new ArrayList<>(rows.size());
        lastMap = Double.NaN;
        lastPp = Double.NaN;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Result rich = richSeries.get(i);
            RawDynamic dynamic = rawDynamic.get(i);
            if (!row.isValid(method) || rich.outputValid != 1 || dynamic == null) {
                results.add(emptyFor(model, row.rejectReason(method)));
                continue;
            }
            double dynamicMap = Double.isNaN(lastMap) ? dynamic.map : ALPHA_MAP * dynamic.map + (1.0 - ALPHA_MAP) * lastMap;
            double dynamicPp = Double.isNaN(lastPp) ? dynamic.pp : ALPHA_PP * dynamic.pp + (1.0 - ALPHA_PP) * lastPp;
            lastMap = dynamicMap;
            lastPp = dynamicPp;

            double mapRaw = rich.mapRaw + DEFAULT_DYNAMIC_GAIN_MAP * (dynamic.map - rawAnchorMap);
            double ppRaw = rich.ppRaw + DEFAULT_DYNAMIC_GAIN_PP * (dynamic.pp - rawAnchorPp);
            double map = rich.map + DEFAULT_DYNAMIC_GAIN_MAP * (dynamicMap - anchorMap);
            double pp = rich.pp + DEFAULT_DYNAMIC_GAIN_PP * (dynamicPp - anchorPp);
            results.add(new Result(
                    mapRaw,
                    ppRaw,
                    map,
                    pp,
                    rich.baselineMapRaw,
                    rich.baselinePpRaw,
                    rich.baselineMap,
                    rich.baselinePp,
                    rich.deltaMap,
                    rich.deltaPp,
                    rich.map,
                    rich.pp,
                    dynamicMap,
                    dynamicPp,
                    dynamicMap - anchorMap,
                    dynamicPp - anchorPp,
                    DEFAULT_DYNAMIC_GAIN_MAP,
                    DEFAULT_DYNAMIC_GAIN_PP,
                    rich.initialBaselineBeats,
                    rich.baselineShrinkage,
                    1,
                    "ok",
                    model,
                    rich.baselineMapTerms,
                    rich.baselinePpTerms,
                    rich.deltaMapTerms,
                    rich.deltaPpTerms,
                    dynamic.mapTerms,
                    dynamic.ppTerms));
        }
        return results;
    }

    private static RawDynamic predictDynamic(Config config, String method, Row row) {
        if ("SinBP_D".equals(method)) {
            DynamicModel base = config.dynamicModels.get("RTBP");
            DynamicModel residual = config.dynamicModels.get("SinBP_D");
            double[] baseFeatures = new double[] {
                    row.get("M2_A_used"),
                    row.get("M2_HR_used"),
                    row.get("M2_V2P_relTTP_used"),
                    row.get("M2_P2V_relTTP_used"),
            };
            double[] residualFeatures = featureValues(row, residual.residualFeatures);
            double[] mapBaseTerms = linearTerms(base.map, baseFeatures);
            double[] ppBaseTerms = linearTerms(base.pp, baseFeatures);
            double[] mapResidualTerms = linearTerms(residual.residualMap, residualFeatures);
            double[] ppResidualTerms = linearTerms(residual.residualPp, residualFeatures);
            double map = sum(mapBaseTerms) + sum(mapResidualTerms);
            double pp = sum(ppBaseTerms) + sum(ppResidualTerms);
            return new RawDynamic(map, pp, concat(mapBaseTerms, mapResidualTerms), concat(ppBaseTerms, ppResidualTerms));
        }

        DynamicModel dynamic = config.dynamicModels.get(method);
        double[] features = featureValues(row, dynamic.features);
        double[] mapTerms = linearTerms(dynamic.map, features);
        double[] ppTerms = linearTerms(dynamic.pp, features);
        return new RawDynamic(sum(mapTerms), sum(ppTerms), mapTerms, ppTerms);
    }

    private static Summary buildSummary(List<Row> rows, AdaptiveModel model, String method) {
        List<Row> initialRows = new ArrayList<>();
        for (Row row : rows) {
            if (row.isValid(method)) {
                initialRows.add(row);
                if (initialRows.size() >= model.initialBaselineBeats) {
                    break;
                }
            }
        }
        if (initialRows.isEmpty()) {
            return null;
        }

        double[] summaryFeatures = model.columnMajorSummary
                ? columnMajorSummary(initialRows, model.summarySourceColumns)
                : statMajorSummary(initialRows, model.summarySourceColumns);
        double[] anchor = new double[model.featureNames.length];
        for (int i = 0; i < model.featureNames.length; i++) {
            anchor[i] = median(valuesFor(initialRows, model.featureNames[i]));
        }
        return new Summary(summaryFeatures, anchor, initialRows.size());
    }

    private static double[] columnMajorSummary(List<Row> rows, String[] columns) {
        double[] features = new double[columns.length * 5];
        int cursor = 0;
        for (String column : columns) {
            List<Double> values = valuesFor(rows, column);
            features[cursor++] = median(values);
            features[cursor++] = std(values);
            features[cursor++] = percentile(values, 10.0);
            features[cursor++] = percentile(values, 90.0);
            features[cursor++] = values.size() >= 2 ? values.get(values.size() - 1) - values.get(0) : 0.0;
        }
        return features;
    }

    private static double[] statMajorSummary(List<Row> rows, String[] columns) {
        double[] features = new double[columns.length * 4];
        int cursor = 0;
        for (String column : columns) {
            features[cursor++] = median(valuesFor(rows, column));
        }
        for (String column : columns) {
            features[cursor++] = std(valuesFor(rows, column));
        }
        for (String column : columns) {
            features[cursor++] = percentile(valuesFor(rows, column), 10.0);
        }
        for (String column : columns) {
            features[cursor++] = percentile(valuesFor(rows, column), 90.0);
        }
        return features;
    }

    private static Config loadConfig(Context context) throws IOException, JSONException {
        JSONObject root = new JSONObject(readRawResource(context, R.raw.realtime_bp_coefficients));
        Config config = new Config();
        for (int i = 0; i < SERIES.length - 1; i++) {
            JSONObject group = root.getJSONObject(SERIES_JSON_KEYS[i]);
            for (String method : METHODS) {
                config.adaptiveModels.put(SERIES_JSON_KEYS[i] + ":" + method, new AdaptiveModel(group.getJSONObject(method), method));
            }
        }
        JSONObject models = root.getJSONObject("models");
        JSONObject rtbp = models.getJSONObject("RTBP");
        config.dynamicModels.put("RTBP", new DynamicModel(
                readStringArray(rtbp.getJSONArray("features")),
                readDoubleArray(rtbp.getJSONArray("MAP")),
                readDoubleArray(rtbp.getJSONArray("PP"))));

        JSONObject sinD = models.getJSONObject("SinBP_D");
        config.dynamicModels.put("SinBP_D", new DynamicModel(
                new String[0],
                new double[0],
                new double[0],
                readStringArray(sinD.getJSONArray("residual_features")),
                readDoubleArray(sinD.getJSONArray("residual_MAP")),
                readDoubleArray(sinD.getJSONArray("residual_PP"))));

        JSONObject sinM = models.getJSONObject("SinBP_M");
        config.dynamicModels.put("SinBP_M", new DynamicModel(
                readStringArray(sinM.getJSONArray("features")),
                readDoubleArray(sinM.getJSONArray("MAP")),
                readDoubleArray(sinM.getJSONArray("PP"))));
        return config;
    }

    private static String readRawResource(Context context, int resourceId) throws IOException {
        try (InputStream input = context.getResources().openRawResource(resourceId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static List<Result> emptySeries(int size) {
        List<Result> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            results.add(Result.empty());
        }
        return results;
    }

    private static Result emptyFor(AdaptiveModel model, String reason) {
        return new Result(
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                EMPTY,
                0,
                model == null ? EMPTY : model.baselineShrinkage,
                0,
                CsvFormatUtils.normalizeRejectReason(reason),
                model,
                new double[0],
                new double[0],
                new double[0],
                new double[0],
                new double[0],
                new double[0]);
    }

    private static double[] featureValues(Row row, String[] names) {
        double[] values = new double[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = row.get(names[i]);
        }
        return values;
    }

    private static List<Double> valuesFor(List<Row> rows, String column) {
        List<Double> values = new ArrayList<>();
        for (Row row : rows) {
            double value = row.get(column);
            if (Double.isFinite(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static double predict(double[] coefficients, double[] features) {
        double value = coefficients.length > 0 ? coefficients[0] : 0.0;
        for (int i = 1; i < coefficients.length && i - 1 < features.length; i++) {
            value += coefficients[i] * features[i - 1];
        }
        return value;
    }

    private static double[] linearTerms(double[] coefficients, double[] features) {
        double[] terms = new double[coefficients.length];
        if (coefficients.length == 0) {
            return terms;
        }
        terms[0] = coefficients[0];
        for (int i = 1; i < coefficients.length && i - 1 < features.length; i++) {
            terms[i] = coefficients[i] * features[i - 1];
        }
        return terms;
    }

    private static double[] subtract(double[] values, double[] anchor) {
        double[] centered = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            centered[i] = values[i] - (i < anchor.length ? anchor[i] : 0.0);
        }
        return centered;
    }

    private static double sum(double[] values) {
        double result = 0.0;
        for (double value : values) {
            result += value;
        }
        return result;
    }

    private static double[] concat(double[] first, double[] second) {
        double[] output = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, output, first.length, second.length);
        return output;
    }

    private static double median(List<Double> values) {
        return percentile(values, 50.0);
    }

    private static double std(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = 0.0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.size();
        double sumSquares = 0.0;
        for (double value : values) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / values.size());
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double position = (percentile / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = position - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private static String[] readStringArray(JSONArray array) throws JSONException {
        String[] values = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.getString(i);
        }
        return values;
    }

    private static double[] readDoubleArray(JSONArray array) throws JSONException {
        double[] values = new double[array.length()];
        for (int i = 0; i < array.length(); i++) {
            values[i] = array.getDouble(i);
        }
        return values;
    }

    private static String joinNames(String[] names) {
        return CsvFormatUtils.sanitizeCsvText(String.join(";", names));
    }
}
