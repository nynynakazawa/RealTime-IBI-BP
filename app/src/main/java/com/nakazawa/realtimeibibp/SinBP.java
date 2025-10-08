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
    private long idealCurveStartTime = 0;  // 理想曲線の開始時刻（固定、位相の基準）
    private boolean hasIdealCurve = false;
    
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
     * 理想曲線データが利用可能かチェック
     */
    public boolean hasIdealCurve() {
        return hasIdealCurve;
    }
    
    /**
     * 指定時刻での理想曲線の値を取得
     * @param currentTime 現在時刻（ms）
     * @return 理想曲線の値
     */
    public double getIdealCurveValue(long currentTime) {
        if (!hasIdealCurve || currentIBIms <= 0 || idealCurveStartTime == 0) {
            return currentMean;
        }
        
        // 理想曲線開始時刻からの経過時間を計算（連続的な位相）
        double elapsedSinceStart = currentTime - idealCurveStartTime;
        
        // 周期内に正規化（負の値の場合は0にクリップ）
        if (elapsedSinceStart < 0) {
            elapsedSinceStart = 0;
        }
        double tNorm = elapsedSinceStart % currentIBIms;
        
        // 非対称サイン波基底を使用
        double sNorm = asymmetricSineBasis(tNorm, currentIBIms);
        
        // 理想波形: mean + A * (s_norm を中心からの偏差として1.5倍に拡大)
        // s_norm は [0,1] の範囲なので、中心0.5からの偏差を1.5倍にする
        double deviation = (sNorm - 0.5) * 1.5;
        return currentMean + currentAmplitude * (0.5 + deviation);
    }
    
    /**
     * 現在の理想曲線パラメータを取得
     */
    public double getCurrentMean() { return currentMean; }
    public double getCurrentAmplitude() { return currentAmplitude; }
    public double getCurrentIBI() { return currentIBIms; }
    
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
     * 非対称サイン波基底関数（収縮期1/3:拡張期2/3）
     * 三角関数の合成により、t=0でピーク、谷までが2/3T、谷から次のピークまでが1/3Tとなる波形を生成
     * @param t 時刻（ms）、0からT（IBI）の範囲
     * @param T 周期（IBI、ms）
     * @return 正規化された波形値 [0,1]（t=0で最大値1）
     */
    // 位相シフト定数（クラスレベルで定義）
    private static final double PHASE_SHIFT = -Math.PI / 4.0;
    
    private double asymmetricSineBasis(double t, double T) {
        // 周期内に正規化（0〜1）
        double phase = (t % T) / T;
        
        // 非対称波形の生成
        // t=0でピーク、phase=2/3で谷、phase=1で次のピーク（周期T内で1サイクル完結）
        
        double value;
        if (phase <= 2.0/3.0) {
            // ピーク→谷: 2/3の時間
            // phase: 0→2/3 を 0→π にマッピング
            double angle = phase * (3.0/2.0) * Math.PI;  // 0→π
            value = Math.cos(angle + PHASE_SHIFT);  // cos(0)=1 → cos(π)=-1（左シフト）
        } else {
            // 谷→ピーク: 1/3の時間
            // phase: 2/3→1 を π→2π にマッピング
            double angle = Math.PI + (phase - 2.0/3.0) * 3.0 * Math.PI;  // π→2π
            value = Math.cos(angle + PHASE_SHIFT);  // cos(π)=-1 → cos(2π)=1（左シフト）
        }
        
        // [0,1]に正規化（-1〜1 → 0〜1）
        return (value + 1.0) / 2.0;
    }
    
    /**
     * 歪み指標の計算
     * 非対称サイン波モデル（収縮期1/3:拡張期2/3）からの残差を計算
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
        
        // 非対称サイン波形再構成との残差を計算
        double sumSquaredError = 0;
        int N = beatSamples.size();
        double T = ibi;  // 周期（ms）
        
        for (int i = 0; i < N; i++) {
            // 時刻をms単位で計算（t=0がピーク）
            double t = (double) i * T / N;
            
            // 非対称サイン波基底を使用
            double sNorm = asymmetricSineBasis(t, T);
            
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
        
        // ピーク検出時に理想曲線の位相を再同期
        // シンプルに：lastPeakTime を理想曲線のピーク位置に合わせる
        // 理想曲線のピーク位置を探索して、その時刻をlastPeakTimeに対応させる
        double maxValue = -1.0;
        double peakPhaseTime = 0;
        int searchSteps = 100;
        for (int i = 0; i < searchSteps; i++) {
            double t = (double) i * T / searchSteps;
            double val = asymmetricSineBasis(t, T);
            if (val > maxValue) {
                maxValue = val;
                peakPhaseTime = t;
            }
        }
        // 僅かに遅れているため、位相を前進（ピーク位置を5%早める）
        peakPhaseTime *= 1.8;
        
        // lastPeakTime = idealCurveStartTime + peakPhaseTime
        // → idealCurveStartTime = lastPeakTime - peakPhaseTime
        idealCurveStartTime = lastPeakTime - (long)peakPhaseTime;
        
        hasIdealCurve = true;
        
        Log.d(TAG + "-Distortion", String.format(
                "E=%.4f (mean=%.2f, Asymmetric sine model: systole 1/3, diastole 2/3)", 
                currentE, mean));
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
        
        // BaseLogicから血管特性を取得（AIは除去、Stiffness_sinを優先）
        double valleyToPeakRelTTP = (logicRef != null) ? logicRef.averageValleyToPeakRelTTP : 0.0;
        double peakToValleyRelTTP = (logicRef != null) ? logicRef.averagePeakToValleyRelTTP : 0.0;
        
        // 血管硬さ指標（歪み × 振幅の平方根）- SinBPの独自指標
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

