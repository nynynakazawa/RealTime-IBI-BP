package com.nakazawa.realtimeibibp;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 信号処理用のユーティリティクラス
 * DRY原則に従い、共通の信号処理ロジックをここに集約する
 */
public class SignalProcessingUtils {
    private static final String TAG = "SignalProcessingUtils";

    /**
     * 前の拍のrPPGデータから収縮期/拡張期の比率を計算
     * 低心拍数ではサンプル数が多くなるため、より安定した谷の検出が必要
     * 
     * @param beatSamples 1拍分のサンプル値
     * @param beatTimes   1拍分の時刻（ms）
     * @param ibi         周期（ms）
     * @return [systoleRatio, diastoleRatio] の配列
     */
    public static double[] calculateSystoleDiastoleRatio(List<Double> beatSamples, List<Long> beatTimes, double ibi) {
        if (beatSamples == null || beatSamples.isEmpty() || beatTimes == null
                || beatTimes.size() != beatSamples.size()) {
            // デフォルト値（1:2）
            return new double[] { 1.0 / 3.0, 2.0 / 3.0 };
        }

        int N = beatSamples.size();
        if (N < 3) {
            return new double[] { 1.0 / 3.0, 2.0 / 3.0 };
        }

        // 低心拍数ではサンプル数が多くなるため、移動平均でスムージングしてから谷を検出
        // 移動平均のウィンドウサイズはサンプル数に応じて調整
        int windowSize = Math.max(3, Math.min(7, N / 10)); // サンプル数の10%程度、最小3、最大7
        List<Double> smoothedSamples = smoothSamples(beatSamples, windowSize);

        // ピーク位置を検出（開始点がピークと仮定）
        int peakIndex = 0;
        double maxValue = Double.NEGATIVE_INFINITY;
        int searchRange = Math.max(3, (int) (N * 0.2)); // 最初の20%の範囲
        for (int i = 0; i < searchRange && i < smoothedSamples.size(); i++) {
            if (smoothedSamples.get(i) > maxValue) {
                maxValue = smoothedSamples.get(i);
                peakIndex = i;
            }
        }

        // 谷の位置を検出（ピーク以降の最小値）
        // ピークから後半80%の範囲で谷を探索（最後の20%は次のピークの可能性があるため除外）
        int valleySearchStart = peakIndex;
        int valleySearchEnd = Math.min(N - 1, (int) (N * 0.8));
        int valleyIndex = valleySearchStart;
        double minValue = Double.MAX_VALUE;

        for (int i = valleySearchStart; i <= valleySearchEnd; i++) {
            if (smoothedSamples.get(i) < minValue) {
                minValue = smoothedSamples.get(i);
                valleyIndex = i;
            }
        }

        // 谷の時刻を取得
        long peakTime = beatTimes.get(peakIndex);
        long valleyTime = beatTimes.get(valleyIndex);

        // 拡張期の時間（ピーク→谷）
        double diastoleTime = valleyTime - peakTime;

        // 異常値チェック：拡張期が負の値や異常に大きい値の場合はデフォルト値を使用
        if (diastoleTime < 0 || diastoleTime > ibi * 0.9) {
            Log.w(TAG, String.format("Invalid diastole time: %.1f ms (IBI=%.1f ms), using default",
                    diastoleTime, ibi));
            return new double[] { 1.0 / 3.0, 2.0 / 3.0 };
        }

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
            return new double[] { 1.0 / 3.0, 2.0 / 3.0 };
        }

        Log.d(TAG + "-Ratio", String.format(
                "Calculated ratio: systole=%.3f (%.1f ms), diastole=%.3f (%.1f ms), IBI=%.1f ms, N=%d, window=%d",
                systoleRatio, systoleTime, diastoleRatio, diastoleTime, ibi, N, windowSize));

        return new double[] { systoleRatio, diastoleRatio };
    }

    /**
     * 移動平均でサンプルをスムージング（ノイズ除去）
     * 
     * @param samples    元のサンプル値
     * @param windowSize 移動平均のウィンドウサイズ
     * @return スムージング後のサンプル値
     */
    public static List<Double> smoothSamples(List<Double> samples, int windowSize) {
        List<Double> smoothed = new ArrayList<>();
        int N = samples.size();
        int halfWindow = windowSize / 2;

        for (int i = 0; i < N; i++) {
            double sum = 0.0;
            int count = 0;

            // ウィンドウ内の平均を計算
            for (int j = Math.max(0, i - halfWindow); j <= Math.min(N - 1, i + halfWindow); j++) {
                sum += samples.get(j);
                count++;
            }

            smoothed.add(sum / count);
        }

        return smoothed;
    }

    /**
     * 1拍をN点にリサンプリング（線形補間）
     * 
     * @param beatSamples 元のサンプル
     * @param targetSize  目標サイズ
     * @return リサンプリングされた配列
     */
    public static double[] resampleBeat(List<Double> beatSamples, int targetSize) {
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
     * 異常値チェック（拍単位）
     * 
     * @param ibi          IBI (ms)
     * @param amplitude    振幅
     * @param lastValidIBI 前回の有効IBI
     * @return 有効な拍ならtrue
     */
    public static boolean isValidBeat(double ibi, double amplitude, double lastValidIBI) {
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
            if (ibiChange > 0.3) { // 30%以上の変化は異常
                Log.w(TAG, String.format("IBI change too rapid: %.1f%%", ibiChange * 100));
                return false;
            }
        }

        return true;
    }

    /**
     * BP値の生理学的妥当性チェック
     * 
     * @param sbp 収縮期血圧
     * @param dbp 拡張期血圧
     * @return 有効ならtrue
     */
    public static boolean isValidBP(double sbp, double dbp) {
        // 範囲チェック
        if (sbp < 60 || sbp > 200)
            return false;
        if (dbp < 40 || dbp > 150)
            return false;
        if (sbp <= dbp)
            return false;

        // 脈圧チェック（20-100 mmHg）
        double pp = sbp - dbp;
        if (pp < 20 || pp > 100)
            return false;

        return true;
    }

    /**
     * 値を[min, max]でクリップ
     * 
     * @param v  値
     * @param lo 最小値
     * @param hi 最大値
     * @return クリップされた値
     */
    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * ロバスト平均（ハンペルフィルタ相当）
     * 
     * @param hist 履歴データ
     * @return ロバスト平均値
     */
    public static double robustAverage(Deque<Double> hist) {
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
}
