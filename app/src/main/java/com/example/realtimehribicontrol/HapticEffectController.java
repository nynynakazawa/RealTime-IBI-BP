package com.example.realtimehribicontrol;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;

/**
 * フレームワークの HapticGenerator と衝突しないよう、
 * 自前ラッパーを HapticEffectController として定義。
 * API 31+ 端末では音声同期ハプティクスを有効化し、
 * それ未満では no-op（振動オフ）となります。
 */
public class HapticEffectController {
    private final android.media.audiofx.HapticGenerator frameworkGen;
    private final boolean supported;

    /**
     * @param context        アプリケーションコンテキスト
     * @param audioSessionId MediaPlayer#getAudioSessionId() で取得したセッションID
     */
    public HapticEffectController(@NonNull Context context, int audioSessionId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.media.audiofx.HapticGenerator g;
            boolean ok;
            try {
                // Android 12(API31) で導入された HapticGenerator を生成
                g = android.media.audiofx.HapticGenerator.create(audioSessionId);
                g.setEnabled(true);
                ok = true;
            } catch (Exception e) {
                // API 31+ でもデバイスが非対応の可能性あり
                g = null;
                ok = false;
            }
            frameworkGen = g;
            supported    = ok;
        } else {
            frameworkGen = null;
            supported    = false;
        }
    }

    /** 再生開始時に振動を有効化（非対応時は no-op） **/
    public void start() {
        // コンストラクタ内で setEnabled(true) 済みなので特に何もしない
    }

    /** 停止時に振動を無効化・リリース **/
    public void stop() {
        if (supported && frameworkGen != null) {
            frameworkGen.setEnabled(false);
            frameworkGen.release();
        }
    }
}