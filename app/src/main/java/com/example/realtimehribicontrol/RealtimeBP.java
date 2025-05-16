package com.example.realtimehribicontrol;

import android.util.Log;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Photoplethysmography (PPG) の形態学的特徴量を用いた
 * リアルタイム血圧推定器（SBP/DBP）。
 *
 * ─ アルゴリズムの要点 ─
 * 1. 1 拍分の correctedGreenValue（正規化済み PPG 振幅）をバッファリング
 * 2. 形態学的特徴: 最大振幅 (S), 拍末振幅 (D), time-to-peak (TTP), 拍幅 (PW)
 * 3. hemodynamic 特徴: オーグメンテーション指数 (AI = (S - D) / S), 心拍数 (HR)
 * 4. 線形回帰モデルで SBP / DBP を推定
 */
public class RealtimeBP {

    /** 1 拍あたり最大サンプル数 (想定フレームレート 30–90 fps 対応) */
    private static final int MAX_BEAT_SAMPLES = 90;

    /** 1 拍分の PPG 振幅を保持するバッファ */
    private final Deque<Double> beatBuf = new ArrayDeque<>(MAX_BEAT_SAMPLES);

    /** 最後の拍動開始時刻 (ms) */
    private long lastBeatStart = System.currentTimeMillis();

    /* ===== リスナ：推定結果受信用 ===== */
    public interface BPListener {
        /** SBP / DBP 更新時に呼び出される */
        void onBpUpdated(double sbp, double dbp);
    }
    private BPListener listener;

    /** リスナ登録メソッド */
    public void setListener(BPListener l) {
        this.listener = l;
    }

    /**
     * Logic1 から毎フレーム呼び出される更新メソッド
     * @param correctedGreenValue 正規化済み PPG 振幅 (0–100%)
     * @param smoothedIbiMs       平滑化済み IBI (ms)
     */
    public void update(double correctedGreenValue, double smoothedIbiMs) {
        // バッファにサンプルを追加
        beatBuf.addLast(correctedGreenValue);
        // 古いサンプルは捨てる
        if (beatBuf.size() > MAX_BEAT_SAMPLES) {
            beatBuf.pollFirst();
        }

        long now = System.currentTimeMillis();

        // IBI (ms) が経過したら 1 拍分とみなして処理
        if (now - lastBeatStart >= smoothedIbiMs && beatBuf.size() > 15) {
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
        // --- 形態学的特徴抽出 ---
        int n = beatBuf.size();
        double sAmp = Double.MIN_VALUE;  // systolic peak amplitude
        int idx = 0, idxPeak = 0;
        // 拍動波の最初と最後の振幅
        double first = beatBuf.peekFirst();
        double last  = beatBuf.peekLast();  // diastolic end amplitude

        // 最大振幅 (S) とそのサンプルインデックス (TTP) を検出
        for (double v : beatBuf) {
            if (v > sAmp) {
                sAmp = v;
                idxPeak = idx;
            }
            idx++;
        }
        double dAmp = last;
        // Augmentation Index = (S - D) / S
        double ai   = (sAmp - dAmp) / Math.max(sAmp, 1e-3);
        double ttp  = idxPeak; // time-to-peak [サンプル数]
        double pw   = n;       // pulse width [サンプル数]
        double hr   = 60000.0 / ibiMs;  // 心拍数 [bpm]

        // --- 線形回帰モデル (文献平均係数を仮設定) ---
        final double a0 = 79,  a1 = 62,   a2 = 0.55, a3 = 0.24;  // SBP
        final double b0 = 46,  b1 = 38,   b2 = 0.35, b3 = 0.17;  // DBP
        double sbp = a0 + a1 * ai + a2 * hr + a3 * (ttp / pw);
        double dbp = b0 + b1 * ai + b2 * hr + b3 * (ttp / pw);

        Log.d("RealtimeBP", String.format(
                "SBP %.1f / DBP %.1f  (AI %.2f  HR %.1f  TTP/PW %.2f)",
                sbp, dbp, ai, hr, ttp / pw));

        // リスナへ結果を通知
        if (listener != null) {
            listener.onBpUpdated(sbp, dbp);
        }
    }
}