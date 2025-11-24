package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Sin波モデルベースのリアルタイム血圧推定器（SBP/DBP）
 * 【SinBP(M) - Model based Linear Regression】
 * 
 * アルゴリズムの要点：
 * 1. Sin波のパラメータ（振幅A、平均値Mean、IBI、位相Phi）を一拍ごとに抽出
 * 2. Sin波パラメータのみを使用した線形回帰モデルでBP推定
 * 3. rPPGの情報は使用せず、Sin波モデルのみに基づく推定
 * 
 * 線形回帰式：
 * SBP = α0 + α1*A + α2*HR + α3*Mean + α4*Phi
 * DBP = β0 + β1*A + β2*HR + β3*Mean + β4*Phi
 * 
 * 参考：一般的なPPGベースのBP推定研究
 * - 振幅と心拍数は血圧と強い相関がある
 * - 平均値（DC成分）も血圧推定に有効
 * - 位相情報は血管特性を反映
 */
public class SinBPModel {
    private static final String TAG = "SinBPModel";

    // バッファサイズ（30fps × 3秒 = 90サンプル）
    private static final int BUFFER_SIZE = 90;

    // 平均用ウィンドウ（10拍）
    private static final int AVG_BEATS = 10;

    // 不応期（ミリ秒）
    private static final long REFRACTORY_PERIOD_MS = 500;

    // リングバッファ
    private final Deque<Double> ppgBuffer = new ArrayDeque<>(BUFFER_SIZE);
    private final Deque<Long> timeBuffer = new ArrayDeque<>(BUFFER_SIZE);

    // 拍検出用
    private double lastPeakValue = 0;
    private long lastPeakTime = 0;
    private double lastValidIBI = 0;

    // 1拍遅延処理用：前の拍のデータを保持
    private long previousPeakTime = 0;
    private double previousPeakValue = 0;

    // 動的な収縮期/拡張期比率（1拍遅延で計算）
    private double currentSystoleRatio = 1.0 / 3.0; // 初期値は1/3（デフォルト）
    private double currentDiastoleRatio = 2.0 / 3.0; // 初期値は2/3（デフォルト）

    // Sin波パラメータ（一拍ごとに更新）
    private double currentA = 0; // 振幅
    private double currentIBI = 0; // IBI (ms)
    private double currentPhi = 0; // 位相
    private double currentMean = 0; // 平均値

    // BP推定結果
    private double lastSinSBP = 0;
    private double lastSinDBP = 0;
    private double lastSinSBPAvg = 0;
    private double lastSinDBPAvg = 0;

    // 平均用履歴
    private final Deque<Double> sinSbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> sinDbpHist = new ArrayDeque<>(AVG_BEATS);

    // ISO管理
    private int currentISO = 600;

    // BaseLogicへの参照（Sin波パラメータ取得用）
    private BaseLogic logicRef;

    // フレームレート
    private int frameRate = 30;

    // 線形回帰係数（Sin波パラメータのみを使用）
    // 注意: 振幅Aと平均値MeanはLogic1で正規化された値（0-10範囲）を使用
    // 2025-11-22評価結果より最適化（SinBP_M係数）
    // SBP = ALPHA0 + ALPHA1*A + ALPHA2*HR + ALPHA3*Mean + ALPHA4*Phi
    private static final double ALPHA0 = 71.03692006596621; // intercept
    private static final double ALPHA1 = 9.119930658703085; // M3_A
    private static final double ALPHA2 = -0.2148949678218121; // M3_HR
    private static final double ALPHA3 = -0.0920224889164238; // M3_Mean
    private static final double ALPHA4 = 11.793308948663666; // M3_Phi

    // DBP = BETA0 + BETA1*A + BETA2*HR + BETA3*Mean + BETA4*Phi
    private static final double BETA0 = 21.680322032107288; // intercept
    private static final double BETA1 = 6.568615116225804; // M3_A
    private static final double BETA2 = 9.294430469883875e-05; // M3_HR
    private static final double BETA3 = -0.3937788369343091; // M3_Mean
    private static final double BETA4 = 15.691979325320622; // M3_Phi

    // リスナー
    public interface SinBPModelListener {
        void onSinBPModelUpdated(double sinSbp, double sinDbp,
                double sinSbpAvg, double sinDbpAvg);
    }

    private SinBPModelListener listener;

    /**
     * リスナーを設定
     */
    public void setListener(SinBPModelListener l) {
        this.listener = l;
    }

    /**
     * ISO値を更新
     */
    public void updateISO(int iso) {
        this.currentISO = iso;
    }

    /**
     * 検出が有効かチェック
     */
    private boolean isDetectionValid() {
        return currentISO >= 300;
    }

    /**
     * BaseLogicへの参照を設定
     */
    public void setLogicRef(BaseLogic logic) {
        this.logicRef = logic;
        Log.d(TAG, "BaseLogic reference set");
    }

    /**
     * フレームレートを更新
     */
    public void setFrameRate(int fps) {
        this.frameRate = fps;
        Log.d(TAG, "Frame rate updated: " + fps);
    }

    /**
     * BaseLogicから30fpsで呼ばれるメインメソッド
     */
    public void update(double correctedGreenValue, long timestampMs) {
        // ISOチェック
        if (!isDetectionValid()) {
            return;
        }

        // バッファに追加
        ppgBuffer.addLast(correctedGreenValue);
        timeBuffer.addLast(timestampMs);

        // バッファサイズ制限
        if (ppgBuffer.size() > BUFFER_SIZE) {
            ppgBuffer.pollFirst();
            timeBuffer.pollFirst();
        }

        // ピーク検出
        detectPeak(correctedGreenValue, timestampMs);
    }

    /**
     * ピーク検出（移動窓 + 不応期）
     */
    private void detectPeak(double currentValue, long currentTime) {
        // 不応期チェック
        if (currentTime - lastPeakTime < REFRACTORY_PERIOD_MS) {
            return;
        }

        // バッファが十分でない場合はスキップ
        if (ppgBuffer.size() < 7) {
            return;
        }

        // 移動窓での最大値チェック（前後3フレーム）
        Double[] recent = ppgBuffer.toArray(new Double[0]);
        int idx = recent.length - 4; // 中央

        if (idx < 3 || idx >= recent.length - 3) {
            return;
        }

        // 中央値が両側より大きい場合のみピークとする
        boolean isPeak = true;
        for (int i = idx - 3; i <= idx + 3; i++) {
            if (i != idx && recent[i] >= recent[idx]) {
                isPeak = false;
                break;
            }
        }

        if (isPeak) {
            processPeak(recent[idx], currentTime);
        }
    }

    /**
     * ピーク検出時の処理（1拍遅延方式）
     */
    private void processPeak(double peakValue, long peakTime) {
        // 初回のピークは記録のみ
        if (lastPeakTime == 0) {
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            previousPeakTime = peakTime;
            previousPeakValue = peakValue;
            return;
        }

        // 2回目以降：前の拍（previousPeakTime → lastPeakTime）のデータが完全になったので処理
        double ibi = lastPeakTime - previousPeakTime;

        // 前の拍分のデータを取得（時刻情報も含む）
        List<Double> beatSamples = new ArrayList<>();
        List<Long> beatTimes = new ArrayList<>();
        extractBeatSamplesWithTime(previousPeakTime, lastPeakTime, beatSamples, beatTimes);

        if (beatSamples == null || beatSamples.isEmpty()) {
            // データを更新して次の拍に備える
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }

        // Sin波パラメータを抽出（rPPG情報は使用しない）
        // その拍のrPPGデータから収縮期/拡張期の比率を計算（SinBP(D)と同様に非対称性を考慮）
        double[] ratios = SignalProcessingUtils.calculateSystoleDiastoleRatio(beatSamples, beatTimes, ibi);
        currentSystoleRatio = ratios[0];
        currentDiastoleRatio = ratios[1];

        Log.d(TAG + "-Ratio", String.format("SinBP(M) Asymmetry: systole=%.3f, diastole=%.3f",
                currentSystoleRatio, currentDiastoleRatio));

        // その拍のサイン波フィット
        extractSinParameters(beatSamples, ibi);

        // 異常値チェック
        if (!SignalProcessingUtils.isValidBeat(ibi, currentA, lastValidIBI)) {
            // データを更新して次の拍に備える
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }

        // Sin波パラメータから線形回帰でBP推定
        estimateBPFromModel();

        // データを更新して次の拍に備える
        previousPeakTime = lastPeakTime;
        previousPeakValue = lastPeakValue;
        lastPeakValue = peakValue;
        lastPeakTime = peakTime;
        lastValidIBI = ibi;

        Log.d(TAG + "-ProcessPeak", String.format(
                "Beat processed: IBI=%.1f, A=%.2f, Mean=%.2f, Phi=%.3f",
                ibi, currentA, currentMean, currentPhi));
    }

    /**
     * 1拍分のサンプルと時刻を抽出（時刻情報も含む）
     */
    private void extractBeatSamplesWithTime(long startTime, long endTime,
            List<Double> samples, List<Long> times) {
        Long[] timeArray = timeBuffer.toArray(new Long[0]);
        Double[] valueArray = ppgBuffer.toArray(new Double[0]);

        samples.clear();
        times.clear();

        for (int i = 0; i < timeArray.length; i++) {
            if (timeArray[i] >= startTime && timeArray[i] <= endTime) {
                samples.add(valueArray[i]);
                times.add(timeArray[i]);
            }
        }
    }

    /**
     * Sin波パラメータを抽出（rPPG情報は使用しない、Sin波モデルのみ）
     * 注意: beatSamplesはLogic1で正規化された値（0-10範囲）を使用
     */
    private void extractSinParameters(List<Double> beatSamples, double ibi) {
        if (beatSamples == null || beatSamples.isEmpty()) {
            return;
        }

        int N = beatSamples.size();

        // 平均値を計算（DCオフセット）
        // 注意: beatSamplesは正規化後の値（0-10範囲）なので、meanも0-10範囲
        double mean = 0;
        for (double sample : beatSamples) {
            mean += sample;
        }
        mean /= N;
        currentMean = mean;

        // DFT風の内積計算で振幅と位相を抽出
        double a = 0, b = 0;
        for (int n = 0; n < N; n++) {
            double angle = 2 * Math.PI * n / N;
            double normalizedSample = beatSamples.get(n) - mean; // DC成分を除去
            a += normalizedSample * Math.sin(angle);
            b += normalizedSample * Math.cos(angle);
        }

        // 正規化
        a = a * 2.0 / N;
        b = b * 2.0 / N;

        // 振幅計算
        // 注意: beatSamplesは正規化後の値（0-10範囲）なので、振幅Aも正規化後の範囲内
        currentA = Math.sqrt(a * a + b * b);

        // ゼロ除算チェック
        if (currentA < 1e-6) {
            Log.w(TAG, "Amplitude too small: " + currentA);
            currentA = 0;
            currentPhi = 0;
            return;
        }

        // 位相計算
        currentPhi = Math.atan2(b, a);

        // 位相を[0, 2π]に正規化
        if (currentPhi < 0) {
            currentPhi += 2 * Math.PI;
        }

        currentIBI = ibi;

        Log.d(TAG + "-Extract", String.format("Extracted: A=%.3f, Mean=%.2f, Phi=%.3f rad (%.1f deg)",
                currentA, currentMean, currentPhi, Math.toDegrees(currentPhi)));
    }

    /**
     * Sin波パラメータから線形回帰でBP推定
     */
    private void estimateBPFromModel() {
        // smoothedIBIからHRを計算（RealtimeBPと同様の方法）
        double hr = 0.0;
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                hr = 60000.0 / lastSmoothedIbi; // smoothedIBIから計算
            } else {
                hr = 60000.0 / currentIBI; // フォールバック
            }
        } else {
            hr = 60000.0 / currentIBI; // フォールバック
        }

        // 線形回帰式：SBP = α0 + α1*A + α2*HR + α3*Mean + α4*Phi
        double sbp = ALPHA0 + ALPHA1 * currentA + ALPHA2 * hr +
                ALPHA3 * currentMean + ALPHA4 * currentPhi;

        // 線形回帰式：DBP = β0 + β1*A + β2*HR + β3*Mean + β4*Phi
        double dbp = BETA0 + BETA1 * currentA + BETA2 * hr +
                BETA3 * currentMean + BETA4 * currentPhi;

        // 制約適用
        if (sbp < dbp + 10) {
            sbp = dbp + 10;
        }

        sbp = SignalProcessingUtils.clamp(sbp, 60, 200);
        dbp = SignalProcessingUtils.clamp(dbp, 40, 150);

        // 生理学的妥当性チェック
        if (!SignalProcessingUtils.isValidBP(sbp, dbp)) {
            Log.w(TAG, String.format("Invalid BP: SBP=%.1f, DBP=%.1f", sbp, dbp));
            return;
        }

        Log.d(TAG + "-Estimate", String.format(
                "BP from Model: SBP=%.1f, DBP=%.1f (A=%.2f, HR=%.1f, Mean=%.2f, Phi=%.3f)",
                sbp, dbp, currentA, hr, currentMean, currentPhi));

        // 履歴更新と平均計算
        updateHistory(sbp, dbp);
    }

    /**
     * 履歴更新と平均計算
     */
    private void updateHistory(double sbp, double dbp) {
        // 現在値を保存
        lastSinSBP = sbp;
        lastSinDBP = dbp;

        // 履歴に追加
        sinSbpHist.addLast(sbp);
        if (sinSbpHist.size() > AVG_BEATS) {
            sinSbpHist.pollFirst();
        }

        sinDbpHist.addLast(dbp);
        if (sinDbpHist.size() > AVG_BEATS) {
            sinDbpHist.pollFirst();
        }

        // ロバスト平均計算
        double sbpAvg = SignalProcessingUtils.robustAverage(sinSbpHist);
        double dbpAvg = SignalProcessingUtils.robustAverage(sinDbpHist);

        lastSinSBPAvg = sbpAvg;
        lastSinDBPAvg = dbpAvg;

        Log.d(TAG + "-Average", String.format(
                "Averaged BP: SBP_avg=%.1f, DBP_avg=%.1f (history size: %d)",
                sbpAvg, dbpAvg, sinSbpHist.size()));

        // リスナー通知
        if (listener != null) {
            listener.onSinBPModelUpdated(sbp, dbp, sbpAvg, dbpAvg);
        }
    }

    /**
     * コンストラクタ
     */
    public SinBPModel() {
        // 初期値を設定
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        Log.d(TAG, "SinBPModel initialized");
    }

    /**
     * リセット
     */
    public void reset() {
        ppgBuffer.clear();
        timeBuffer.clear();
        sinSbpHist.clear();
        sinDbpHist.clear();

        lastPeakValue = 0;
        lastPeakTime = 0;
        previousPeakTime = 0;
        previousPeakValue = 0;
        currentSystoleRatio = 1.0 / 3.0;
        currentDiastoleRatio = 2.0 / 3.0;
        currentA = 0;
        currentIBI = 0;
        currentPhi = 0;
        currentMean = 0;
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        lastValidIBI = 0;

        Log.d(TAG, "SinBPModel reset");
    }

    // Getter methods
    public double getLastSinSBP() {
        return lastSinSBP;
    }

    public double getLastSinDBP() {
        return lastSinDBP;
    }

    public double getLastSinSBPAvg() {
        return lastSinSBPAvg;
    }

    public double getLastSinDBPAvg() {
        return lastSinDBPAvg;
    }

    // 学習用CSV出力のための特徴量取得メソッド
    public double getCurrentAmplitude() {
        return currentA;
    }

    public double getCurrentIBI() {
        return currentIBI;
    }

    public double getCurrentMean() {
        return currentMean;
    }

    public double getCurrentPhase() {
        return currentPhi;
    }

    public double getCurrentHR() {
        if (currentIBI > 0) {
            return 60000.0 / currentIBI;
        }
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                return 60000.0 / lastSmoothedIbi;
            }
        }
        return 0.0;
    }
}
