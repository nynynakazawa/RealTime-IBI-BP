package com.example.realtimehribicontrol;

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

    /** 1 拍あたり最大サンプル数 (想定フレームレート 30–90 fps 対応) */
    private static final int MAX_BEAT_SAMPLES = 90;

    /** 1 拍分の PPG 振幅を保持するバッファ */
    private final Deque<Double> beatBuf = new ArrayDeque<>(MAX_BEAT_SAMPLES);

    /** 最後の拍動開始時刻 (ms) */
    private long lastBeatStart = System.currentTimeMillis();

    /** 平均用ウィンドウ (10 拍) */
    private static final int AVG_BEATS = 10;

    /** 直近 N 拍の推定 SBP / DBP を保持 */
    private final Deque<Double> sbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> dbpHist = new ArrayDeque<>(AVG_BEATS);
    
    /** 現在のISO値（動的に更新） */
    private int currentISO = 600; // デフォルト値

    /** フレームレート [fps]（デフォルト30、setterで更新可） */
    private int frameRate = 30;
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

    /** AI の HR 補正傾き [%/bpm] */
    private static final double AI_HR_SLOPE_PCT_PER_BPM = -0.39;
    // --- 回帰係数（調整後） ---
    private static final double C0 = 80,  C1 = 30,  C2 = 0.2,
        C3 = 2,  C4 = 0.01, C5 = -10;
    private static final double D0 = 60,  D1 = 15,  D2 = 0.1,
        D3 = 1,  D4 = 0.005, D5 = 0;
    /**
     * Logic1 から毎フレーム呼び出される更新メソッド
     * @param correctedGreenValue 正規化済み PPG 振幅 (0–100%)
     * @param smoothedIbiMs       平滑化済み IBI (ms)
     */
    public void update(double correctedGreenValue, double smoothedIbiMs) {
        Log.d("RealtimeBP", "update called: value=" + correctedGreenValue + ", IBI=" + smoothedIbiMs);
        // バッファにサンプルを追加
        beatBuf.addLast(correctedGreenValue);
        // 古いサンプルは捨てる
        if (beatBuf.size() > MAX_BEAT_SAMPLES) {
            beatBuf.pollFirst();
        }

        long now = System.currentTimeMillis();

        // IBI (ms) が経過したら 1 拍分とみなして処理
        if (now - lastBeatStart >= smoothedIbiMs && beatBuf.size() > 5) {
            estimateAndNotify(smoothedIbiMs);
            // バッファクリア & 拍動開始時刻を更新
            beatBuf.clear();
            lastBeatStart = now;
        }
    }

    /**
     * 1 拍分の形態学的特徴を抽出し，
     * 線形回帰モデルで SBP / DBP を推定し，リスナへ通知
     */
    private void estimateAndNotify(double ibiMs) {
        Log.d("RealtimeBP", "estimateAndNotify called");
        // --- 形態学的特徴抽出 ---
        int n = beatBuf.size();
        double[] beatArr = new double[n];
        int j = 0;
        for (double v : beatBuf) beatArr[j++] = v;
        // ここでAIraw等の算出
        if (logicRef != null) {
            updateAIfromLogicWindow(60, ibiMs);
        }
        // --- 回帰式 ---
        double hr   = 60000.0 / ibiMs;  // 心拍数 [bpm]
        double isoNorm = currentISO / 600.0;
        double sNorm = 0.0; // sValをupdateAIfromLogicWindowで保存する場合はここで取得
        double isoDev = isoNorm - 1.0;
        // --- 回帰式に使う値をログ出力 ---
        double sPeak = 0.0;
        if (logicRef != null && logicRef.lastPeakEvent != null) {
            sPeak = logicRef.lastPeakEvent.value;
        }
        sNorm = sPeak * isoNorm;

        double sbp = C0 + C1*lastAiAt75 + C2*hr + C3*lastRelTTP + C4*sNorm + C5*isoDev;
        double dbp = D0 + D1*lastAiAt75 + D2*hr + D3*lastRelTTP + D4*sNorm + D5*isoDev;

        Log.d("RealtimeBP", String.format(
            "回帰式入力: Ai75=%.2f, RelTTP=%.2f, hr=%.2f, sNorm=%.2f, isoDev=%.2f, sbp=%.2f, dbp=%.2f",
            lastAiAt75, lastRelTTP, hr, sNorm, isoDev, sbp, dbp
        ));

        // clamp sbp, dbp
        sbp = clamp(sbp, 60, 200);
        dbp = clamp(dbp, 40, 150);
        // --- 保存 ---
        lastSbp = sbp;
        lastDbp = dbp;
        sbpHist.addLast(sbp); if (sbpHist.size() > AVG_BEATS) sbpHist.pollFirst();
        dbpHist.addLast(dbp); if (dbpHist.size() > AVG_BEATS) dbpHist.pollFirst();
        double sbpAvg = robustAverage(sbpHist);
        double dbpAvg = robustAverage(dbpHist);
        lastSbpAvg = sbpAvg;
        lastDbpAvg = dbpAvg;
        /* リスナ通知 (平均付き) */
        if (listener != null) listener.onBpUpdated(sbp, dbp, sbpAvg, dbpAvg);
    }

    // BaseLogicの参照を保持
    private BaseLogic logicRef;
    public void setLogicRef(BaseLogic logic) {
        this.logicRef = logic;
        Log.d("RealtimeBP", "setLogicRef called: " + logic);
    }

    /**
     * CorrectedGreenValue系列（window配列）からAIraw, AI75Frac, relTTPを算出
     * 拍区間は直近の谷→次の谷、Sピークはその区間内の最大値、Dピークは区間末端値
     */
    public void updateAIfromLogicWindow(int windowSize, double ibiMs) {
        Log.d("RealtimeBP", "updateAIfromLogicWindow called");
        if (logicRef == null) return;
        // 1. まず谷を検出し記録
        int valleyIdx = logicRef.detectValleyIndexAndRecord(windowSize);
        Log.d("RealtimeBP", "valleyIdx=" + valleyIdx + ", lastValleyEvent=" + logicRef.lastValleyEvent);

        // 2. 直前に山が記録されていれば、山→谷区間で特徴量を計算
        if (logicRef.lastPeakEvent != null && logicRef.lastValleyEvent != null) {
            // 谷→山 or 山→谷のどちらが新しいかで区間を決定
            BaseLogic.PeakValleyEvent prev, next;
            boolean isValleyToPeak = logicRef.lastValleyEvent.timestamp < logicRef.lastPeakEvent.timestamp;
            if (isValleyToPeak) {
                prev = logicRef.lastValleyEvent;
                next = logicRef.lastPeakEvent;
            } else {
                prev = logicRef.lastPeakEvent;
                next = logicRef.lastValleyEvent;
            }
            long dt = next.timestamp - prev.timestamp;
            double dv = next.value - prev.value;
            double relTTP = (double) (next.timestamp - prev.timestamp) / ibiMs;
            double aiRawFrac = isValleyToPeak ? (next.value - prev.value) / Math.max(next.value, 1e-9)
                                              : (prev.value - next.value) / Math.max(prev.value, 1e-9);
            double aiRawPct = aiRawFrac * 100.0;
            double hr = 60000.0 / ibiMs;
            double aiAt75Pct = aiRawPct + AI_HR_SLOPE_PCT_PER_BPM * (hr - 75);
            double aiAt75Frac = aiAt75Pct / 100.0;
            aiAt75Frac = clamp(aiAt75Frac, -0.5, 1.0);
            lastAiRaw = aiRawFrac;
            lastAiRawPct = aiRawPct;
            lastAiAt75 = aiAt75Frac;
            lastAiAt75Pct = aiAt75Pct;
            lastRelTTP = relTTP;
            Log.d("RealtimeBP", String.format(
                "区間: %s, prevIdx=%d, nextIdx=%d, dt=%dms, dv=%.2f, relTTP=%.3f, AIraw=%.3f",
                isValleyToPeak ? "谷→山" : "山→谷", prev.index, next.index, dt, dv, relTTP, aiRawFrac
            ));
        }
        // 3. 次に山を検出し記録（次回の区間計算用）
        int peakIdx = logicRef.detectPeakIndexAndRecord(windowSize);
        Log.d("RealtimeBP", "peakIdx=" + peakIdx + ", lastPeakEvent=" + logicRef.lastPeakEvent);
    }


    private double robustAverage(Deque<Double> hist) {
        List<Double> list = new ArrayList<>(hist);
        if (list.isEmpty()) return 0.0;

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

    /**
     * Savitzky-Golayフィルタ（5点3次、係数固定）
     * 入力配列dataに滑らか化を適用し、同じ長さの新配列を返す
     */
    public static double[] savitzkyGolay5_3(double[] data) {
        int n = data.length;
        double[] result = new double[n];
        // 端はそのままコピー（3次5点は両端2点はフィルタ不可）
        if (n < 5) return data.clone();
        result[0] = data[0];
        result[1] = data[1];
        result[n-2] = data[n-2];
        result[n-1] = data[n-1];
        // 係数: [-3, 12, 17, 12, -3] / 35
        for (int i = 2; i < n - 2; i++) {
            result[i] = (
                -3 * data[i-2] +
                12 * data[i-1] +
                17 * data[i] +
                12 * data[i+1] +
                -3 * data[i+2]
            ) / 35.0;
        }
        return result;
    }

    /**
     * 1次微分（単純差分）を計算
     * @param data 入力配列
     * @return 微分配列（長さはdata.length）
     */
    public static double[] diff1(double[] data) {
        int n = data.length;
        double[] diff = new double[n];
        diff[0] = 0;
        for (int i = 1; i < n; i++) {
            diff[i] = data[i] - data[i-1];
        }
        return diff;
    }

    /**
     * 2次微分（単純差分の差分）を計算
     * @param data 入力配列
     * @return 2次微分配列（長さはdata.length）
     */
    public static double[] diff2(double[] data) {
        int n = data.length;
        double[] diff2 = new double[n];
        diff2[0] = 0;
        diff2[1] = 0;
        for (int i = 2; i < n; i++) {
            diff2[i] = data[i] - 2 * data[i-1] + data[i-2];
        }
        return diff2;
    }

    /**
     * foot検出: 1次微分が0から正になる点（上昇開始点）のインデックスを返す
     * @param data PPG波形配列
     * @return foot index（見つからなければ0）
     */
    public static int detectFoot(double[] data) {
        int n = data.length;
        for (int i = 1; i < n; i++) {
            double diffPrev = data[i] - data[i-1];
            if (diffPrev > 0) {
                // 直前が0以下→正になった最初の点
                if (i == 1 || data[i-1] - data[i-2] <= 0) {
                    return i-1;
                }
            }
        }
        return 0; // 見つからなければ先頭
    }

    /**
     * Sピーク（最大値）とDピーク（dicrotic notch後の最大値）を検出
     * @param data PPG波形配列（1拍分）
     * @return int[]{Sピークindex, Dピークindex}
     */
    public static int[] detectSPeakAndDPeak(double[] data) {
        int n = data.length;
        // Sピーク（最大値）
        int idxS = 0;
        double maxS = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            if (data[i] > maxS) {
                maxS = data[i];
                idxS = i;
            }
        }
        // Dicrotic notch検出（Sピーク後の最初の局所最小）
        int idxNotch = idxS;
        for (int i = idxS + 1; i < n - 1; i++) {
            if (data[i] < data[i-1] && data[i] < data[i+1]) {
                idxNotch = i;
                break;
            }
        }
        // Dピーク（notch後の最大値）
        int idxD = idxNotch;
        double maxD = data[idxNotch];
        for (int i = idxNotch + 1; i < n; i++) {
            if (data[i] > maxD) {
                maxD = data[i];
                idxD = i;
            }
        }
        return new int[]{idxS, idxD};
    }

    public double getLastSbp()    { return lastSbp; }
    public double getLastDbp()    { return lastDbp; }
    public double getLastSbpAvg() { return lastSbpAvg; }
    public double getLastDbpAvg() { return lastDbpAvg; }
    public double getLastAiRaw() { return lastAiRaw; }
    public double getLastAiAt75() { return lastAiAt75; }
    public double getLastAiRawPct()  { return lastAiRawPct; }
    public double getLastAiAt75Pct() { return lastAiAt75Pct; }
    // relTTPのgetter
    public double getLastRelTTP() { return lastRelTTP; }

    /**
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}