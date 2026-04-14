package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import com.nakazawa.realtimeibibp.bp.FeatureClampUtils;
import com.nakazawa.realtimeibibp.bp.MapPpPrediction;
import com.nakazawa.realtimeibibp.bp.RealtimeMapPpModels;

/**
 * Photoplethysmography (PPG) の形態学的特徴量を用いた
 * リアルタイム血圧推定器（SBP/DBP）。
 *
 * ─ アルゴリズムの要点 ─
 * 1. BaseLogicから振幅（A）、心拍数（HR）、相対TTP（V2P_relTTP, P2V_relTTP）を取得
 * 2. 線形回帰モデルで SBP / DBP を推定
 * 
 * 回帰式:
 * SBP = C0 + C1*A + C2*HR + C3*V2P_relTTP + C4*P2V_relTTP
 * DBP = D0 + D1*A + D2*HR + D3*V2P_relTTP + D4*P2V_relTTP
 */
public class RealtimeBP {

    /** 平均用ウィンドウ (10 拍) */
    private static final int AVG_BEATS = 10;
    /** postprocess 後の表示用平均は、追加の二重平滑化を避けるため少し短くする */
    private static final int DISPLAY_AVG_BEATS = 5;

    /** 直近 N 拍の推定 SBP / DBP を保持 */
    private final Deque<Double> sbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> dbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> displayedSbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> displayedDbpHist = new ArrayDeque<>(AVG_BEATS);
    private final BPPostProcessor postProcessor = new BPPostProcessor(BPPostProcessor.Method.RTBP);

    /** 現在のISO値（動的に更新） */
    private int currentISO = 600;
    private boolean isDetectionEnabled = true;
    
    // 直前の有効な値を保持（ISO < 300の時に使用）
    private double lastValidHr = 0.0;

    /** フレームレート [fps]（デフォルト30、setterで更新可） */
    private int frameRate = 30;

    // ===== 独立した連続検出システム =====
    private volatile long lastUpdateTime = 0;

    /** フレームレートを更新するメソッド */
    public void setFrameRate(int fps) {
        this.frameRate = fps;
        Log.d("RealtimeBP", "frameRate updated: " + fps);
    }

    /* ===== リスナ：推定結果受信用 ===== */
    public interface BPListener {
        void onBpUpdated(double sbp, double dbp,
                         double sbpAvg, double dbpAvg);
    }

    private BPListener listener;

    /** リスナ登録メソッド */
    public void setListener(BPListener l) {
        this.listener = l;
    }

    /** ISO値を更新するメソッド */
    public void updateISO(int iso) {
        this.currentISO = iso;
    }

    private double lastSbp, lastDbp, lastSbpAvg, lastDbpAvg;
    private double lastDisplayedSbp, lastDisplayedDbp, lastDisplayedSbpAvg, lastDisplayedDbpAvg;
    private double lastMapRaw, lastPpRaw, lastMapSmoothed, lastPpSmoothed, lastMapCalibrated, lastPpCalibrated;
    private int lastPostprocessApplied;
    // relTTP用フィールド
    private double lastValleyToPeakRelTTP; // 谷→山のrelTTP
    private double lastPeakToValleyRelTTP; // 山→谷のrelTTP
    private double lastAmplitude; // 振幅（A）
    private double lastInputIbiMs;
    private double lastSmoothedIbiMs;
    private int lastUsedSmoothedIbi;
    private double lastUsedAmplitude;
    private double lastUsedHr;
    private double lastUsedValleyToPeakRelTTP;
    private double lastUsedPeakToValleyRelTTP;
    private double lastRawSbp;
    private double lastRawDbp;
    private int lastClampApplied;
    private int lastOutputValid;
    private int lastFeatureClampApplied;
    private String lastFeatureClampReason = "init";
    private String lastRejectReason = "init";

    // 2026-04-10: realtime session 3件から MAP/PP を別々に再学習し、SBP/DBP 係数へ変換。
    // CNAP はオフライン教師ラベルとしてのみ使用し、アプリ実行時の補正入力には使わない。
    // SBP: C0 + C1*A + C2*HR + C3*V2P_relTTP + C4*P2V_relTTP
    private static final double C0 = 120.17891432255932;  // intercept
    private static final double C1 = -0.8591241817045703; // M1_A
    private static final double C2 = 0.17319093422811827; // M1_HR
    private static final double C3 = 0.5477678810080552;  // M1_V2P_relTTP
    private static final double C4 = 11.021048362406805;  // M1_P2V_relTTP
    // DBP: D0 + D1*A + D2*HR + D3*V2P_relTTP + D4*P2V_relTTP
    private static final double D0 = 87.20713989383975;   // intercept
    private static final double D1 = 0.431515602257126;   // M1_A
    private static final double D2 = -0.0888642718739695; // M1_HR
    private static final double D3 = -0.3713417153679063; // M1_V2P_relTTP
    private static final double D4 = -5.5791444452849595; // M1_P2V_relTTP

    // prepared_training_data.csv の 1-99 percentile で支持域を定義。
    // 線形係数の外挿を避け、実機で異常特徴量が入ったときの暴走を抑える。
    private static final double A_SUPPORT_MIN = 0.838976;
    private static final double A_SUPPORT_MAX = 9.636604;
    private static final double HR_SUPPORT_MIN = 59.436808;
    private static final double HR_SUPPORT_MAX = 108.624444;
    private static final double V2P_SUPPORT_MIN = -0.928772;
    private static final double V2P_SUPPORT_MAX = -0.355472;
    private static final double P2V_SUPPORT_MIN = -0.859316;
    private static final double P2V_SUPPORT_MAX = -0.272260;

    public static double[] getSbpCoefficients() {
        return RealtimeMapPpModels.getRtbpSbpCoefficients();
    }

    public static double[] getDbpCoefficients() {
        return RealtimeMapPpModels.getRtbpDbpCoefficients();
    }

    /**
     * BaseLogicからのコールバック用メソッド
     * @param correctedGreenValue 正規化済み PPG 振幅 (0–100%)
     * @param smoothedIbiMs 平滑化済み IBI (ms)
     */
    public void update(double correctedGreenValue, double smoothedIbiMs) {
        // ISOチェック
        if (currentISO < 300) {
            lastOutputValid = 0;
            lastRejectReason = "low_iso";
            Log.d("RealtimeBP-ISO", "Blood pressure estimation skipped: ISO=" + currentISO);
            return;
        }

        // 血圧推定を実行
        estimateAndNotify(smoothedIbiMs);
    }

    /**
     * 1 拍分の形態学的特徴を抽出し，
     * 線形回帰モデルで SBP / DBP を推定し，リスナへ通知
     */
    private void estimateAndNotify(double ibiMs) {
        // ISOチェック
        if (currentISO < 300) {
            Log.d("RealtimeBP-ISO", "Blood pressure estimation skipped: ISO=" + currentISO);
            return;
        }

        Log.d("RealtimeBP-Estimate", "=== estimateAndNotify START ===");
        Log.d("RealtimeBP-Estimate", "Input: ibiMs=" + ibiMs);
        lastInputIbiMs = ibiMs;

        // BaseLogicから最新の値を取得
        double valleyToPeakRelTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        double peakToValleyRelTTP = (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
        double amplitude = (logicRef != null) ? logicRef.averageValleyToPeakAmplitude : 0.0;
        
        // ローカル変数に保存
        lastValleyToPeakRelTTP = valleyToPeakRelTTP;
        lastPeakToValleyRelTTP = peakToValleyRelTTP;
        lastAmplitude = amplitude;
        
        Log.d("RealtimeBP-Estimate", String.format(
                "BaseLogic values: A=%.3f, V2P_relTTP=%.3f, P2V_relTTP=%.3f",
                amplitude, valleyToPeakRelTTP, peakToValleyRelTTP));

        String relTtpReason = SignalProcessingUtils.getRelTtpConsistencyReason(
                valleyToPeakRelTTP, peakToValleyRelTTP);
        if (!"ok".equals(relTtpReason)) {
            lastOutputValid = 0;
            lastRejectReason = relTtpReason;
            Log.w("RealtimeBP-Estimate", "Skipping RTBP update due to inconsistent relTTP pair: " + relTtpReason);
            return;
        }
        
        // --- 回帰式計算 ---
        // 最新のsmoothedBpmを使用
        double hr = 0.0;
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                hr = 60000.0 / lastSmoothedIbi; // 最新のsmoothedBpm
                lastSmoothedIbiMs = lastSmoothedIbi;
                lastUsedSmoothedIbi = 1;
            } else {
                hr = 60000.0 / ibiMs; // フォールバック
                lastSmoothedIbiMs = 0.0;
                lastUsedSmoothedIbi = 0;
            }
        } else {
            hr = 60000.0 / ibiMs; // フォールバック
            lastSmoothedIbiMs = 0.0;
            lastUsedSmoothedIbi = 0;
        }
        
        // 有効なHR値を保存（ISO < 300の時に使用）
        if (hr > 0) {
            lastValidHr = hr;
        }

        StringBuilder featureClampReason = new StringBuilder();
        double usedAmplitude = FeatureClampUtils.clampFeature("A", amplitude, A_SUPPORT_MIN, A_SUPPORT_MAX, featureClampReason);
        double usedHr = FeatureClampUtils.clampFeature("HR", hr, HR_SUPPORT_MIN, HR_SUPPORT_MAX, featureClampReason);
        double usedValleyToPeakRelTTP = FeatureClampUtils.clampFeature(
                "V2P_relTTP", valleyToPeakRelTTP, V2P_SUPPORT_MIN, V2P_SUPPORT_MAX, featureClampReason);
        double usedPeakToValleyRelTTP = FeatureClampUtils.clampFeature(
                "P2V_relTTP", peakToValleyRelTTP, P2V_SUPPORT_MIN, P2V_SUPPORT_MAX, featureClampReason);
        lastUsedAmplitude = usedAmplitude;
        lastUsedHr = usedHr;
        lastUsedValleyToPeakRelTTP = usedValleyToPeakRelTTP;
        lastUsedPeakToValleyRelTTP = usedPeakToValleyRelTTP;
        lastFeatureClampApplied = featureClampReason.length() > 0 ? 1 : 0;
        lastFeatureClampReason = featureClampReason.length() > 0 ? featureClampReason.toString() : "ok";

        MapPpPrediction prediction = RealtimeMapPpModels.predictRtbp(
                usedAmplitude,
                usedHr,
                usedValleyToPeakRelTTP,
                usedPeakToValleyRelTTP);
        double sbp = prediction.sbpFinal;
        double dbp = prediction.dbpFinal;
        lastRawSbp = prediction.sbpModelRaw;
        lastRawDbp = prediction.dbpModelRaw;

        Log.d("RealtimeBP-Estimate", String.format(
                Locale.US,
                "RawBP: SBP=%.2f/%.2f final=%.2f/%.2f MAP=%.2f PP=%.2f A=%.3f->%.3f, HR=%.2f->%.2f, VtoP_relTTP=%.3f->%.3f, PtoV_relTTP=%.3f->%.3f clamp=%s",
                prediction.sbpModelRaw, prediction.dbpModelRaw,
                sbp, dbp,
                prediction.mapModelRaw, prediction.ppModelRaw,
                amplitude, usedAmplitude,
                hr, usedHr,
                valleyToPeakRelTTP, usedValleyToPeakRelTTP,
                peakToValleyRelTTP, usedPeakToValleyRelTTP,
                lastFeatureClampReason));
        lastClampApplied = (Math.abs(prediction.sbpFinal - prediction.sbpModelRaw) > 1e-9
                || Math.abs(prediction.dbpFinal - prediction.dbpModelRaw) > 1e-9) ? 1 : 0;
        lastOutputValid = 1;
        lastRejectReason = "ok";

        BPPostProcessor.Result postprocess = postProcessor.apply(sbp, dbp);
        lastMapRaw = postprocess.mapRaw;
        lastPpRaw = postprocess.ppRaw;
        lastMapSmoothed = postprocess.mapSmoothed;
        lastPpSmoothed = postprocess.ppSmoothed;
        lastMapCalibrated = postprocess.mapCalibrated;
        lastPpCalibrated = postprocess.ppCalibrated;
        lastPostprocessApplied = postprocess.postprocessApplied;
        double displayedSbp = postprocess.postprocessApplied == 1 ? postprocess.sbpSmoothed : sbp;
        double displayedDbp = postprocess.postprocessApplied == 1 ? postprocess.dbpSmoothed : dbp;
        lastDisplayedSbp = displayedSbp;
        lastDisplayedDbp = displayedDbp;

        // --- 保存と平均計算 ---
        lastSbp = sbp;
        lastDbp = dbp;
        sbpHist.addLast(sbp);
        if (sbpHist.size() > AVG_BEATS)
            sbpHist.pollFirst();
        dbpHist.addLast(dbp);
        if (dbpHist.size() > AVG_BEATS)
            dbpHist.pollFirst();
        displayedSbpHist.addLast(displayedSbp);
        if (displayedSbpHist.size() > DISPLAY_AVG_BEATS)
            displayedSbpHist.pollFirst();
        displayedDbpHist.addLast(displayedDbp);
        if (displayedDbpHist.size() > DISPLAY_AVG_BEATS)
            displayedDbpHist.pollFirst();

        double sbpAvg = SignalProcessingUtils.robustAverage(sbpHist);
        double dbpAvg = SignalProcessingUtils.robustAverage(dbpHist);
        double displayedSbpAvg = SignalProcessingUtils.robustAverage(displayedSbpHist);
        double displayedDbpAvg = SignalProcessingUtils.robustAverage(displayedDbpHist);
        lastSbpAvg = sbpAvg;
        lastDbpAvg = dbpAvg;
        lastDisplayedSbpAvg = displayedSbpAvg;
        lastDisplayedDbpAvg = displayedDbpAvg;

        Log.d("RealtimeBP-Estimate", String.format(
                "Averaged BP: raw=%.1f/%.1f displayed=%.1f/%.1f (history size: %d)",
                sbpAvg, dbpAvg, displayedSbpAvg, displayedDbpAvg, sbpHist.size()));

        // リスナ通知
        if (listener != null) {
            listener.onBpUpdated(displayedSbp, displayedDbp, displayedSbpAvg, displayedDbpAvg);
            Log.d("RealtimeBP-Estimate", "BP values notified to listener");
        }

        Log.d("RealtimeBP-Estimate", "=== estimateAndNotify END ===");
    }

    // BaseLogicの参照を保持
    private BaseLogic logicRef;

    public void setLogicRef(BaseLogic logic) {
        this.logicRef = logic;
        Log.d("RealtimeBP", "setLogicRef called: " + logic);

        // 連続検出のコールバックを設定
        if (logic != null) {
            logic.setBPFrameCallback(this::update);
        }
    }

    /**
     * 血圧推定値をリセット
     */
    public void reset() {
        // 血圧履歴をクリア
        sbpHist.clear();
        dbpHist.clear();
        displayedSbpHist.clear();
        displayedDbpHist.clear();
        postProcessor.reset();
        
        // 血圧値をリセット
        lastSbp = 0.0;
        lastDbp = 0.0;
        lastSbpAvg = 0.0;
        lastDbpAvg = 0.0;
        lastDisplayedSbp = 0.0;
        lastDisplayedDbp = 0.0;
        lastDisplayedSbpAvg = 0.0;
        lastDisplayedDbpAvg = 0.0;
        lastMapRaw = 0.0;
        lastPpRaw = 0.0;
        lastMapSmoothed = 0.0;
        lastPpSmoothed = 0.0;
        lastMapCalibrated = 0.0;
        lastPpCalibrated = 0.0;
        lastPostprocessApplied = 0;
        
        // その他の値をリセット
        lastValleyToPeakRelTTP = 0.0;
        lastPeakToValleyRelTTP = 0.0;
        lastAmplitude = 0.0;
        lastInputIbiMs = 0.0;
        lastSmoothedIbiMs = 0.0;
        lastUsedSmoothedIbi = 0;
        lastUsedAmplitude = 0.0;
        lastUsedHr = 0.0;
        lastUsedValleyToPeakRelTTP = 0.0;
        lastUsedPeakToValleyRelTTP = 0.0;
        lastRawSbp = 0.0;
        lastRawDbp = 0.0;
        lastClampApplied = 0;
        lastOutputValid = 0;
        lastFeatureClampApplied = 0;
        lastFeatureClampReason = "reset";
        lastRejectReason = "reset";
        
        // タイムスタンプをリセット
        lastUpdateTime = 0;
        
        Log.d("RealtimeBP", "Blood pressure values reset to 0.00");
    }

    public double getLastSbp() {
        return lastSbp;
    }

    public double getLastDbp() {
        return lastDbp;
    }

    public double getLastSbpAvg() {
        return lastSbpAvg;
    }

    public double getLastDbpAvg() {
        return lastDbpAvg;
    }

    public double getLastDisplayedSbp() {
        return lastDisplayedSbp;
    }

    public double getLastDisplayedDbp() {
        return lastDisplayedDbp;
    }

    public double getLastDisplayedSbpAvg() {
        return lastDisplayedSbpAvg;
    }

    public double getLastDisplayedDbpAvg() {
        return lastDisplayedDbpAvg;
    }

    // 学習用CSV出力のための特徴量取得メソッド
    public double getLastAmplitude() {
        return lastAmplitude;
    }

    public double getLastValidHr() {
        return lastValidHr;
    }

    public double getLastValleyToPeakRelTTP() {
        return lastValleyToPeakRelTTP;
    }

    public double getLastPeakToValleyRelTTP() {
        return lastPeakToValleyRelTTP;
    }

    public double getLastInputIbiMs() {
        return lastInputIbiMs;
    }

    public double getLastSmoothedIbiMs() {
        return lastSmoothedIbiMs;
    }

    public int getLastUsedSmoothedIbi() {
        return lastUsedSmoothedIbi;
    }

    public double getLastUsedAmplitude() {
        return lastUsedAmplitude;
    }

    public double getLastUsedHr() {
        return lastUsedHr;
    }

    public double getLastUsedValleyToPeakRelTTP() {
        return lastUsedValleyToPeakRelTTP;
    }

    public double getLastUsedPeakToValleyRelTTP() {
        return lastUsedPeakToValleyRelTTP;
    }

    public double getLastRawSbp() {
        return lastRawSbp;
    }

    public double getLastRawDbp() {
        return lastRawDbp;
    }

    public int getLastClampApplied() {
        return lastClampApplied;
    }

    public int getLastOutputValid() {
        return lastOutputValid;
    }

    public int getLastFeatureClampApplied() {
        return lastFeatureClampApplied;
    }

    public String getLastFeatureClampReason() {
        return lastFeatureClampReason;
    }

    public String getLastRejectReason() {
        return lastRejectReason;
    }

    public double getLastMapRaw() {
        return lastMapRaw;
    }

    public double getLastPpRaw() {
        return lastPpRaw;
    }

    public double getLastMapSmoothed() {
        return lastMapSmoothed;
    }

    public double getLastPpSmoothed() {
        return lastPpSmoothed;
    }

    public double getLastMapCalibrated() {
        return lastMapCalibrated;
    }

    public double getLastPpCalibrated() {
        return lastPpCalibrated;
    }

    public int getLastPostprocessApplied() {
        return lastPostprocessApplied;
    }

    /**
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
