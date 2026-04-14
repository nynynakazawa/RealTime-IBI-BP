package com.nakazawa.realtimeibibp;

import android.util.Log;
import com.nakazawa.realtimeibibp.bp.FeatureClampUtils;
import com.nakazawa.realtimeibibp.bp.MapPpPrediction;
import com.nakazawa.realtimeibibp.bp.PeakInterpolationUtils;
import com.nakazawa.realtimeibibp.bp.RealtimeMapPpModels;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

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
 * SBP = α0 + α1*A + α2*HR + α3*Mean + α4*sin(Phi) + α5*cos(Phi)
 * DBP = β0 + β1*A + β2*HR + β3*Mean + β4*sin(Phi) + β5*cos(Phi)
 * 
 * 参考：一般的なPPGベースのBP推定研究
 * - 振幅と心拍数は血圧と強い相関がある
 * - 平均値（DC成分）も血圧推定に有効
 * - 位相情報は血管特性を反映
 * - ただし位相は円周量なので、回帰には Phi そのものではなく sin/cos 展開を用いる
 */
public class SinBPModel {
    private static final String TAG = "SinBPModel";

    // バッファサイズ（30fps × 3秒 = 90サンプル）
    private static final int BUFFER_SIZE = 90;

    // 平均用ウィンドウ（10拍）
    private static final int AVG_BEATS = 10;
    // postprocess 後の表示平均は短めに保ち、SBP/DBP の見かけ上の一体化を抑える
    private static final int DISPLAY_AVG_BEATS = 5;

    // 不応期（ミリ秒）
    private static final long REFRACTORY_PERIOD_MS = 500;
    private static final long MAX_REFRACTORY_PERIOD_MS = 900;
    private static final double ADAPTIVE_REFRACTORY_RATIO = 0.58;
    private static final double MIN_PEAK_PROMINENCE = 0.12;
    private static final double MIN_PEAK_PROMINENCE_RATIO = 0.35;

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
    private double currentFitAComponent = 0;
    private double currentFitBComponent = 0;
    private double currentSinPhi = 0;
    private double currentCosPhi = 1;
    private double currentFitRMSE = 0;
    private int currentBeatSampleCount = 0;
    private double currentBeatMin = 0;
    private double currentBeatMax = 0;
    private double currentBeatRange = 0;
    private double currentBeatStd = 0;
    private double currentRawSbp = 0;
    private double currentRawDbp = 0;
    private double currentConstrainedSbp = 0;
    private double currentConstrainedDbp = 0;
    private int currentConstraintApplied = 0;
    private int currentClampApplied = 0;
    private int currentOutputValid = 0;
    private int currentUsedSmoothedIbi = 0;
    private double currentSmoothedIbiMs = 0;
    private double currentUsedA = 0;
    private double currentUsedHR = 0;
    private double currentUsedMean = 0;
    private double currentUsedSinPhi = 0;
    private double currentUsedCosPhi = 1;
    private int currentFeatureClampApplied = 0;
    private String currentFeatureClampReason = "init";
    private String currentRejectReason = "init";

    // BP推定結果
    private double lastSinSBP = 0;
    private double lastSinDBP = 0;
    private double lastSinSBPAvg = 0;
    private double lastSinDBPAvg = 0;
    private double lastDisplayedSinSBP = 0;
    private double lastDisplayedSinDBP = 0;
    private double lastDisplayedSinSBPAvg = 0;
    private double lastDisplayedSinDBPAvg = 0;
    private double lastMapRaw = 0;
    private double lastPpRaw = 0;
    private double lastMapSmoothed = 0;
    private double lastPpSmoothed = 0;
    private double lastMapCalibrated = 0;
    private double lastPpCalibrated = 0;
    private int lastPostprocessApplied = 0;

    // 平均用履歴
    private final Deque<Double> sinSbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> sinDbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> displayedSbpHist = new ArrayDeque<>(AVG_BEATS);
    private final Deque<Double> displayedDbpHist = new ArrayDeque<>(AVG_BEATS);
    private final BPPostProcessor postProcessor = new BPPostProcessor(BPPostProcessor.Method.SIN_BP_M);

    // ISO管理
    private int currentISO = 600;

    // BaseLogicへの参照（Sin波パラメータ取得用）
    private BaseLogic logicRef;

    // フレームレート
    private int frameRate = 30;

    // 線形回帰係数（Sin波パラメータのみを使用）
    // 注意: 振幅Aと平均値MeanはLogic1で正規化された値（0-10範囲）を使用
    // 2026-04-10: realtime session 3件から MAP/PP を別々に再学習し、SBP/DBP 係数へ変換。
    // CNAP はオフライン教師ラベルとしてのみ使用し、アプリ実行時の補正入力には使わない。
    // SBP = ALPHA0 + ALPHA1*A + ALPHA2*HR + ALPHA3*Mean + ALPHA4*sinPhi + ALPHA5*cosPhi
    private static final double ALPHA0 = 142.7628950120919; // intercept
    private static final double ALPHA1 = -2.5624732687040424; // M3_A
    private static final double ALPHA2 = 0.1148598656663119; // M3_HR
    private static final double ALPHA3 = 0.05546399935526579; // M3_Mean
    private static final double ALPHA4 = -21.01363796681076; // M3_sinPhi
    private static final double ALPHA5 = 0.6257597683196169; // M3_cosPhi

    // DBP = BETA0 + BETA1*A + BETA2*HR + BETA3*Mean + BETA4*sinPhi + BETA5*cosPhi
    private static final double BETA0 = 75.89358484916787; // intercept
    private static final double BETA1 = 1.269476107026161; // M3_A
    private static final double BETA2 = -0.0581896641241181; // M3_HR
    private static final double BETA3 = -0.038938921280612285; // M3_Mean
    private static final double BETA4 = 10.637750764540236; // M3_sinPhi
    private static final double BETA5 = -0.31912231331633445; // M3_cosPhi

    // prepared_training_data.csv 由来の 1-99 percentile 支持域。
    private static final double A_SUPPORT_MIN = 1.407856;
    private static final double A_SUPPORT_MAX = 5.120968;
    private static final double HR_SUPPORT_MIN = 49.652532;
    private static final double HR_SUPPORT_MAX = 104.529600;
    private static final double MEAN_SUPPORT_MIN = 2.545452;
    private static final double MEAN_SUPPORT_MAX = 7.678100;
    private static final double SIN_PHI_SUPPORT_MIN = -0.409478;
    private static final double SIN_PHI_SUPPORT_MAX = 0.999557;
    private static final double COS_PHI_SUPPORT_MIN = -0.975257;
    private static final double COS_PHI_SUPPORT_MAX = 0.899444;
    // 090943 セッションでは大外れ拍が single-sine fit RMSE > 1.0 に集中していた。
    // SinBP(M) は 1 次調和モデル前提なので、ここを超えた拍は「モデル前提から外れた拍」として除外する。
    private static final double MAX_FIT_RMSE = 1.0;
    private static final int MAX_ALLOWED_FEATURE_CLAMPS = 1;

    public static double[] getSbpCoefficients() {
        return RealtimeMapPpModels.getSinBpMSbpCoefficients();
    }

    public static double[] getDbpCoefficients() {
        return RealtimeMapPpModels.getSinBpMDbpCoefficients();
    }

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
        if (currentTime - lastPeakTime < getAdaptiveRefractoryPeriodMs()) {
            return;
        }

        // バッファが十分でない場合はスキップ
        if (ppgBuffer.size() < 7) {
            return;
        }

        // 移動窓での最大値チェック（前後3フレーム）
        Double[] recent = ppgBuffer.toArray(new Double[0]);
        Long[] recentTimes = timeBuffer.toArray(new Long[0]);
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

        if (isPeak && isProminentPeak(recent, idx)) {
            long interpolatedPeakTime = interpolatePeakTimeMs(recent, recentTimes, idx, currentTime);
            processPeak(recent[idx], interpolatedPeakTime);
        }
    }

    private long interpolatePeakTimeMs(Double[] recent, Long[] recentTimes, int peakIndex, long currentTimeMs) {
        double left = recent[peakIndex - 1];
        double center = recent[peakIndex];
        double right = recent[peakIndex + 1];
        double frameDurationMs = 1000.0 / Math.max(frameRate, 1);
        long baseTime = Math.round(currentTimeMs - 3.0 * frameDurationMs);
        if (recentTimes != null && peakIndex >= 0 && peakIndex < recentTimes.length && recentTimes[peakIndex] != null) {
            long candidate = recentTimes[peakIndex];
            if (Math.abs(candidate - currentTimeMs) <= frameDurationMs * 4.0) {
                baseTime = candidate;
            }
        }
        return PeakInterpolationUtils.interpolatePeakTimeMs(left, center, right, baseTime, frameRate);
    }

    private long getAdaptiveRefractoryPeriodMs() {
        double referenceIbiMs = 0.0;
        if (lastValidIBI > 0) {
            referenceIbiMs = lastValidIBI;
        } else if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            referenceIbiMs = logicRef.getLastSmoothedIbi();
        } else if (currentIBI > 0) {
            referenceIbiMs = currentIBI;
        }
        if (referenceIbiMs <= 0) {
            return REFRACTORY_PERIOD_MS;
        }
        long adaptiveMs = Math.round(referenceIbiMs * ADAPTIVE_REFRACTORY_RATIO);
        return Math.max(REFRACTORY_PERIOD_MS, Math.min(MAX_REFRACTORY_PERIOD_MS, adaptiveMs));
    }

    private boolean isProminentPeak(Double[] recent, int peakIndex) {
        double localMin = Double.POSITIVE_INFINITY;
        double localMax = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, peakIndex - 6); i <= Math.min(recent.length - 1, peakIndex + 3); i++) {
            localMin = Math.min(localMin, recent[i]);
            localMax = Math.max(localMax, recent[i]);
        }
        double prominence = recent[peakIndex] - localMin;
        double localRange = localMax - localMin;
        double requiredProminence = Math.max(MIN_PEAK_PROMINENCE, localRange * MIN_PEAK_PROMINENCE_RATIO);
        return prominence >= requiredProminence;
    }

    /**
     * ピーク検出時の処理（1拍遅延方式）
     */
    private void processPeak(double peakValue, long peakTime) {
        currentRejectReason = "ok";
        currentOutputValid = 0;
        currentConstraintApplied = 0;
        currentClampApplied = 0;
        currentSmoothedIbiMs = 0;
        currentUsedSmoothedIbi = 0;
        currentUsedA = 0;
        currentUsedHR = 0;
        currentUsedMean = 0;
        currentUsedSinPhi = 0;
        currentUsedCosPhi = 1;
        currentFeatureClampApplied = 0;
        currentFeatureClampReason = "ok";
        currentRawSbp = 0;
        currentRawDbp = 0;
        currentConstrainedSbp = 0;
        currentConstrainedDbp = 0;
        // 初回のピークは記録のみ
        if (lastPeakTime == 0) {
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            previousPeakTime = peakTime;
            previousPeakValue = peakValue;
            currentRejectReason = "initial_peak";
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
            currentRejectReason = "empty_beat_samples";
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }

        updateBeatWindowMetrics(beatSamples);

        String beatWindowReason = SignalProcessingUtils.getBeatWindowStabilityReason(beatSamples, ibi, frameRate);
        if (!"ok".equals(beatWindowReason)) {
            currentRejectReason = beatWindowReason;
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
        String invalidBeatReason = SignalProcessingUtils.getInvalidBeatReason(ibi, currentA, lastValidIBI);
        if (!"ok".equals(invalidBeatReason)) {
            currentRejectReason = invalidBeatReason;
            // データを更新して次の拍に備える
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }

        if (currentFitRMSE > MAX_FIT_RMSE) {
            currentRejectReason = "poor_sine_fit";
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
            currentRejectReason = "empty_beat_samples";
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
        currentFitAComponent = a;
        currentFitBComponent = b;

        // 振幅計算
        // 注意: beatSamplesは正規化後の値（0-10範囲）なので、振幅Aも正規化後の範囲内
        currentA = Math.sqrt(a * a + b * b);

        // ゼロ除算チェック
        if (currentA < 1e-6) {
            Log.w(TAG, "Amplitude too small: " + currentA);
            currentA = 0;
            currentPhi = 0;
            currentSinPhi = 0;
            currentCosPhi = 1;
            currentFitRMSE = 0;
            currentRejectReason = "zero_amplitude";
            return;
        }

        // 位相計算
        currentPhi = Math.atan2(b, a);

        // 位相を[0, 2π]に正規化
        if (currentPhi < 0) {
            currentPhi += 2 * Math.PI;
        }
        currentSinPhi = Math.sin(currentPhi);
        currentCosPhi = Math.cos(currentPhi);

        currentIBI = ibi;
        currentFitRMSE = computeFitRMSE(beatSamples, currentMean, a, b);

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
                currentSmoothedIbiMs = lastSmoothedIbi;
                currentUsedSmoothedIbi = 1;
            } else {
                hr = 60000.0 / currentIBI; // フォールバック
            }
        } else {
            hr = 60000.0 / currentIBI; // フォールバック
        }

        StringBuilder featureClampReason = new StringBuilder();
        double usedA = FeatureClampUtils.clampFeature("A", currentA, A_SUPPORT_MIN, A_SUPPORT_MAX, featureClampReason);
        double usedHr = FeatureClampUtils.clampFeature("HR", hr, HR_SUPPORT_MIN, HR_SUPPORT_MAX, featureClampReason);
        double usedMean = FeatureClampUtils.clampFeature("Mean", currentMean, MEAN_SUPPORT_MIN, MEAN_SUPPORT_MAX, featureClampReason);
        double usedSinPhi = FeatureClampUtils.clampFeature(
                "sinPhi", currentSinPhi, SIN_PHI_SUPPORT_MIN, SIN_PHI_SUPPORT_MAX, featureClampReason);
        double usedCosPhi = FeatureClampUtils.clampFeature(
                "cosPhi", currentCosPhi, COS_PHI_SUPPORT_MIN, COS_PHI_SUPPORT_MAX, featureClampReason);
        currentUsedA = usedA;
        currentUsedHR = usedHr;
        currentUsedMean = usedMean;
        currentUsedSinPhi = usedSinPhi;
        currentUsedCosPhi = usedCosPhi;
        currentFeatureClampApplied = featureClampReason.length() > 0 ? 1 : 0;
        currentFeatureClampReason = featureClampReason.length() > 0 ? featureClampReason.toString() : "ok";
        if (FeatureClampUtils.countFeatureClampSegments(featureClampReason) > MAX_ALLOWED_FEATURE_CLAMPS) {
            currentRejectReason = "feature_support_violation";
            return;
        }

        MapPpPrediction prediction = RealtimeMapPpModels.predictSinBpM(
                usedA,
                usedHr,
                usedMean,
                usedSinPhi,
                usedCosPhi);
        double sbp = prediction.sbpFinal;
        double dbp = prediction.dbpFinal;
        currentRawSbp = prediction.sbpModelRaw;
        currentRawDbp = prediction.dbpModelRaw;
        currentConstraintApplied = prediction.sbpModelRaw < prediction.dbpModelRaw + 20.0 ? 1 : 0;
        currentClampApplied = (Math.abs(prediction.sbpFinal - prediction.sbpModelRaw) > 1e-9
                || Math.abs(prediction.dbpFinal - prediction.dbpModelRaw) > 1e-9) ? 1 : 0;
        currentConstrainedSbp = sbp;
        currentConstrainedDbp = dbp;

        // 生理学的妥当性チェック
        String invalidBpReason = SignalProcessingUtils.getInvalidBPReason(sbp, dbp);
        if (!"ok".equals(invalidBpReason)) {
            currentRejectReason = invalidBpReason;
            Log.w(TAG, String.format("Invalid BP: SBP=%.1f, DBP=%.1f", sbp, dbp));
            return;
        }
        currentConstrainedSbp = sbp;
        currentConstrainedDbp = dbp;
        currentOutputValid = 1;
        currentRejectReason = "ok";

        Log.d(TAG + "-Estimate", String.format(
                Locale.US,
                "BP from Model: raw=%.1f/%.1f final=%.1f/%.1f MAP=%.1f PP=%.1f (A=%.2f->%.2f, HR=%.1f->%.1f, Mean=%.2f->%.2f, sinPhi=%.3f->%.3f, cosPhi=%.3f->%.3f, clamp=%s)",
                prediction.sbpModelRaw, prediction.dbpModelRaw,
                sbp, dbp,
                prediction.mapModelRaw, prediction.ppModelRaw,
                currentA, usedA,
                hr, usedHr,
                currentMean, usedMean,
                currentSinPhi, usedSinPhi,
                currentCosPhi, usedCosPhi,
                currentFeatureClampReason));

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
        lastDisplayedSinSBP = displayedSbp;
        lastDisplayedSinDBP = displayedDbp;

        // 履歴に追加
        sinSbpHist.addLast(sbp);
        if (sinSbpHist.size() > AVG_BEATS) {
            sinSbpHist.pollFirst();
        }

        sinDbpHist.addLast(dbp);
        if (sinDbpHist.size() > AVG_BEATS) {
            sinDbpHist.pollFirst();
        }
        displayedSbpHist.addLast(displayedSbp);
        if (displayedSbpHist.size() > DISPLAY_AVG_BEATS) {
            displayedSbpHist.pollFirst();
        }
        displayedDbpHist.addLast(displayedDbp);
        if (displayedDbpHist.size() > DISPLAY_AVG_BEATS) {
            displayedDbpHist.pollFirst();
        }

        // ロバスト平均計算
        double sbpAvg = SignalProcessingUtils.robustAverage(sinSbpHist);
        double dbpAvg = SignalProcessingUtils.robustAverage(sinDbpHist);
        double displayedSbpAvg = SignalProcessingUtils.robustAverage(displayedSbpHist);
        double displayedDbpAvg = SignalProcessingUtils.robustAverage(displayedDbpHist);

        lastSinSBPAvg = sbpAvg;
        lastSinDBPAvg = dbpAvg;
        lastDisplayedSinSBPAvg = displayedSbpAvg;
        lastDisplayedSinDBPAvg = displayedDbpAvg;

        Log.d(TAG + "-Average", String.format(
                "Averaged BP: raw=%.1f/%.1f displayed=%.1f/%.1f (history size: %d)",
                sbpAvg, dbpAvg, displayedSbpAvg, displayedDbpAvg, sinSbpHist.size()));

        // リスナー通知
        if (listener != null) {
            listener.onSinBPModelUpdated(displayedSbp, displayedDbp, displayedSbpAvg, displayedDbpAvg);
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
        lastDisplayedSinSBP = 0;
        lastDisplayedSinDBP = 0;
        lastDisplayedSinSBPAvg = 0;
        lastDisplayedSinDBPAvg = 0;
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
        displayedSbpHist.clear();
        displayedDbpHist.clear();
        postProcessor.reset();

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
        lastDisplayedSinSBP = 0;
        lastDisplayedSinDBP = 0;
        lastDisplayedSinSBPAvg = 0;
        lastDisplayedSinDBPAvg = 0;
        lastMapRaw = 0;
        lastPpRaw = 0;
        lastMapSmoothed = 0;
        lastPpSmoothed = 0;
        lastMapCalibrated = 0;
        lastPpCalibrated = 0;
        lastPostprocessApplied = 0;
        lastValidIBI = 0;
        currentFitAComponent = 0;
        currentFitBComponent = 0;
        currentSinPhi = 0;
        currentCosPhi = 1;
        currentFitRMSE = 0;
        currentBeatSampleCount = 0;
        currentBeatMin = 0;
        currentBeatMax = 0;
        currentBeatRange = 0;
        currentBeatStd = 0;
        currentRawSbp = 0;
        currentRawDbp = 0;
        currentConstrainedSbp = 0;
        currentConstrainedDbp = 0;
        currentConstraintApplied = 0;
        currentClampApplied = 0;
        currentOutputValid = 0;
        currentUsedSmoothedIbi = 0;
        currentSmoothedIbiMs = 0;
        currentUsedA = 0;
        currentUsedHR = 0;
        currentUsedMean = 0;
        currentUsedSinPhi = 0;
        currentUsedCosPhi = 1;
        currentFeatureClampApplied = 0;
        currentFeatureClampReason = "reset";
        currentRejectReason = "reset";

        Log.d(TAG, "SinBPModel reset");
    }

    private void updateBeatWindowMetrics(List<Double> beatSamples) {
        currentBeatSampleCount = beatSamples.size();
        if (beatSamples.isEmpty()) {
            currentBeatMin = 0;
            currentBeatMax = 0;
            currentBeatRange = 0;
            currentBeatStd = 0;
            return;
        }
        currentBeatMin = beatSamples.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        currentBeatMax = beatSamples.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        currentBeatRange = currentBeatMax - currentBeatMin;
        double mean = beatSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = 0.0;
        for (double sample : beatSamples) {
            double delta = sample - mean;
            variance += delta * delta;
        }
        currentBeatStd = Math.sqrt(variance / beatSamples.size());
    }

    private double computeFitRMSE(List<Double> beatSamples, double mean, double a, double b) {
        if (beatSamples == null || beatSamples.isEmpty()) {
            return 0.0;
        }
        double sumSquaredError = 0.0;
        int N = beatSamples.size();
        for (int n = 0; n < N; n++) {
            double angle = 2 * Math.PI * n / N;
            double fitted = mean + a * Math.sin(angle) + b * Math.cos(angle);
            double error = beatSamples.get(n) - fitted;
            sumSquaredError += error * error;
        }
        return Math.sqrt(sumSquaredError / N);
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

    public double getLastDisplayedSinSBP() {
        return lastDisplayedSinSBP;
    }

    public double getLastDisplayedSinDBP() {
        return lastDisplayedSinDBP;
    }

    public double getLastDisplayedSinSBPAvg() {
        return lastDisplayedSinSBPAvg;
    }

    public double getLastDisplayedSinDBPAvg() {
        return lastDisplayedSinDBPAvg;
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
        return currentUsedHR;
    }

    public double getCurrentUsedAmplitude() {
        return currentUsedA;
    }

    public double getCurrentUsedMean() {
        return currentUsedMean;
    }

    public double getCurrentFitAComponent() {
        return currentFitAComponent;
    }

    public double getCurrentFitBComponent() {
        return currentFitBComponent;
    }

    public double getCurrentSinPhi() {
        return currentSinPhi;
    }

    public double getCurrentCosPhi() {
        return currentCosPhi;
    }

    public double getCurrentUsedSinPhi() {
        return currentUsedSinPhi;
    }

    public double getCurrentUsedCosPhi() {
        return currentUsedCosPhi;
    }

    public double getCurrentFitRMSE() {
        return currentFitRMSE;
    }

    public int getCurrentBeatSampleCount() {
        return currentBeatSampleCount;
    }

    public double getCurrentBeatMin() {
        return currentBeatMin;
    }

    public double getCurrentBeatMax() {
        return currentBeatMax;
    }

    public double getCurrentBeatRange() {
        return currentBeatRange;
    }

    public double getCurrentBeatStd() {
        return currentBeatStd;
    }

    public double getCurrentSystoleRatio() {
        return currentSystoleRatio;
    }

    public double getCurrentDiastoleRatio() {
        return currentDiastoleRatio;
    }

    public double getCurrentRawSbp() {
        return currentRawSbp;
    }

    public double getCurrentRawDbp() {
        return currentRawDbp;
    }

    public double getCurrentConstrainedSbp() {
        return currentConstrainedSbp;
    }

    public double getCurrentConstrainedDbp() {
        return currentConstrainedDbp;
    }

    public int getCurrentConstraintApplied() {
        return currentConstraintApplied;
    }

    public int getCurrentClampApplied() {
        return currentClampApplied;
    }

    public int getCurrentOutputValid() {
        return currentOutputValid;
    }

    public int getCurrentFeatureClampApplied() {
        return currentFeatureClampApplied;
    }

    public int getCurrentUsedSmoothedIbi() {
        return currentUsedSmoothedIbi;
    }

    public double getCurrentSmoothedIbiMs() {
        return currentSmoothedIbiMs;
    }

    public String getCurrentRejectReason() {
        return currentRejectReason;
    }

    public String getCurrentFeatureClampReason() {
        return currentFeatureClampReason;
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

}
