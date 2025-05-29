package com.example.realtimehribicontrol;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.content.Context;
import android.os.Vibrator;
import android.os.VibrationEffect;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * simpleMIDI
 * ────────────────────────────────────────
 * • MediaPlayer で MIDI をループ再生
 * • 最新3回の Smoothed BPM 平均＋10% でテンポ調整
 * • API31+ 端末では HapticEffectController による音声同期ハプティクス
 */
public class simpleMIDI {
    private static final String TAG = "simpleMIDI";

    private final MediaPlayer player;
    private final Handler handler = new Handler();

    private Runnable hrPollTask;
    private final Context ctx;

    private final int bars = 4;
    private final double baseTempo;
    private final Deque<Double> recentBpm = new ArrayDeque<>(3);
    private static final int BPM_HISTORY = 3;
    private final double tempoOffsetRatio;
    private final GreenValueAnalyzer analyzer;
    private final HapticEffectController hapticController;

    public simpleMIDI(Context ctx,
                      GreenValueAnalyzer analyzer,
                      Uri midiUri,
                      double tempoOffsetRatio) {
        this.ctx = ctx;
        this.analyzer = analyzer;
        this.tempoOffsetRatio = tempoOffsetRatio;   // 新フィールドに保存
        player = new MediaPlayer();

        // データソース設定（URI が null の場合は raw/rock16 をフォールバック）
        try {
            if (midiUri != null) {
                player.setDataSource(ctx, midiUri);
            } else {
                AssetFileDescriptor afd =
                        ctx.getResources().openRawResourceFd(R.raw.bass);
                player.setDataSource(
                        afd.getFileDescriptor(),
                        afd.getStartOffset(),
                        afd.getLength()
                );
                afd.close();
            }
            player.prepare();
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "MIDI ファイルの読み込み失敗", e);
            throw new RuntimeException("MIDI ファイルの読み込み失敗", e);
        }

        baseTempo = 120.0;

        // ハプティクスコントローラー初期化
        hapticController = new HapticEffectController(
                ctx,
                player.getAudioSessionId()
        );

        // BPMポーリング＋テンポ更新タスクをここで代入
        hrPollTask = new Runnable() {
            @Override
            public void run() {
                double bpm = analyzer.getLatestSmoothedBpm();
                if (bpm > 0) {
                    if (recentBpm.size() >= BPM_HISTORY) {
                        recentBpm.poll();
                    }
                    recentBpm.offer(bpm);

                    double sum = 0;
                    for (double b : recentBpm) sum += b;
                    double avg = sum / recentBpm.size();
                    double target = avg * (1.0 + tempoOffsetRatio);  // ±任意オフセット
                    updateTempoSafely(target);
                }
                handler.postDelayed(hrPollTask, 1000);
            }
        };
    }

    /** 再生開始 **/
    public void start() {
        // MediaPlayer の組み込みループ機能を使って、終了直後にシームレスに再生をループ
        player.setLooping(true);
        player.start();

        // --- 振動の強度を指定できるように VibrationEffect を生成して再生 ---
        Vibrator vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            // 振動の強度を最大 (1–255 の範囲) = 255 を指定
            VibrationEffect effect = VibrationEffect.createOneShot(50, 255);
            vibrator.vibrate(effect);
        }

        // 既存のハプティクスコントローラーも動かしたい場合
        hapticController.start();

        handler.post(hrPollTask);
    }

    /** 再生停止 **/
    public void stop() {
        hapticController.stop();
        handler.removeCallbacks(hrPollTask);
        player.setOnCompletionListener(null);
        player.stop();
        player.release();
    }

    private long loopDurationMs() {
        float speed = player.getPlaybackParams().getSpeed();
        double currentTempo = baseTempo * speed;
        return (long)(bars * 4 * (60000.0 / currentTempo));
    }

    private void updateTempoSafely(double targetTempo) {
        float speed = (float)(targetTempo / baseTempo);
        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            player.setPlaybackParams(params);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "テンポ更新失敗 speed=" + speed, e);
        }
    }
}