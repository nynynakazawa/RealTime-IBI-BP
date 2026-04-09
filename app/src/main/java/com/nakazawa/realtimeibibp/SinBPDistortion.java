package com.nakazawa.realtimeibibp;

import android.util.Log;
import com.nakazawa.realtimeibibp.bp.FeatureClampUtils;
import com.nakazawa.realtimeibibp.bp.PeakInterpolationUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 非対称サイン波モデル残差に基づくリアルタイム血圧推定器（SBP/DBP）
 * 【SinBP(D) - Distortion based】
 * 
 * アルゴリズムの要点：
 * 1. PPG波形を非対称サイン波モデルで近似（収縮期1/3:拡張期2/3の時間比）
 * 2. 振幅A、周期IBI（実測peak-to-peak）を抽出
 * 3. 歪み指標Eで理想非対称サイン波形からの外れを評価
 * 4. BaseLogicからRTBP特徴量（A・HR・relTTP）を取得
 * 5. Stiffness_sin = E√A を計算し、RTBP base を残差補正する
 * 
 * 非対称サイン波モデル（理論的背景）：
 * - t=0でピーク、谷までが2/3T（拡張期）、谷から次のピークまでが1/3T（収縮期）
 * - 三角関数の合成により非対称性を実現
 * - ピーク位置をt=0, T（IBI）に整列
 * - 実測IBIと毎拍同期し、PPG/動脈波の生理学的非対称性を反映
 * - 周波数分解に依存せず、低FPS環境（30fps）やノイズに対して頑健
 */
public class SinBPDistortion {
    private static final String TAG = "SinBPDistortion";

    // バッファサイズ（30fps × 3秒 = 90サンプル）
    private static final int BUFFER_SIZE = 90;

    // 平均用ウィンドウ（10拍）
    private static final int AVG_BEATS = 10;

    // サイン波フィット用のサンプル数
    private static final int FIT_SAMPLES = 64;

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
    private double lastValidIBI = 0; // 異常値検出用

    // 1拍遅延処理用：前の拍のデータを保持
    private long previousPeakTime = 0;
    private double previousPeakValue = 0;
    private List<Double> previousBeatSamples = null;
    private Long[] previousBeatTimes = null;

    // 動的な収縮期/拡張期比率（1拍遅延で計算）
    private double currentSystoleRatio = 1.0 / 3.0; // 初期値は1/3（デフォルト）
    private double currentDiastoleRatio = 2.0 / 3.0; // 初期値は2/3（デフォルト）

    // 拍ごとの結果
    private double currentA = 0; // 振幅
    private double currentIBI = 0; // IBI (ms)
    private double currentPhi = 0; // 位相
    private double currentE = 0; // 歪み指標
    private double currentRegressionAmplitude = 0; // 回帰用の生波形振幅
    private double currentFitAComponent = 0;
    private double currentFitBComponent = 0;
    private double currentSinPhi = 0;
    private double currentCosPhi = 1;
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
    private double currentUsedRegressionAmplitude = 0;
    private double currentUsedHR = 0;
    private double currentUsedValleyToPeakRelTTP = 0;
    private double currentUsedPeakToValleyRelTTP = 0;
    private double currentUsedDistortion = 0;
    private double currentStiffnessSin = 0;
    private double currentUsedStiffnessSin = 0;
    private double currentBaseSbp = 0;
    private double currentBaseDbp = 0;
    private double currentSbpCorrection = 0;
    private double currentDbpCorrection = 0;
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
    private final BPPostProcessor postProcessor = new BPPostProcessor(BPPostProcessor.Method.SIN_BP_D);

    // ISO管理
    private int currentISO = 600;

    // BaseLogicへの参照
    private BaseLogic logicRef;

    // フレームレート
    private int frameRate = 30;

    // 固定係数（2026-04-09時点）
    // SinBP(D) は RTBP を第1段の base とし、第2段で Stiffness_sin = E√A と E を加える。
    // 補正係数は prepared_training_data.csv 上で RTBP 残差を [Stiffness_sin, E] に回帰して得た。
    private static final double[] RTBP_SBP_BASE = RealtimeBP.getSbpCoefficients();
    private static final double[] RTBP_DBP_BASE = RealtimeBP.getDbpCoefficients();
    private static final double GAMMA0 = 2.690652753209871;
    private static final double GAMMA1 = 2.537295192342401;
    private static final double GAMMA2 = -1.807192089982415;
    private static final double DELTA0 = 13.061043372954417;
    private static final double DELTA1 = 2.632014752144330;
    private static final double DELTA2 = -4.290118500197587;

    // prepared_training_data.csv から求めた支持域。
    // A / HR / relTTP は 1-99 percentile を使用する。
    // E は「残差が大きすぎる拍だけを抑える」ため上限のみ使う。低残差拍を下から押し上げると
    // clean beat を人工的に歪ませるため、下限側は拘束しない。
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

    public static double[] getSbpCoefficients() {
        return new double[] {
                RTBP_SBP_BASE[0] + GAMMA0,
                RTBP_SBP_BASE[1],
                RTBP_SBP_BASE[2],
                RTBP_SBP_BASE[3],
                RTBP_SBP_BASE[4],
                GAMMA1,
                GAMMA2
        };
    }

    public static double[] getDbpCoefficients() {
        return new double[] {
                RTBP_DBP_BASE[0] + DELTA0,
                RTBP_DBP_BASE[1],
                RTBP_DBP_BASE[2],
                RTBP_DBP_BASE[3],
                RTBP_DBP_BASE[4],
                DELTA1,
                DELTA2
        };
    }

    public static double[] getSbpBaseCoefficients() {
        return new double[] {
                RTBP_SBP_BASE[0], RTBP_SBP_BASE[1], RTBP_SBP_BASE[2], RTBP_SBP_BASE[3], RTBP_SBP_BASE[4]
        };
    }

    public static double[] getDbpBaseCoefficients() {
        return new double[] {
                RTBP_DBP_BASE[0], RTBP_DBP_BASE[1], RTBP_DBP_BASE[2], RTBP_DBP_BASE[3], RTBP_DBP_BASE[4]
        };
    }

    public static double[] getSbpCorrectionCoefficients() {
        return new double[] { GAMMA0, GAMMA1, GAMMA2 };
    }

    public static double[] getDbpCorrectionCoefficients() {
        return new double[] { DELTA0, DELTA1, DELTA2 };
    }

    // 理想曲線データ（UI表示用）
    private double currentMean = 0;
    private double currentAmplitude = 0;
    private double currentIBIms = 0;
    private long idealCurveStartTime = 0; // 理想曲線の開始時刻（前の拍のピーク時刻）
    private long idealCurveEndTime = 0; // 理想曲線の終了時刻（前の拍の終了時刻 = 現在のピーク時刻）
    private boolean hasIdealCurve = false;
    private double currentScaledMin = Double.NaN;
    private double currentScaledMax = Double.NaN;
    private double currentIdealMin = Double.NaN;
    private double currentIdealMax = Double.NaN;
    private boolean hasScaledCurve = false;

    // 位相フィルタリング用
    private final Deque<Double> phaseHistory = new ArrayDeque<>(3);
    private double lastPhaseShift = 0.0;
    private static final double MAX_PHASE_CHANGE = Math.PI / 4; // 最大位相変化量
    private int consecutiveFailedSyncs = 0; // 連続して同期に失敗した回数
    private static final int MAX_FAILED_SYNCS = 3; // 最大連続失敗回数（この回数以上でリセット）

    // 処理時間遅延補正用
    private static final long PROCESSING_DELAY_MS = 100; // 処理時間による遅延（ミリ秒）

    // リスナー
    public interface SinBPDistortionListener {
        void onSinBPUpdated(double sinSbp, double sinDbp,
                double sinSbpAvg, double sinDbpAvg);
    }

    private SinBPDistortionListener listener;

    /**
     * リスナーを設定
     */
    public void setListener(SinBPDistortionListener l) {
        this.listener = l;
    }

    /**
     * 理想曲線データが利用可能かチェック
     */
    public boolean hasIdealCurve() {
        return hasIdealCurve;
    }

    /**
     * 拍内の相対位置から理想曲線の値を取得（時刻に依存しない）
     * 
     * @param relativePosition 拍内の相対位置（0.0～1.0、0.0がピーク開始、1.0が次のピーク）
     * @return 理想曲線の値（理想曲線が利用できない場合はNaN）
     */
    public double getIdealCurveValueByRelativePosition(double relativePosition) {
        if (!hasIdealCurve || currentIBIms <= 0) {
            return Double.NaN;
        }

        // 相対位置を周期内の時間に変換
        double tNorm = relativePosition * currentIBIms;

        // 非対称サイン波基底を使用（1拍遅延で計算された動的な比率を使用）
        // currentSystoleRatioとcurrentDiastoleRatioは、processPeakで計算された
        // 前の拍のデータから計算された動的な比率（1拍遅延で非対称係数を計算したもの）
        double sNorm = asymmetricSineBasis(tNorm, currentIBIms);

        // 理想波形: mean + A * s_norm（calculateDistortionと同じロジック）
        // これにより、1拍遅延で非対称係数を計算した理想曲線の値が得られる
        return currentMean + currentAmplitude * sNorm;
    }

    /**
     * 実測波形に合わせたスケーリング情報を更新
     */
    public void updateScaledCurveRange(double observedMin, double observedMax,
            double idealMin, double idealMax) {
        if (Double.isNaN(observedMin) || Double.isNaN(observedMax) || observedMax <= observedMin ||
                Double.isNaN(idealMin) || Double.isNaN(idealMax) || idealMax <= idealMin) {
            hasScaledCurve = false;
            return;
        }
        currentScaledMin = observedMin;
        currentScaledMax = observedMax;
        currentIdealMin = idealMin;
        currentIdealMax = idealMax;
        hasScaledCurve = true;
    }

    /**
     * スケール済み理想曲線の値を取得
     */
    public double getScaledIdealCurveValueByRelativePosition(double relativePosition) {
        double baseValue = getIdealCurveValueByRelativePosition(relativePosition);
        if (Double.isNaN(baseValue)) {
            return Double.NaN;
        }
        if (!hasScaledCurve) {
            return baseValue;
        }
        double scale = (currentScaledMax - currentScaledMin) / (currentIdealMax - currentIdealMin);
        return currentScaledMin + (baseValue - currentIdealMin) * scale;
    }

    /**
     * 現在処理中の拍のサンプル数を取得（理想曲線生成用）
     * 
     * @return サンプル数、または0（理想曲線が利用できない場合）
     */
    public int getCurrentBeatSampleCount() {
        if (!hasIdealCurve || idealCurveStartTime == 0 || idealCurveEndTime == 0) {
            return 0;
        }

        // 理想曲線の時間範囲内のサンプル数を計算（30fps想定）
        double frameInterval = 1000.0 / 30.0;
        long duration = idealCurveEndTime - idealCurveStartTime;
        return (int) Math.ceil(duration / frameInterval);
    }

    /**
     * 理想曲線の開始時刻を取得（UI表示用）
     */
    public long getIdealCurveStartTime() {
        return idealCurveStartTime;
    }

    /**
     * 理想曲線の終了時刻を取得（UI表示用）
     */
    public long getIdealCurveEndTime() {
        return idealCurveEndTime;
    }

    /**
     * 現在の理想曲線パラメータを取得
     */
    public double getCurrentMean() {
        return currentMean;
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
     * ピーク検出（改良版：移動窓 + 不応期）
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
     * ピーク検出時の処理（1拍遅延方式：次のピークが来た時点で、その拍のrPPGから比率を計算してSin近似を計算）
     * 
     * 動作フロー：
     * - 1拍目：記録のみ
     * - 2拍目：1拍目のrPPGから比率を計算し、1拍目のSin近似を計算して表示 + 2拍目を記録
     * - 3拍目：2拍目のrPPGから比率を計算し、2拍目のSin近似を計算して表示 + 3拍目を記録
     * - ... を永遠に続ける
     */
    private void processPeak(double peakValue, long peakTime) {
        currentRejectReason = "ok";
        currentOutputValid = 0;
        currentConstraintApplied = 0;
        currentClampApplied = 0;
        currentSmoothedIbiMs = 0;
        currentUsedSmoothedIbi = 0;
        currentUsedRegressionAmplitude = 0;
        currentUsedHR = 0;
        currentUsedValleyToPeakRelTTP = 0;
        currentUsedPeakToValleyRelTTP = 0;
        currentUsedDistortion = 0;
        currentStiffnessSin = 0;
        currentUsedStiffnessSin = 0;
        currentBaseSbp = 0;
        currentBaseDbp = 0;
        currentSbpCorrection = 0;
        currentDbpCorrection = 0;
        currentFeatureClampApplied = 0;
        currentFeatureClampReason = "ok";
        currentRawSbp = 0;
        currentRawDbp = 0;
        currentConstrainedSbp = 0;
        currentConstrainedDbp = 0;
        Log.d(TAG + "-ProcessPeak", String.format(
                "processPeak() called: peakValue=%.2f, peakTime=%d, lastPeakTime=%d, previousPeakTime=%d",
                peakValue, peakTime, lastPeakTime, previousPeakTime));

        // 初回のピークは記録のみ
        if (lastPeakTime == 0) {
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            previousPeakTime = peakTime;
            previousPeakValue = peakValue;
            currentRejectReason = "initial_peak";
            Log.d(TAG + "-ProcessPeak", "First peak detected - recording only");
            return;
        }

        // 2回目以降：前の拍（previousPeakTime → lastPeakTime）のデータが完全になったので処理
        double ibi = lastPeakTime - previousPeakTime;
        Log.d(TAG + "-ProcessPeak", String.format(
                "Processing beat: previousPeakTime=%d, lastPeakTime=%d, IBI=%.1f",
                previousPeakTime, lastPeakTime, ibi));

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

        // その拍のrPPGデータから収縮期/拡張期の比率を計算
        double[] ratios = SignalProcessingUtils.calculateSystoleDiastoleRatio(beatSamples, beatTimes, ibi);
        double systoleRatio = ratios[0];
        double diastoleRatio = ratios[1];

        // 計算した比率を保存（理想曲線表示用）
        currentSystoleRatio = systoleRatio;
        currentDiastoleRatio = diastoleRatio;

        // その拍のサイン波フィット（動的な比率を使用）
        fitSineWave(beatSamples, ibi);

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

        // その拍の歪み計算（動的な比率を使用）
        calculateDistortion(beatSamples, beatTimes, currentA, currentPhi, ibi, systoleRatio, diastoleRatio);

        Log.d(TAG + "-ProcessPeak", String.format(
                "After distortion calc: hasIdealCurve=%b, startTime=%d, endTime=%d",
                hasIdealCurve, idealCurveStartTime, idealCurveEndTime));

        // その拍のBP推定
        estimateBP(ibi, currentE);

        // データを更新して次の拍に備える
        previousPeakTime = lastPeakTime;
        previousPeakValue = lastPeakValue;
        lastPeakValue = peakValue;
        lastPeakTime = peakTime;
        lastValidIBI = ibi;

        Log.d(TAG + "-ProcessPeak", String.format(
                "Beat processed: IBI=%.1f, A=%.2f, E=%.4f, ratio=%.3f:%.3f, hasIdealCurve=%b",
                ibi, currentA, currentE, systoleRatio, diastoleRatio, hasIdealCurve));
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
     * サイン波フィッティング
     * 注意: beatSamplesはLogic1で正規化された値（0-10範囲）を使用
     */
    private void fitSineWave(List<Double> beatSamples, double ibi) {
        // 64点にリサンプリング
        double[] resampled = SignalProcessingUtils.resampleBeat(beatSamples, FIT_SAMPLES);

        int N = resampled.length;
        double a = 0, b = 0;

        // DFT風の内積計算
        for (int n = 0; n < N; n++) {
            double angle = 2 * Math.PI * n / N;
            a += resampled[n] * Math.sin(angle);
            b += resampled[n] * Math.cos(angle);
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

        Log.d(TAG + "-Fit", String.format("Fitted: A=%.3f, phi=%.3f rad (%.1f deg)",
                currentA, currentPhi, Math.toDegrees(currentPhi)));
    }

    /**
     * 非対称サイン波基底関数（動的な比率に対応）
     * 三角関数の合成により、t=0でピーク、谷までがdiastoleRatio、谷から次のピークまでがsystoleRatioとなる波形を生成
     * 
     * @param t             時刻（ms）、0からT（IBI）の範囲
     * @param T             周期（IBI、ms）
     * @param systoleRatio  収縮期の比率（0-1）
     * @param diastoleRatio 拡張期の比率（0-1、通常は1-systoleRatio）
     * @return 正規化された波形値 [0,1]（t=0で最大値1）
     */
    // 位相シフト定数（クラスレベルで定義）
    private static final double PHASE_SHIFT = -Math.PI / 4.0;

    private double asymmetricSineBasis(double t, double T, double systoleRatio, double diastoleRatio) {
        // 周期内に正規化（0〜1）
        double phase = (t % T) / T;

        // 非対称波形の生成
        // t=0でピーク、phase=diastoleRatioで谷、phase=1で次のピーク（周期T内で1サイクル完結）

        double value;
        if (phase <= diastoleRatio) {
            // ピーク→谷: diastoleRatioの時間
            // phase: 0→diastoleRatio を 0→π にマッピング
            double angle = phase * (1.0 / diastoleRatio) * Math.PI; // 0→π
            value = Math.cos(angle + PHASE_SHIFT); // cos(0)=1 → cos(π)=-1（左シフト）
        } else {
            // 谷→ピーク: systoleRatioの時間
            // phase: diastoleRatio→1 を π→2π にマッピング
            double angle = Math.PI + (phase - diastoleRatio) * (1.0 / systoleRatio) * Math.PI; // π→2π
            value = Math.cos(angle + PHASE_SHIFT); // cos(π)=-1 → cos(2π)=1（左シフト）
        }

        // [0,1]に正規化（-1〜1 → 0〜1）
        return (value + 1.0) / 2.0;
    }

    /**
     * 非対称サイン波基底関数（現在の比率を使用するオーバーロード）
     */
    private double asymmetricSineBasis(double t, double T) {
        return asymmetricSineBasis(t, T, currentSystoleRatio, currentDiastoleRatio);
    }

    /**
     * 歪み指標の計算（動的な比率を使用）
     * 非対称サイン波モデルからの残差を計算
     * 注意: beatSamplesはLogic1で正規化された値（0-10範囲）を使用
     * 
     * @param beatSamples   1拍分のサンプル値
     * @param beatTimes     1拍分の時刻（ms）
     * @param A             振幅
     * @param phi           位相
     * @param ibi           周期（ms）
     * @param systoleRatio  収縮期比率
     * @param diastoleRatio 拡張期比率
     */
    private void calculateDistortion(List<Double> beatSamples, List<Long> beatTimes, double A, double phi, double ibi,
            double systoleRatio, double diastoleRatio) {
        if (A < 1e-6) {
            currentE = 0;
            return;
        }

        // サンプルの平均値を計算（DCオフセット）
        // 注意: beatSamplesは正規化後の値（0-10範囲）なので、meanも0-10範囲
        double mean = 0;
        for (double sample : beatSamples) {
            mean += sample;
        }
        mean /= beatSamples.size();

        // 非対称サイン波形再構成との残差を計算
        double sumSquaredError = 0;
        int N = beatSamples.size();
        double T = ibi; // 周期（ms）

        for (int i = 0; i < N; i++) {
            // 時刻をms単位で計算（t=0がピーク）
            double t = (double) i * T / N;

            // 非対称サイン波基底を使用（動的な比率）
            double sNorm = asymmetricSineBasis(t, T, systoleRatio, diastoleRatio);

            // 理想波形の再構成: mean + A * s_norm
            double idealValue = mean + A * sNorm;

            double error = beatSamples.get(i) - idealValue;
            sumSquaredError += error * error;
        }

        currentE = Math.sqrt(sumSquaredError / N); // RMS誤差

        // 理想曲線パラメータを保存（UI表示用）
        currentMean = mean;
        currentAmplitude = A;
        currentIBIms = T;

        // ピーク検出時に理想曲線の位相を再同期（位相フィルタリング適用）
        // 実測値のピーク位置を正確に検出して理想曲線と同期
        // 低心拍数では1拍のサンプル数が多くなるため、より正確な位相探索が必要
        double peakPhaseTime = findPeakPhaseInBeat(beatSamples, beatTimes, T, systoleRatio, diastoleRatio);

        // 位相調整（実測値と理想曲線の位相整合）
        double phaseOffset = calculatePhaseOffset(peakPhaseTime, T);

        Log.d(TAG + "-PhaseAdjust", String.format(
                "Phase adjustment: peakPhaseTime=%.1f, offset=%.1f, T=%.1f, HR=%.1f",
                peakPhaseTime, phaseOffset, T, 60000.0 / T));

        // 位相フィルタリング + 重み付け調整を適用（心拍数に応じて動的に調整）
        double filteredPhaseTime = applyPhaseFiltering(phaseOffset, T);

        // 理想曲線の開始時刻と終了時刻を設定（前の拍の範囲）
        // 前の拍のピーク時刻から次のピーク時刻（現在のピーク時刻）までが理想曲線の範囲
        idealCurveStartTime = previousPeakTime;
        idealCurveEndTime = lastPeakTime;

        hasIdealCurve = true;

        Log.d(TAG + "-Distortion", String.format(
                "E=%.4f (mean=%.2f, Asymmetric sine model: systole %.3f, diastole %.3f)",
                currentE, mean, systoleRatio, diastoleRatio));
    }

    /**
     * BP推定
     * 注意: 振幅AはLogic1で正規化された値（0-10範囲）を使用
     */
    private void estimateBP(double ibi, double E) {
        // smoothedIBIからHRを計算（RealtimeBPと同様の方法）
        double hr = 0.0;
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                hr = 60000.0 / lastSmoothedIbi; // smoothedIBIから計算
                currentSmoothedIbiMs = lastSmoothedIbi;
                currentUsedSmoothedIbi = 1;
            } else {
                hr = 60000.0 / ibi; // フォールバック
            }
        } else {
            hr = 60000.0 / ibi; // フォールバック
        }

        // SinBP(D) は RTBP features を base とし、Stiffness_sin と E で補正する。
        double valleyToPeakRelTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        double peakToValleyRelTTP = (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
        double regressionAmplitude = (logicRef != null) ? logicRef.averageValleyToPeakAmplitude : 0.0;
        currentRegressionAmplitude = regressionAmplitude;

        String relTtpReason = SignalProcessingUtils.getRelTtpConsistencyReason(
                valleyToPeakRelTTP, peakToValleyRelTTP);
        if (!"ok".equals(relTtpReason)) {
            currentRejectReason = relTtpReason;
            return;
        }

        StringBuilder featureClampReason = new StringBuilder();
        double usedRegressionAmplitude = FeatureClampUtils.clampFeature(
                "A", regressionAmplitude, A_SUPPORT_MIN, A_SUPPORT_MAX, featureClampReason);
        double usedHr = FeatureClampUtils.clampFeature("HR", hr, HR_SUPPORT_MIN, HR_SUPPORT_MAX, featureClampReason);
        double usedValleyToPeakRelTTP = FeatureClampUtils.clampFeature(
                "V2P_relTTP", valleyToPeakRelTTP, V2P_SUPPORT_MIN, V2P_SUPPORT_MAX, featureClampReason);
        double usedPeakToValleyRelTTP = FeatureClampUtils.clampFeature(
                "P2V_relTTP", peakToValleyRelTTP, P2V_SUPPORT_MIN, P2V_SUPPORT_MAX, featureClampReason);
        double usedE = FeatureClampUtils.clampUpperFeature("E", E, E_SUPPORT_MAX, featureClampReason);
        double stiffnessSin = E * Math.sqrt(Math.max(regressionAmplitude, 0.0));
        double usedStiffnessSin = FeatureClampUtils.clampUpperFeature(
                "Stiffness", usedE * Math.sqrt(Math.max(usedRegressionAmplitude, 0.0)),
                STIFFNESS_SUPPORT_MAX, featureClampReason);
        currentUsedRegressionAmplitude = usedRegressionAmplitude;
        currentUsedHR = usedHr;
        currentUsedValleyToPeakRelTTP = usedValleyToPeakRelTTP;
        currentUsedPeakToValleyRelTTP = usedPeakToValleyRelTTP;
        currentUsedDistortion = usedE;
        currentStiffnessSin = stiffnessSin;
        currentUsedStiffnessSin = usedStiffnessSin;
        currentFeatureClampApplied = featureClampReason.length() > 0 ? 1 : 0;
        currentFeatureClampReason = featureClampReason.length() > 0 ? featureClampReason.toString() : "ok";
        if (FeatureClampUtils.countFeatureClampSegments(featureClampReason) > MAX_ALLOWED_FEATURE_CLAMPS) {
            currentRejectReason = "feature_support_violation";
            return;
        }

        double sbpBase = RTBP_SBP_BASE[0] + RTBP_SBP_BASE[1] * usedRegressionAmplitude + RTBP_SBP_BASE[2] * usedHr +
                RTBP_SBP_BASE[3] * usedValleyToPeakRelTTP + RTBP_SBP_BASE[4] * usedPeakToValleyRelTTP;
        double dbpBase = RTBP_DBP_BASE[0] + RTBP_DBP_BASE[1] * usedRegressionAmplitude + RTBP_DBP_BASE[2] * usedHr +
                RTBP_DBP_BASE[3] * usedValleyToPeakRelTTP + RTBP_DBP_BASE[4] * usedPeakToValleyRelTTP;
        double sbpCorrection = GAMMA0 + GAMMA1 * usedStiffnessSin + GAMMA2 * usedE;
        double dbpCorrection = DELTA0 + DELTA1 * usedStiffnessSin + DELTA2 * usedE;
        currentBaseSbp = sbpBase;
        currentBaseDbp = dbpBase;
        currentSbpCorrection = sbpCorrection;
        currentDbpCorrection = dbpCorrection;

        double sbpRefined = sbpBase + sbpCorrection;
        double dbpRefined = dbpBase + dbpCorrection;
        currentRawSbp = sbpRefined;
        currentRawDbp = dbpRefined;

        // 制約適用
        currentConstraintApplied = 0;
        if (sbpRefined < dbpRefined + 20) {
            sbpRefined = dbpRefined + 20;
            currentConstraintApplied = 1;
        }
        currentConstrainedSbp = sbpRefined;
        currentConstrainedDbp = dbpRefined;
        double clampedSbp = SignalProcessingUtils.clamp(sbpRefined, 60, 200);
        double clampedDbp = SignalProcessingUtils.clamp(dbpRefined, 40, 150);
        currentClampApplied = (Math.abs(clampedSbp - sbpRefined) > 1e-9 || Math.abs(clampedDbp - dbpRefined) > 1e-9) ? 1 : 0;
        sbpRefined = clampedSbp;
        dbpRefined = clampedDbp;
        currentConstrainedSbp = sbpRefined;
        currentConstrainedDbp = dbpRefined;

        // 生理学的妥当性チェック
        String invalidBpReason = SignalProcessingUtils.getInvalidBPReason(sbpRefined, dbpRefined);
        if (!"ok".equals(invalidBpReason)) {
            currentRejectReason = invalidBpReason;
            Log.w(TAG, String.format("Invalid BP: SBP=%.1f, DBP=%.1f", sbpRefined, dbpRefined));
            return;
        }
        currentOutputValid = 1;
        currentRejectReason = "ok";

        Log.d(TAG + "-Estimate", String.format(
                Locale.US,
                "BP: SBP=%.1f, DBP=%.1f (base=%.1f/%.1f, corr=%.1f/%.1f, A=%.3f->%.3f, HR=%.1f->%.1f, V2P=%.3f->%.3f, P2V=%.3f->%.3f, Stiff=%.3f->%.3f, E=%.3f->%.3f, clamp=%s)",
                sbpRefined, dbpRefined,
                sbpBase, dbpBase,
                sbpCorrection, dbpCorrection,
                regressionAmplitude, usedRegressionAmplitude,
                hr, usedHr,
                valleyToPeakRelTTP, usedValleyToPeakRelTTP,
                peakToValleyRelTTP, usedPeakToValleyRelTTP,
                stiffnessSin, usedStiffnessSin,
                E, usedE,
                currentFeatureClampReason));

        // 履歴更新と平均計算
        updateHistory(sbpRefined, dbpRefined);
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
        double displayedSbp = postprocess.postprocessApplied == 1 ? postprocess.sbpCalibrated : sbp;
        double displayedDbp = postprocess.postprocessApplied == 1 ? postprocess.dbpCalibrated : dbp;
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
        if (displayedSbpHist.size() > AVG_BEATS) {
            displayedSbpHist.pollFirst();
        }
        displayedDbpHist.addLast(displayedDbp);
        if (displayedDbpHist.size() > AVG_BEATS) {
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
            listener.onSinBPUpdated(displayedSbp, displayedDbp, displayedSbpAvg, displayedDbpAvg);
        }
    }

    /**
     * 位相オフセットを計算（実測値のピーク位置に基づく）
     */
    private double calculatePhaseOffset(double peakPhaseTime, double T) {
        // 実測値のピーク位置を理想曲線のピーク位置（t=0）に合わせる
        // 理想曲線のピークはt=0で発生するため、実測値のピーク位置を0にシフト
        double phaseOffset = -peakPhaseTime;

        // 周期内に正規化
        while (phaseOffset < 0) {
            phaseOffset += T;
        }
        while (phaseOffset >= T) {
            phaseOffset -= T;
        }

        Log.d(TAG + "-PhaseOffset", String.format(
                "Phase offset calculation: peakTime=%.1f, offset=%.1f, T=%.1f",
                peakPhaseTime, phaseOffset, T));

        return phaseOffset;
    }

    /**
     * 位相フィルタリング + 重み付け調整を適用（心拍数に応じて動的に調整）
     * 
     * @param rawPhaseTime 生の位相オフセット
     * @param ibi          周期（ms）
     * @return フィルタリング後の位相オフセット
     */
    private double applyPhaseFiltering(double rawPhaseTime, double ibi) {
        // 心拍数を計算
        double hr = 60000.0 / ibi;

        // 低心拍数（HR < 70）では、位相フィルタリングの重みを下げて、より迅速に同期を修正
        // 高心拍数（HR > 90）では、従来通り強いフィルタリングを適用
        double filterWeight;
        if (hr < 70) {
            // 低心拍数：重みを下げて（50%）、より迅速に同期を修正
            filterWeight = 0.5;
        } else if (hr < 90) {
            // 中程度：60%の重み
            filterWeight = 0.6;
        } else {
            // 高心拍数：従来通り70%の重み
            filterWeight = 0.7;
        }

        // 1. 位相差を計算
        double phaseDifference = rawPhaseTime - lastPhaseShift;

        // 2. 急激な変化を制限（低心拍数ではより大きな変化を許容）
        double maxChange = MAX_PHASE_CHANGE;
        if (hr < 70) {
            // 低心拍数では、より大きな位相変化を許容（周期が長いため）
            maxChange = MAX_PHASE_CHANGE * 1.5;
        }

        if (Math.abs(phaseDifference) > maxChange) {
            double sign = Math.signum(phaseDifference);
            phaseDifference = sign * maxChange;
            consecutiveFailedSyncs++;
        } else {
            // 同期が成功した場合はリセット
            consecutiveFailedSyncs = 0;
        }

        // 3. 連続して同期に失敗した場合は、より積極的に位相を修正
        if (consecutiveFailedSyncs >= MAX_FAILED_SYNCS) {
            Log.w(TAG + "-PhaseFilter", String.format(
                    "Too many failed syncs (%d), resetting phase", consecutiveFailedSyncs));
            // 位相をリセットして、現在の位相を直接使用
            lastPhaseShift = rawPhaseTime;
            phaseHistory.clear();
            phaseHistory.addLast(rawPhaseTime);
            consecutiveFailedSyncs = 0;
            return rawPhaseTime;
        }

        // 4. 重み付け調整（心拍数に応じた動的な重み）
        double adjustedPhaseTime = lastPhaseShift + phaseDifference * filterWeight;

        // 5. 位相履歴に追加
        phaseHistory.addLast(adjustedPhaseTime);
        if (phaseHistory.size() > 3) {
            phaseHistory.pollFirst();
        }

        // 6. 移動平均フィルタ
        double sum = 0.0;
        for (double phase : phaseHistory) {
            sum += phase;
        }
        double filteredPhaseTime = sum / phaseHistory.size();

        // 7. 更新
        lastPhaseShift = filteredPhaseTime;

        Log.d(TAG + "-PhaseFilter", String.format(
                "Phase filtering: raw=%.1f, adjusted=%.1f, filtered=%.1f, HR=%.1f, weight=%.2f",
                rawPhaseTime, adjustedPhaseTime, filteredPhaseTime, hr, filterWeight));

        return filteredPhaseTime;
    }

    /**
     * 1拍分のデータから実測値のピーク位置を正確に検出
     * 低心拍数ではサンプル数が多くなるため、より正確な検出が必要
     * 
     * @param beatSamples   1拍分のサンプル値
     * @param beatTimes     1拍分の時刻（ms）
     * @param T             周期（ms）
     * @param systoleRatio  収縮期比率
     * @param diastoleRatio 拡張期比率
     * @return ピーク位置（ms、0からTの範囲）
     */
    private double findPeakPhaseInBeat(List<Double> beatSamples, List<Long> beatTimes,
            double T, double systoleRatio, double diastoleRatio) {
        int N = beatSamples.size();
        if (N < 3) {
            return 0.0;
        }

        // 実測値のピーク位置を検出（開始点がピークと仮定）
        // ただし、ノイズの影響を考慮して、最初の20%の範囲で最大値を探索
        int searchRange = Math.max(3, (int) (N * 0.2)); // 最初の20%の範囲
        int peakIndex = 0;
        double maxValue = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < searchRange && i < N; i++) {
            if (beatSamples.get(i) > maxValue) {
                maxValue = beatSamples.get(i);
                peakIndex = i;
            }
        }

        // ピーク位置を時刻に変換
        long peakTime = beatTimes.get(peakIndex);
        long startTime = beatTimes.get(0);
        double peakPhaseTime = peakTime - startTime;

        // 周期内に正規化
        while (peakPhaseTime < 0) {
            peakPhaseTime += T;
        }
        while (peakPhaseTime >= T) {
            peakPhaseTime -= T;
        }

        Log.d(TAG + "-PeakFind", String.format(
                "Found peak at index %d, phaseTime=%.1f ms (out of %.1f ms), N=%d",
                peakIndex, peakPhaseTime, T, N));

        return peakPhaseTime;
    }

    /**
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * コンストラクタ
     */
    public SinBPDistortion() {
        // 初期値を設定（UI表示用）
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        lastDisplayedSinSBP = 0;
        lastDisplayedSinDBP = 0;
        lastDisplayedSinSBPAvg = 0;
        lastDisplayedSinDBPAvg = 0;
        Log.d(TAG, "SinBPDistortion initialized");
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

        // 位相フィルタリング用のリセット
        phaseHistory.clear();
        lastPhaseShift = 0.0;
        consecutiveFailedSyncs = 0;

        lastPeakValue = 0;
        lastPeakTime = 0;
        previousPeakTime = 0;
        previousPeakValue = 0;
        previousBeatSamples = null;
        previousBeatTimes = null;
        currentSystoleRatio = 1.0 / 3.0;
        currentDiastoleRatio = 2.0 / 3.0;
        currentA = 0;
        currentIBI = 0;
        currentPhi = 0;
        currentE = 0;
        currentRegressionAmplitude = 0;
        currentFitAComponent = 0;
        currentFitBComponent = 0;
        currentSinPhi = 0;
        currentCosPhi = 1;
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
        currentUsedRegressionAmplitude = 0;
        currentUsedHR = 0;
        currentUsedValleyToPeakRelTTP = 0;
        currentUsedPeakToValleyRelTTP = 0;
        currentUsedDistortion = 0;
        currentStiffnessSin = 0;
        currentUsedStiffnessSin = 0;
        currentBaseSbp = 0;
        currentBaseDbp = 0;
        currentSbpCorrection = 0;
        currentDbpCorrection = 0;
        currentFeatureClampApplied = 0;
        currentFeatureClampReason = "reset";
        currentRejectReason = "reset";
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

        Log.d(TAG, "SinBPDistortion reset with 1-beat delay and dynamic ratio");
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

    public double getCurrentRegressionAmplitude() {
        return currentRegressionAmplitude;
    }

    public double getCurrentIBI() {
        return currentIBI;
    }

    public double getCurrentDistortion() {
        return currentE;
    }

    public double getCurrentHR() {
        return currentUsedHR;
    }

    public double getCurrentValleyToPeakRelTTP() {
        return (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
    }

    public double getCurrentPeakToValleyRelTTP() {
        return (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
    }

    public double getCurrentUsedRegressionAmplitude() {
        return currentUsedRegressionAmplitude;
    }

    public double getCurrentUsedValleyToPeakRelTTP() {
        return currentUsedValleyToPeakRelTTP;
    }

    public double getCurrentUsedPeakToValleyRelTTP() {
        return currentUsedPeakToValleyRelTTP;
    }

    public double getCurrentUsedDistortion() {
        return currentUsedDistortion;
    }

    public double getCurrentStiffnessSin() {
        return currentStiffnessSin;
    }

    public double getCurrentUsedStiffnessSin() {
        return currentUsedStiffnessSin;
    }

    public double getCurrentBaseSbp() {
        return currentBaseSbp;
    }

    public double getCurrentBaseDbp() {
        return currentBaseDbp;
    }

    public double getCurrentSbpCorrection() {
        return currentSbpCorrection;
    }

    public double getCurrentDbpCorrection() {
        return currentDbpCorrection;
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

    public double getCurrentPhi() {
        return currentPhi;
    }

    public double getCurrentSinPhi() {
        return currentSinPhi;
    }

    public double getCurrentCosPhi() {
        return currentCosPhi;
    }

    public double getCurrentFitAComponent() {
        return currentFitAComponent;
    }

    public double getCurrentFitBComponent() {
        return currentFitBComponent;
    }

    public int getCurrentBeatWindowSampleCount() {
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

}
