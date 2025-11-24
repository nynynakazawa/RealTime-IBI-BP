package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Locale;

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

    /** 直近 N 拍の推定 SBP / DBP を保持 */
    private final Deque<Double> sbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> dbpHist = new ArrayDeque<>(AVG_BEATS);

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
    // relTTP用フィールド
    private double lastValleyToPeakRelTTP; // 谷→山のrelTTP
    private double lastPeakToValleyRelTTP; // 山→谷のrelTTP
    private double lastAmplitude; // 振幅（A）

    // --- 回帰係数（2025-11-22評価結果より最適化） ---
    // SBP: C0 + C1*A + C2*HR + C3*V2P_relTTP + C4*P2V_relTTP
    // RealTimeBP SBP係数（2025-11-22）
    private static final double C0 = 120.49478037874556;  // intercept
    private static final double C1 = 2.924063174160665;     // M1_A
    private static final double C2 = -0.3107170597000359;   // M1_HR
    private static final double C3 = 27.499385119512844;    // M1_V2P_relTTP
    private static final double C4 = -31.8944153518056;     // M1_P2V_relTTP
    // DBP: D0 + D1*A + D2*HR + D3*V2P_relTTP + D4*P2V_relTTP
    // RealTimeBP DBP係数（2025-11-22）
    private static final double D0 = 57.22996321631314;    // intercept
    private static final double D1 = 3.810689138933605;     // M1_A
    private static final double D2 = 0.14232918573186615;   // M1_HR
    private static final double D3 = 53.34911907085872;    // M1_V2P_relTTP
    private static final double D4 = -27.23680556466717;    // M1_P2V_relTTP

    /**
     * BaseLogicからのコールバック用メソッド
     * @param correctedGreenValue 正規化済み PPG 振幅 (0–100%)
     * @param smoothedIbiMs 平滑化済み IBI (ms)
     */
    public void update(double correctedGreenValue, double smoothedIbiMs) {
        // ISOチェック
        if (currentISO < 300) {
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
        
        // --- 回帰式計算 ---
        // 最新のsmoothedBpmを使用
        double hr = 0.0;
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                hr = 60000.0 / lastSmoothedIbi; // 最新のsmoothedBpm
            } else {
                hr = 60000.0 / ibiMs; // フォールバック
            }
        } else {
            hr = 60000.0 / ibiMs; // フォールバック
        }
        
        // 有効なHR値を保存（ISO < 300の時に使用）
        if (hr > 0) {
            lastValidHr = hr;
        }

        // 回帰式による血圧推定: SBP = C0 + C1*A + C2*HR + C3*V2P_relTTP + C4*P2V_relTTP
        double sbp = C0 + C1 * lastAmplitude + C2 * hr 
                + C3 * lastValleyToPeakRelTTP + C4 * lastPeakToValleyRelTTP;
        // DBP = D0 + D1*A + D2*HR + D3*V2P_relTTP + D4*P2V_relTTP
        double dbp = D0 + D1 * lastAmplitude + D2 * hr 
                + D3 * lastValleyToPeakRelTTP + D4 * lastPeakToValleyRelTTP;

        Log.d("RealtimeBP-Estimate", String.format(
                "RawBP: SBP=%.2f, DBP=%.2f A=%.3f, HR=%.2f, VtoP_relTTP=%.3f, PtoV_relTTP=%.3f",
                sbp, dbp, lastAmplitude, hr, lastValleyToPeakRelTTP, lastPeakToValleyRelTTP));

        // 範囲制限
        sbp = clamp(sbp, 60, 200);
        dbp = clamp(dbp, 40, 150);

        // --- 保存と平均計算 ---
        lastSbp = sbp;
        lastDbp = dbp;
        sbpHist.addLast(sbp);
        if (sbpHist.size() > AVG_BEATS)
            sbpHist.pollFirst();
        dbpHist.addLast(dbp);
        if (dbpHist.size() > AVG_BEATS)
            dbpHist.pollFirst();

        double sbpAvg = robustAverage(sbpHist);
        double dbpAvg = robustAverage(dbpHist);
        lastSbpAvg = sbpAvg;
        lastDbpAvg = dbpAvg;

        Log.d("RealtimeBP-Estimate", String.format(
                "Averaged BP: SBP_avg=%.1f, DBP_avg=%.1f (history size: %d)",
                sbpAvg, dbpAvg, sbpHist.size()));

        // リスナ通知
        if (listener != null) {
            listener.onBpUpdated(sbp, dbp, sbpAvg, dbpAvg);
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
        
        // 血圧値をリセット
        lastSbp = 0.0;
        lastDbp = 0.0;
        lastSbpAvg = 0.0;
        lastDbpAvg = 0.0;
        
        // その他の値をリセット
        lastValleyToPeakRelTTP = 0.0;
        lastPeakToValleyRelTTP = 0.0;
        lastAmplitude = 0.0;
        
        // タイムスタンプをリセット
        lastUpdateTime = 0;
        
        Log.d("RealtimeBP", "Blood pressure values reset to 0.00");
    }

    private double robustAverage(Deque<Double> hist) {
        List<Double> list = new ArrayList<>(hist);
        if (list.isEmpty())
            return 0.0;

        // 1) 中央値を求める
        Collections.sort(list);
        double median = list.get(list.size() / 2);

        // 2) 偏差リストを作成し、その中央値 (MAD) を求める
        List<Double> deviations = list.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .collect(Collectors.toList());
        double mad = deviations.get(deviations.size() / 2);

        // 3) 閾値 = 3 × MAD (ハンペルフィルタ相当)
        double threshold = 3 * mad;

        // 4) 中央値±閾値内のデータだけフィルタ
        List<Double> filtered = list.stream()
                .filter(v -> Math.abs(v - median) <= threshold)
                .collect(Collectors.toList());

        // 5) フィルタ後の平均を返す (全て除外された場合は median を返す)
        return filtered.stream()
                .mapToDouble(v -> v)
                .average()
                .orElse(median);
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

    /**
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}