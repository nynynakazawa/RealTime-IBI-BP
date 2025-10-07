package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * サイン波ベースのリアルタイム血圧推定器（SBP/DBP）
 * 
 * アルゴリズムの要点：
 * 1. PPG波形をサイン波で近似
 * 2. 振幅A、周期IBI、位相φを抽出
 * 3. 歪み指標Eで波形の複雑さを評価
 * 4. BaseLogicからAI・relTTPを取得して血管特性を反映
 * 5. 3段階のBP推定：ベース→血管特性補正→歪み補正
 */
public class SinBP {
    private static final String TAG = "SinBP";
    
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
    // 振幅Aが1-10程度なので、係数を大きくする
    private static final double ALPHA0 = 80.0;
    private static final double ALPHA1 = 5.0;   // 0.5 → 5.0 (10倍)
    private static final double ALPHA2 = 0.3;
    private static final double BETA0 = 60.0;
    private static final double BETA1 = 3.0;    // 0.3 → 3.0 (10倍)
    private static final double BETA2 = 0.15;
    
    // 血管特性補正係数
    private static final double ALPHA3 = 0.3;   // AI係数（0.8 → 0.3に縮小）
    private static final double ALPHA4 = 5.0;   // relTTP係数（0.2 → 5.0に拡大）
    private static final double ALPHA5 = 0.01;  // stiffness係数（0.1 → 0.01に大幅縮小）
    private static final double BETA3 = 0.2;    // AI係数（0.4 → 0.2に縮小）
    private static final double BETA4 = 3.0;    // relTTP係数（0.1 → 3.0に拡大）
    private static final double BETA5 = 0.005;  // stiffness係数（0.05 → 0.005に大幅縮小）
    
    // 歪み補正係数（Eが小さくなるので拡大）
    private static final double C1 = 0.1;       // 0.01 → 0.1
    private static final double D1 = 0.05;      // 0.005 → 0.05
    
    // リスナー
    public interface SinBPListener {
        void onSinBPUpdated(double sinSbp, double sinDbp,
                           double sinSbpAvg, double sinDbpAvg);
    }
    private SinBPListener listener;
    
    /**
     * リスナーを設定
     */
    public void setListener(SinBPListener l) {
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
        return currentISO >= 500;
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
     * ピーク検出時の処理
     */
    private void processPeak(double peakValue, long peakTime) {
        // 初回のピークは記録のみ
        if (lastPeakTime == 0) {
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            Log.d(TAG, "First peak detected");
            return;
        }
        
        // IBI計算
        double ibi = peakTime - lastPeakTime;
        
        // 1拍分のデータを取得
        List<Double> beatSamples = extractBeatSamples(lastPeakTime, peakTime);
        
        if (beatSamples == null || beatSamples.isEmpty()) {
            Log.w(TAG, "Beat samples extraction failed");
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }
        
        // サイン波フィット
        fitSineWave(beatSamples, ibi);
        
        // 異常値チェック
        if (!isValidBeat(ibi, currentA)) {
            Log.w(TAG, String.format("Invalid beat: IBI=%.1f, A=%.2f", ibi, currentA));
            lastPeakValue = peakValue;
            lastPeakTime = peakTime;
            return;
        }
        
        // 歪み計算
        calculateDistortion(beatSamples, currentA, currentPhi, ibi);
        
        // BP推定
        estimateBP(currentA, ibi, currentE);
        
        // 更新
        lastPeakValue = peakValue;
        lastPeakTime = peakTime;
        lastValidIBI = ibi;
        
        Log.d(TAG, String.format("Beat processed: IBI=%.1f, A=%.2f, phi=%.2f, E=%.4f",
                ibi, currentA, currentPhi, currentE));
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
     * サイン波フィッティング
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
     * 歪み指標の計算
     */
    private void calculateDistortion(List<Double> beatSamples, double A, double phi, double ibi) {
        if (A < 1e-6) {
            currentE = 0;
            return;
        }
        
        // サンプルの平均値を計算（DCオフセット）
        double mean = 0;
        for (double sample : beatSamples) {
            mean += sample;
        }
        mean /= beatSamples.size();
        
        // サイン波再構成との残差を計算（平均値を考慮）
        double sumSquaredError = 0;
        int N = beatSamples.size();
        
        for (int i = 0; i < N; i++) {
            double t = (double) i / N;  // 0〜1に正規化
            double angle = 2 * Math.PI * t + phi;
            double sineValue = mean + A * Math.sin(angle);  // 平均値 + サイン波
            double error = beatSamples.get(i) - sineValue;
            sumSquaredError += error * error;
        }
        
        currentE = Math.sqrt(sumSquaredError / N);  // RMS誤差（正規化）
        
        Log.d(TAG + "-Distortion", String.format("E=%.4f (mean=%.2f)", currentE, mean));
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
     */
    private void estimateBP(double A, double ibi, double E) {
        double hr = 60000.0 / ibi;
        
        // BaseLogicから血管特性を取得
        double ai = (logicRef != null) ? logicRef.averageAI : 0.0;
        double relTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        
        // 血管硬さ指標（歪み × 振幅の平方根）
        double stiffness = E * Math.sqrt(A);
        
        // ベースBP計算
        double sbpBase = ALPHA0 + ALPHA1 * A + ALPHA2 * hr;
        double dbpBase = BETA0 + BETA1 * A + BETA2 * hr;
        
        // 血管特性補正
        double sbpVascular = sbpBase + ALPHA3 * ai + ALPHA4 * relTTP + ALPHA5 * stiffness;
        double dbpVascular = dbpBase + BETA3 * ai + BETA4 * relTTP + BETA5 * stiffness;
        
        // 歪み補正
        double deltaSBP = C1 * E;
        double deltaDBP = D1 * E;
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
                "BP: SBP=%.1f, DBP=%.1f (AI=%.2f, relTTP=%.3f, stiffness=%.3f)",
                sbpRefined, dbpRefined, ai, relTTP, stiffness));
        
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
     * 値を[min, max]でクリップ
     */
    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
    
    /**
     * コンストラクタ
     */
    public SinBP() {
        // 初期値を設定（UI表示用）
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        Log.d(TAG, "SinBP initialized");
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
        currentA = 0;
        currentIBI = 0;
        currentPhi = 0;
        currentE = 0;
        lastSinSBP = 0;
        lastSinDBP = 0;
        lastSinSBPAvg = 0;
        lastSinDBPAvg = 0;
        lastValidIBI = 0;
        
        Log.d(TAG, "SinBP reset");
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
}

