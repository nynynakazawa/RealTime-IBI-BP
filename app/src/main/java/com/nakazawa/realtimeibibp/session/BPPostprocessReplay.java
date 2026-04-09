package com.nakazawa.realtimeibibp.session;

import com.nakazawa.realtimeibibp.BPPostProcessor;
import java.util.ArrayList;
import java.util.List;

public final class BPPostprocessReplay {
    private BPPostprocessReplay() {
    }

    public static List<BPPostProcessor.Result> buildSeries(
            BPPostProcessor.Method method,
            int size,
            List<Double> sbpSeries,
            List<Double> dbpSeries,
            List<Integer> outputValidSeries,
            List<String> rejectReasonSeries) {
        List<BPPostProcessor.Result> results = new ArrayList<>(size);
        BPPostProcessor processor = new BPPostProcessor(method);
        for (int i = 0; i < size; i++) {
            int outputValid = i < outputValidSeries.size() ? outputValidSeries.get(i) : 0;
            String rejectReason = i < rejectReasonSeries.size() ? rejectReasonSeries.get(i) : "missing";
            if (outputValid != 1 || !"ok".equals(rejectReason)) {
                results.add(BPPostProcessor.Result.empty());
                continue;
            }
            double sbp = i < sbpSeries.size() ? sbpSeries.get(i) : 0.0;
            double dbp = i < dbpSeries.size() ? dbpSeries.get(i) : 0.0;
            if (!Double.isFinite(sbp) || !Double.isFinite(dbp) || sbp <= 0.0 || dbp <= 0.0) {
                results.add(BPPostProcessor.Result.empty());
                continue;
            }
            results.add(processor.apply(sbp, dbp));
        }
        return results;
    }

    public static BPPostProcessor.Result getResult(List<BPPostProcessor.Result> results, int index) {
        if (results == null || index < 0 || index >= results.size()) {
            return BPPostProcessor.Result.empty();
        }
        BPPostProcessor.Result result = results.get(index);
        return result != null ? result : BPPostProcessor.Result.empty();
    }
}
