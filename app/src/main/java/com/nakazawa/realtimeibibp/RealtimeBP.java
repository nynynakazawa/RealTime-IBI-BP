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
 * 1. 1 拍分の correctedGreenValue（正規化済み PPG 振幅）をバッファリング
 * 2. 形態学的特徴: 最大振幅 (S), 拍末振幅 (D), time-to-peak (TTP), 拍幅 (PW)
 * 3. hemodynamic 特徴: オーグメンテーション指数 (AI = (S - D) / S), 心拍数 (HR)
 * 4. ISO感度依存の誤差を補正した改良線形回帰モデルで SBP / DBP を推定
 *
 * ─ 改良点 ─
 * - ISO正規化: 参照ISO (600) を基準とした相対ISO値
 * - 振幅補正: sNorm = sAmp * isoNorm
 * - 追加説明変数: isoNorm, sNorm, relTTP
 * - 動的ISO値の取得と適用
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
    private double lastAiRaw, lastAiAt75;
    private double lastAiRawPct, lastAiAt75Pct;
    // relTTP用フィールドを追加
    private double lastRelTTP;
    private double lastValleyToPeakRelTTP; // 谷→山のrelTTP
    private double lastPeakToValleyRelTTP; // 山→谷のrelTTP

    /** AI の HR 補正傾き [%/bpm] */
    private static final double AI_HR_SLOPE_PCT_PER_BPM = -0.39;
    // --- 回帰係数（暫定調整） ---
    // SBP: C0 + C1*AI75 + C2*HR + C4*sNorm + C5*isoDev + C6*V2P_relTTP + C7*P2V_relTTP
    private static final double C0 = 80, C1 = 0.5, C2 = 0.1,
            C4 = 0.001, C5 = -5, C6 = 0.1, C7 = -0.1; // ベースラインは維持、他を調整
    // DBP: D0 + D1*AI75 + D2*HR + D4*sNorm + D5*isoDev + D6*V2P_relTTP + D7*P2V_relTTP
    private static final double D0 = 60, D1 = 0.3, D2 = 0.05,
            D4 = 0.0005, D5 = -2, D6 = 0.05, D7 = -0.05; // ベースラインは維持、他を調整

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

        // BaseLogicから最新の平均値を取得
        double valleyToPeakRelTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        double peakToValleyRelTTP = (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
        double averageAI = (logicRef != null) ? logicRef.averageAI : 0.0;
        
        // ローカル変数に保存（ログ出力用）
        lastValleyToPeakRelTTP = valleyToPeakRelTTP;
        lastPeakToValleyRelTTP = peakToValleyRelTTP;
        lastAiAt75 = averageAI;
        
        Log.d("RealtimeBP-Estimate", String.format(
                "BaseLogic values: V2P_relTTP=%.3f, P2V_relTTP=%.3f, AI=%.3f%%",
                valleyToPeakRelTTP, peakToValleyRelTTP, averageAI));
        
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
        
        // ISO補正の計算（コメントアウト）
        // double isoNorm = currentISO / 600.0;
        // double isoDev = isoNorm - 1.0;
        double isoNorm = 1.0; // ISO補正を無効化（常に基準値として扱う）
        double isoDev = 0.0;  // ISO偏差を0に固定

        // Sピーク値の取得（独立検出システムの平均値を使用）
        double sPeak = 0.0;
        if (logicRef != null) {
            // 谷→山の振幅をSピーク値として使用
            sPeak = logicRef.averageValleyToPeakAmplitude;
        }
        // double sNorm = sPeak * isoNorm; // ISO補正を無効化
        double sNorm = sPeak; // ISO補正なしの生の値を使用

        // 回帰式による血圧推定（谷→山と山→谷のrelTTPを区別して使用、ISO補正項をコメントアウト）
        double sbp = C0 + C1 * lastAiAt75 + C2 * hr + C4 * sNorm // + C5 * isoDev
                + C6 * lastValleyToPeakRelTTP + C7 * lastPeakToValleyRelTTP;
        double dbp = D0 + D1 * lastAiAt75 + D2 * hr + D4 * sNorm // + D5 * isoDev
                + D6 * lastValleyToPeakRelTTP + D7 * lastPeakToValleyRelTTP;

        Log.d("RealtimeBP-Estimate", String.format(
                "RawBP: SBP=%.2f, DBP=%.2f AI75=%.2f, VtoP_relTTP=%.2f, PtoV_relTTP=%.2f, HR=%.2f, sNorm=%.2f, isoDev=%.2f",
                sbp, dbp, lastAiAt75, lastValleyToPeakRelTTP, lastPeakToValleyRelTTP, hr, sNorm, isoDev));

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
        lastAiRaw = 0.0;
        lastAiAt75 = 0.0;
        lastAiRawPct = 0.0;
        lastAiAt75Pct = 0.0;
        lastRelTTP = 0.0;
        lastValleyToPeakRelTTP = 0.0;
        lastPeakToValleyRelTTP = 0.0;
        
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

    /**
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}