package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 非対称サイン波モデル残差に基づくリアルタイム血圧推定器（SBP/DBP）
 * 【SinBP(D) - Distortion based】
 * 
 * アルゴリズムの要点：
 * 1. PPG波形を非対称サイン波モデルで近似（収縮期1/3:拡張期2/3の時間比）
 * 2. 振幅A、周期IBI（実測peak-to-peak）を抽出
 * 3. 歪み指標Eで理想非対称サイン波形からの外れを評価
 * 4. BaseLogicからrelTTP（谷→山・山→谷）を取得して血管特性を反映
 * 5. 3段階のBP推定：ベース（A・HR）→血管特性補正（relTTP・Stiffness_sin）→歪み補正（E）
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
    
    // リングバッファ
    private final Deque<Double> ppgBuffer = new ArrayDeque<>(BUFFER_SIZE);
    private final Deque<Long> timeBuffer = new ArrayDeque<>(BUFFER_SIZE);
    
    // 拍検出用
    private double lastPeakValue = 0;
    private long lastPeakTime = 0;
    private double lastValidIBI = 0;  // 異常値検出用
    
    // 1拍遅延処理用：前の拍のデータを保持
    private long previousPeakTime = 0;
    private double previousPeakValue = 0;
    private List<Double> previousBeatSamples = null;
    private Long[] previousBeatTimes = null;
    
    // 動的な収縮期/拡張期比率（1拍遅延で計算）
    private double currentSystoleRatio = 1.0/3.0;  // 初期値は1/3（デフォルト）
    private double currentDiastoleRatio = 2.0/3.0; // 初期値は2/3（デフォルト）
    
    // 拍ごとの結果
    private double currentA = 0;      // 振幅
    private double currentIBI = 0;    // IBI (ms)
    private double currentPhi = 0;    // 位相
    private double currentE = 0;      // 歪み指標
    
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
    
    // BaseLogicへの参照
    private BaseLogic logicRef;
    
    // フレームレート
    private int frameRate = 30;
    
    // 固定係数（ベースBP推定）
    // 注意: 振幅AはLogic1で正規化された値（0-10範囲）を使用
    // 振幅Aが1-10程度なので、係数を大きくする
    private static final double ALPHA0 = 80.0;
    private static final double ALPHA1 = 5.0;   // 0.5 → 5.0 (10倍) - 正規化後0-10範囲
    private static final double ALPHA2 = 0.3;
    private static final double BETA0 = 60.0;
    private static final double BETA1 = 3.0;    // 0.3 → 3.0 (10倍) - 正規化後0-10範囲
    private static final double BETA2 = 0.15;
    
    // 血管特性補正係数（AIを除去、Stiffness_sinを強化、両方のrelTTPを使用）
    private static final double ALPHA3 = 5.0;   // 谷→山relTTP係数
    private static final double ALPHA4 = 3.0;    // 山→谷relTTP係数
    private static final double ALPHA5 = 0.1;     // Stiffness_sin係数（0.01 → 0.1に大幅強化）
    private static final double BETA3 = 3.0;     // 谷→山relTTP係数
    private static final double BETA4 = 2.0;     // 山→谷relTTP係数
    private static final double BETA5 = 0.05;    // Stiffness_sin係数（0.005 → 0.05に大幅強化）
    
    // 第3段：歪み補正係数（Eによる最終補正）
    private static final double ALPHA6 = 0.1;    // SBP歪み補正係数（0.01 → 0.1）
    private static final double BETA6 = 0.05;   // DBP歪み補正係数（0.005 → 0.05）
    
    // 理想曲線データ（UI表示用）
    private double currentMean = 0;
    private double currentAmplitude = 0;
    private double currentIBIms = 0;
    private long idealCurveStartTime = 0;  // 理想曲線の開始時刻（前の拍のピーク時刻）
    private long idealCurveEndTime = 0;    // 理想曲線の終了時刻（前の拍の終了時刻 = 現在のピーク時刻）
    private boolean hasIdealCurve = false;
    
    // 位相フィルタリング用
    private final Deque<Double> phaseHistory = new ArrayDeque<>(3);
    private double lastPhaseShift = 0.0;
    private static final double MAX_PHASE_CHANGE = Math.PI / 4; // 最大位相変化量
    
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
     * 指定時刻での理想曲線の値を取得（1拍遅延対応：前の拍の範囲内でのみ値を返す）
     * @param targetTime 対象時刻（ms）
     * @return 理想曲線の値（範囲外の場合はNaN）
     */
    public double getIdealCurveValue(long targetTime) {
        if (!hasIdealCurve || currentIBIms <= 0 || idealCurveStartTime == 0 || idealCurveEndTime == 0) {
            return Double.NaN;
        }
        
        // 1拍遅延対応：理想曲線は前の拍の範囲内でのみ有効
        if (targetTime < idealCurveStartTime || targetTime > idealCurveEndTime) {
            return Double.NaN;
        }
        
        // 前の拍のピーク時刻からの経過時間を計算
        double elapsedSinceStart = targetTime - idealCurveStartTime;
        
        // 周期内に正規化
        if (elapsedSinceStart < 0) {
            elapsedSinceStart = 0;
        }
        double tNorm = elapsedSinceStart % currentIBIms;
        
        // 非対称サイン波基底を使用
        double sNorm = asymmetricSineBasis(tNorm, currentIBIms);
        
        // 理想波形: mean + A * (s_norm を中心からの偏差として調整)
        double adjustedSNorm;
        if (sNorm >= 0.5) {
            adjustedSNorm = 0.5 + (sNorm - 0.5) * 1.0;
        } else {
            adjustedSNorm = 0.5 + (sNorm - 0.5) * 3.0;
        }
        
        return currentMean + currentAmplitude * adjustedSNorm;
    }
    
    /**
     * 理想曲線の範囲内の時刻に対応する実測波形のエントリーインデックスを計算
     * @param chartStartTime チャート開始時刻（ms）
     * @param frameInterval フレーム間隔（ms）
     * @return [startIndex, endIndex] の配列、またはnull（理想曲線が利用できない場合）
     */
    public int[] getIdealCurveEntryIndices(long chartStartTime, double frameInterval) {
        if (!hasIdealCurve || idealCurveStartTime == 0 || idealCurveEndTime == 0) {
            return null;
        }
        
        // 理想曲線の範囲内の時刻に対応するエントリーインデックスを計算
        int startIndex = (int)Math.max(0, Math.floor((idealCurveStartTime - chartStartTime) / frameInterval));
        int endIndex = (int)Math.ceil((idealCurveEndTime - chartStartTime) / frameInterval);
        
        return new int[]{startIndex, endIndex};
    }
    
    /**
     * 拍内の相対位置から理想曲線の値を取得（時刻に依存しない）
     * @param relativePosition 拍内の相対位置（0.0～1.0、0.0がピーク開始、1.0が次のピーク）
     * @return 理想曲線の値（理想曲線が利用できない場合はNaN）
     */
    public double getIdealCurveValueByRelativePosition(double relativePosition) {
        if (!hasIdealCurve || currentIBIms <= 0) {
            return Double.NaN;
        }
        
        // 相対位置を周期内の時間に変換
        double tNorm = relativePosition * currentIBIms;
        
        // 非対称サイン波基底を使用
        double sNorm = asymmetricSineBasis(tNorm, currentIBIms);
        
        // 理想波形: mean + A * (s_norm を中心からの偏差として調整)
        double adjustedSNorm;
        if (sNorm >= 0.5) {
            adjustedSNorm = 0.5 + (sNorm - 0.5) * 1.0;
        } else {
            adjustedSNorm = 0.5 + (sNorm - 0.5) * 3.0;
        }
        
        return currentMean + currentAmplitude * adjustedSNorm;
    }
    
    /**
     * 現在処理中の拍のサンプル数を取得（理想曲線生成用）
     * @return サンプル数、または0（理想曲線が利用できない場合）
     */
    public int getCurrentBeatSampleCount() {
        if (!hasIdealCurve || idealCurveStartTime == 0 || idealCurveEndTime == 0) {
            return 0;
        }
        
        // 理想曲線の時間範囲内のサンプル数を計算（30fps想定）
        double frameInterval = 1000.0 / 30.0;
        long duration = idealCurveEndTime - idealCurveStartTime;
        return (int)Math.ceil(duration / frameInterval);
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
    public double getCurrentMean() { return currentMean; }
    
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
        if (currentTime - lastPeakTime < REFRACTORY_PERIOD_MS) {
            return;
        }
        
        // バッファが十分でない場合はスキップ
        if (ppgBuffer.size() < 7) {
            return;
        }
        
        // 移動窓での最大値チェック（前後3フレーム）
        Double[] recent = ppgBuffer.toArray(new Double[0]);
        int idx = recent.length - 4;  // 中央
        
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
     * ピーク検出時の処理（1拍遅延方式：次のピークが来た時点で、その拍のrPPGから比率を計算してSin近似を計算）
     * 
     * 動作フロー：
     * - 1拍目：記録のみ
     * - 2拍目：1拍目のrPPGから比率を計算し、1拍目のSin近似を計算して表示 + 2拍目を記録
     * - 3拍目：2拍目のrPPGから比率を計算し、2拍目のSin近似を計算して表示 + 3拍目を記録
     * - ... を永遠に続ける
     */
    private void processPeak(double peakValue, long peakTime) {
        Log.d(TAG + "-ProcessPeak", String.format(
                "processPeak() called: peakValue=%.2f, peakTime=%d, lastPeakTime=%d, previousPeakTime=%d",
                peakValue, peakTime, lastPeakTime, previousPeakTime));
        
        // 初回のピークは記録のみ
        if (lastPeakTime == 0) {
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            previousPeakTime = peakTime;
            previousPeakValue = peakValue;
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
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }
        
        // その拍のrPPGデータから収縮期/拡張期の比率を計算
        double[] ratios = calculateSystoleDiastoleRatio(beatSamples, beatTimes, ibi);
        double systoleRatio = ratios[0];
        double diastoleRatio = ratios[1];
        
        // 計算した比率を保存（理想曲線表示用）
        currentSystoleRatio = systoleRatio;
        currentDiastoleRatio = diastoleRatio;
        
        // その拍のサイン波フィット（動的な比率を使用）
        fitSineWave(beatSamples, ibi);
        
        // 異常値チェック
        if (!isValidBeat(ibi, currentA)) {
            // データを更新して次の拍に備える
            previousPeakTime = lastPeakTime;
            previousPeakValue = lastPeakValue;
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }
        
        // その拍の歪み計算（動的な比率を使用）
        calculateDistortion(beatSamples, currentA, currentPhi, ibi, systoleRatio, diastoleRatio);
        
        Log.d(TAG + "-ProcessPeak", String.format(
                "After distortion calc: hasIdealCurve=%b, startTime=%d, endTime=%d",
                hasIdealCurve, idealCurveStartTime, idealCurveEndTime));
        
        // その拍のBP推定
        estimateBP(currentA, ibi, currentE);
        
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
     * 1拍分のサンプルを抽出
     */
    private List<Double> extractBeatSamples(long startTime, long endTime) {
        List<Double> samples = new ArrayList<>();
        Long[] times = timeBuffer.toArray(new Long[0]);
        Double[] values = ppgBuffer.toArray(new Double[0]);
        
        for (int i = 0; i < times.length; i++) {
            if (times[i] >= startTime && times[i] <= endTime) {
                samples.add(values[i]);
            }
        }
        
        return samples;
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
     * 前の拍のrPPGデータから収縮期/拡張期の比率を計算
     * @param beatSamples 1拍分のサンプル値
     * @param beatTimes 1拍分の時刻（ms）
     * @param ibi 周期（ms）
     * @return [systoleRatio, diastoleRatio] の配列
     */
    private double[] calculateSystoleDiastoleRatio(List<Double> beatSamples, List<Long> beatTimes, double ibi) {
        if (beatSamples == null || beatSamples.isEmpty() || beatTimes == null || beatTimes.size() != beatSamples.size()) {
            // デフォルト値（1:2）
            return new double[]{1.0/3.0, 2.0/3.0};
        }
        
        int N = beatSamples.size();
        if (N < 3) {
            return new double[]{1.0/3.0, 2.0/3.0};
        }
        
        // 谷の位置を検出（最小値）
        int valleyIndex = 0;
        double minValue = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (beatSamples.get(i) < minValue) {
                minValue = beatSamples.get(i);
                valleyIndex = i;
            }
        }
        
        // 谷の位置が極端に端にある場合は、より安定した方法で検出
        // ピーク（開始点）から谷までの距離を計算
        // ピークは t=0（開始時刻）に位置すると仮定
        
        // 谷の時刻を取得
        long peakTime = beatTimes.get(0);
        long valleyTime = beatTimes.get(valleyIndex);
        
        // 拡張期の時間（ピーク→谷）
        double diastoleTime = valleyTime - peakTime;
        
        // 収縮期の時間（谷→次のピーク = IBI - 拡張期）
        double systoleTime = ibi - diastoleTime;
        
        // 比率を計算
        double diastoleRatio = diastoleTime / ibi;
        double systoleRatio = systoleTime / ibi;
        
        // 異常値チェック（比率が0-1の範囲内で、生理学的に妥当な範囲内）
        if (diastoleRatio < 0.1 || diastoleRatio > 0.9 || 
            systoleRatio < 0.1 || systoleRatio > 0.9) {
            // 異常値の場合はデフォルト値を使用
            Log.w(TAG, String.format("Invalid ratio calculated: systole=%.3f, diastole=%.3f, using default",
                    systoleRatio, diastoleRatio));
            return new double[]{1.0/3.0, 2.0/3.0};
        }
        
        Log.d(TAG + "-Ratio", String.format(
                "Calculated ratio: systole=%.3f (%.1f ms), diastole=%.3f (%.1f ms), IBI=%.1f ms",
                systoleRatio, systoleTime, diastoleRatio, diastoleTime, ibi));
        
        return new double[]{systoleRatio, diastoleRatio};
    }
    
    /**
     * サイン波フィッティング
     * 注意: beatSamplesはLogic1で正規化された値（0-10範囲）を使用
     */
    private void fitSineWave(List<Double> beatSamples, double ibi) {
        // 64点にリサンプリング
        double[] resampled = resampleBeat(beatSamples, FIT_SAMPLES);
        
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
        
        Log.d(TAG + "-Fit", String.format("Fitted: A=%.3f, phi=%.3f rad (%.1f deg)",
                currentA, currentPhi, Math.toDegrees(currentPhi)));
    }
    
    /**
     * 1拍をN点にリサンプリング
     */
    private double[] resampleBeat(List<Double> beatSamples, int targetSize) {
        double[] resampled = new double[targetSize];
        int srcSize = beatSamples.size();
        
        if (srcSize == 0) {
            return resampled;
        }
        
        for (int i = 0; i < targetSize; i++) {
            // 線形補間
            double srcIdx = (double) i * (srcSize - 1) / (targetSize - 1);
            int idx0 = (int) Math.floor(srcIdx);
            int idx1 = Math.min(idx0 + 1, srcSize - 1);
            double t = srcIdx - idx0;
            
            resampled[i] = beatSamples.get(idx0) * (1 - t) + beatSamples.get(idx1) * t;
        }
        
        return resampled;
    }
    
    /**
     * 非対称サイン波基底関数（動的な比率に対応）
     * 三角関数の合成により、t=0でピーク、谷までがdiastoleRatio、谷から次のピークまでがsystoleRatioとなる波形を生成
     * @param t 時刻（ms）、0からT（IBI）の範囲
     * @param T 周期（IBI、ms）
     * @param systoleRatio 収縮期の比率（0-1）
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
            double angle = phase * (1.0/diastoleRatio) * Math.PI;  // 0→π
            value = Math.cos(angle + PHASE_SHIFT);  // cos(0)=1 → cos(π)=-1（左シフト）
        } else {
            // 谷→ピーク: systoleRatioの時間
            // phase: diastoleRatio→1 を π→2π にマッピング
            double angle = Math.PI + (phase - diastoleRatio) * (1.0/systoleRatio) * Math.PI;  // π→2π
            value = Math.cos(angle + PHASE_SHIFT);  // cos(π)=-1 → cos(2π)=1（左シフト）
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
     */
    private void calculateDistortion(List<Double> beatSamples, double A, double phi, double ibi,
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
        double T = ibi;  // 周期（ms）
        
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
        
        currentE = Math.sqrt(sumSquaredError / N);  // RMS誤差
        
        // 理想曲線パラメータを保存（UI表示用）
        currentMean = mean;
        currentAmplitude = A;
        currentIBIms = T;
        
        // ピーク検出時に理想曲線の位相を再同期（位相フィルタリング適用）
        // シンプルに：previousPeakTime を理想曲線のピーク位置に合わせる（前の拍を処理しているため）
        // 理想曲線のピーク位置を探索して、その時刻をpreviousPeakTimeに対応させる
        double maxValue = -1.0;
        double peakPhaseTime = 0;
        int searchSteps = 100;
        for (int i = 0; i < searchSteps; i++) {
            double t = (double) i * T / searchSteps;
            double val = asymmetricSineBasis(t, T, systoleRatio, diastoleRatio);
            if (val > maxValue) {
                maxValue = val;
                peakPhaseTime = t;
            }
        }
        // 位相調整（実測値と理想曲線の位相整合のみ）
        // 実測値のピーク位置に基づいて理想曲線の開始時刻を直接調整
        double phaseOffset = calculatePhaseOffset(peakPhaseTime, T);
        peakPhaseTime = phaseOffset;
        
        Log.d(TAG + "-PhaseAdjust", String.format(
                "Phase adjustment: original=%.1f, offset=%.1f, T=%.1f",
                peakPhaseTime / 3, phaseOffset, T));
        
        // 位相フィルタリング + 重み付け調整を適用
        double filteredPhaseTime = applyPhaseFiltering(peakPhaseTime);
        
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
     * 歪み指標の計算（現在の比率を使用するオーバーロード）
     */
    private void calculateDistortion(List<Double> beatSamples, double A, double phi, double ibi) {
        calculateDistortion(beatSamples, A, phi, ibi, currentSystoleRatio, currentDiastoleRatio);
    }
    
    /**
     * 異常値チェック
     */
    private boolean isValidBeat(double ibi, double amplitude) {
        // IBI範囲チェック（40-200 bpm相当）
        if (ibi < 300 || ibi > 1500) {
            Log.w(TAG, String.format("Invalid IBI: %.1f ms", ibi));
            return false;
        }
        
        // 振幅範囲チェック（correctedGreenValueのスケールに合わせて調整）
        // correctedGreenValueは0-100程度、サイン波振幅は1-20程度が正常範囲
        if (amplitude < 0.5 || amplitude > 50) {
            Log.w(TAG, String.format("Invalid amplitude: %.2f (expected: 0.5-50)", amplitude));
            return false;
        }
        
        // 前回の拍と比較（急激な変化を除外）
        if (lastValidIBI > 0) {
            double ibiChange = Math.abs(ibi - lastValidIBI) / lastValidIBI;
            if (ibiChange > 0.3) {  // 30%以上の変化は異常
                Log.w(TAG, String.format("IBI change too rapid: %.1f%%", ibiChange * 100));
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * BP推定
     * 注意: 振幅AはLogic1で正規化された値（0-10範囲）を使用
     */
    private void estimateBP(double A, double ibi, double E) {
        // smoothedIBIからHRを計算（RealtimeBPと同様の方法）
        double hr = 0.0;
        if (logicRef != null && !logicRef.smoothedIbi.isEmpty()) {
            double lastSmoothedIbi = logicRef.getLastSmoothedIbi();
            if (lastSmoothedIbi > 0) {
                hr = 60000.0 / lastSmoothedIbi; // smoothedIBIから計算
            } else {
                hr = 60000.0 / ibi; // フォールバック
            }
        } else {
            hr = 60000.0 / ibi; // フォールバック
        }
        
        // BaseLogicから血管特性を取得（AIは除去、Stiffness_sinを優先）
        double valleyToPeakRelTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        double peakToValleyRelTTP = (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
        
        // 血管硬さ指標（歪み × 振幅の平方根）- SinBPDistortionの独自指標
        double stiffness = E * Math.sqrt(A);
        
        // ベースBP計算
        double sbpBase = ALPHA0 + ALPHA1 * A + ALPHA2 * hr;
        double dbpBase = BETA0 + BETA1 * A + BETA2 * hr;
        
        // 血管特性補正（AIを除去、Stiffness_sinを強化、両方のrelTTPを使用）
        double sbpVascular = sbpBase + ALPHA3 * valleyToPeakRelTTP + ALPHA4 * peakToValleyRelTTP + ALPHA5 * stiffness;
        double dbpVascular = dbpBase + BETA3 * valleyToPeakRelTTP + BETA4 * peakToValleyRelTTP + BETA5 * stiffness;
        
        // 歪み補正（第3段）
        double deltaSBP = ALPHA6 * E;
        double deltaDBP = BETA6 * E;
        double sbpRefined = sbpVascular + deltaSBP;
        double dbpRefined = dbpVascular + deltaDBP;
        
        // 制約適用
        if (sbpRefined < dbpRefined + 10) {
            sbpRefined = dbpRefined + 10;
        }
        
        sbpRefined = clamp(sbpRefined, 60, 200);
        dbpRefined = clamp(dbpRefined, 40, 150);
        
        // 生理学的妥当性チェック
        if (!isValidBP(sbpRefined, dbpRefined)) {
            Log.w(TAG, String.format("Invalid BP: SBP=%.1f, DBP=%.1f", sbpRefined, dbpRefined));
            return;
        }
        
        Log.d(TAG + "-Estimate", String.format(
                "BP: SBP=%.1f, DBP=%.1f (V2P_relTTP=%.3f, P2V_relTTP=%.3f, stiffness=%.3f)",
                sbpRefined, dbpRefined, valleyToPeakRelTTP, peakToValleyRelTTP, stiffness));
        
        // 履歴更新と平均計算
        updateHistory(sbpRefined, dbpRefined);
    }
    
    /**
     * BP値の生理学的妥当性チェック
     */
    private boolean isValidBP(double sbp, double dbp) {
        // 範囲チェック
        if (sbp < 60 || sbp > 200) return false;
        if (dbp < 40 || dbp > 150) return false;
        if (sbp <= dbp) return false;
        
        // 脈圧チェック（20-100 mmHg）
        double pp = sbp - dbp;
        if (pp < 20 || pp > 100) return false;
        
        return true;
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
        double sbpAvg = robustAverage(sinSbpHist);
        double dbpAvg = robustAverage(sinDbpHist);
        
        lastSinSBPAvg = sbpAvg;
        lastSinDBPAvg = dbpAvg;
        
        Log.d(TAG + "-Average", String.format(
                "Averaged BP: SBP_avg=%.1f, DBP_avg=%.1f (history size: %d)",
                sbpAvg, dbpAvg, sinSbpHist.size()));
        
        // リスナー通知
        if (listener != null) {
            listener.onSinBPUpdated(sbp, dbp, sbpAvg, dbpAvg);
        }
    }
    
    /**
     * ロバスト平均（ハンペルフィルタ相当）
     */
    private double robustAverage(Deque<Double> hist) {
        List<Double> list = new ArrayList<>(hist);
        if (list.isEmpty()) {
            return 0.0;
        }
        
        // 中央値を求める
        Collections.sort(list);
        double median = list.get(list.size() / 2);
        
        // 偏差リストを作成し、その中央値（MAD）を求める
        List<Double> deviations = list.stream()
                .map(v -> Math.abs(v - median))
                .sorted()
                .collect(Collectors.toList());
        double mad = deviations.get(deviations.size() / 2);
        
        // 閾値 = 3 × MAD
        double threshold = 3 * mad;
        
        // 中央値±閾値内のデータだけフィルタ
        List<Double> filtered = list.stream()
                .filter(v -> Math.abs(v - median) <= threshold)
                .collect(Collectors.toList());
        
        // フィルタ後の平均を返す
        return filtered.stream()
                .mapToDouble(v -> v)
                .average()
                .orElse(median);
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
     * 位相フィルタリング + 重み付け調整を適用
     */
    private double applyPhaseFiltering(double rawPhaseTime) {
        // 1. 位相差を計算
        double phaseDifference = rawPhaseTime - lastPhaseShift;
        
        // 2. 急激な変化を制限
        if (Math.abs(phaseDifference) > MAX_PHASE_CHANGE) {
            double sign = Math.signum(phaseDifference);
            phaseDifference = sign * MAX_PHASE_CHANGE;
        }
        
        // 3. 重み付け調整（70%の重み）
        double adjustedPhaseTime = lastPhaseShift + phaseDifference * 0.7;
        
        // 4. 位相履歴に追加
        phaseHistory.addLast(adjustedPhaseTime);
        if (phaseHistory.size() > 3) {
            phaseHistory.pollFirst();
        }
        
        // 5. 移動平均フィルタ
        double sum = 0.0;
        for (double phase : phaseHistory) {
            sum += phase;
        }
        double filteredPhaseTime = sum / phaseHistory.size();
        
        // 6. 更新
        lastPhaseShift = filteredPhaseTime;
        
        Log.d(TAG + "-PhaseFilter", String.format(
                "Phase filtering: raw=%.1f, adjusted=%.1f, filtered=%.1f",
                rawPhaseTime, adjustedPhaseTime, filteredPhaseTime));
        
        return filteredPhaseTime;
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
        
        // 位相フィルタリング用のリセット
        phaseHistory.clear();
        lastPhaseShift = 0.0;
        
        lastPeakValue = 0;
        lastPeakTime = 0;
        previousPeakTime = 0;
        previousPeakValue = 0;
        previousBeatSamples = null;
        previousBeatTimes = null;
        currentSystoleRatio = 1.0/3.0;
        currentDiastoleRatio = 2.0/3.0;
        currentA = 0;
        currentIBI = 0;
        currentPhi = 0;
        currentE = 0;
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        lastValidIBI = 0;
        
        Log.d(TAG, "SinBPDistortion reset with 1-beat delay and dynamic ratio");
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

    public double getCurrentDistortion() {
        return currentE;
    }

    public double getCurrentStiffness() {
        // Stiffness_sin = E * sqrt(A)
        return currentE * Math.sqrt(Math.max(currentA, 0.0));
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

    public double getCurrentValleyToPeakRelTTP() {
        return (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
    }

    public double getCurrentPeakToValleyRelTTP() {
        return (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
    }
}

