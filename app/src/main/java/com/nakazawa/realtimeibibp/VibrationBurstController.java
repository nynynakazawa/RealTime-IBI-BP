package com.nakazawa.realtimeibibp;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * phase2だけで動くバーストバイブ制御。
 * WHY: PPG/接触面積のOFF窓とIMUのON窓を同じ時計で後から分離できるよう、
 * 現在状態をカメラ・IMU両方からロックなしで読める形にする。
 */
public class VibrationBurstController {
    private static final String TAG = "VibrationBurst";

    public static final int STATE_OFF = 0;
    public static final int STATE_ON = 1;
    public static final int STATE_GUARD = 2;

    public static final long OFF_MS = 1500L;
    public static final long ON_MS = 500L;
    public static final long GUARD_MS = 50L;
    public static final int DEVICE_MAX_AMPLITUDE = 255;
    public static final String MODE = "burst";
    public static final String MODE_CONTINUOUS = "continuous";
    public static final String AMPLITUDE_META = "device_max";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;
    private final AtomicInteger vibState = new AtomicInteger(STATE_OFF);
    private final AtomicInteger burstId = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean continuousRunning = new AtomicBoolean(false);
    private volatile String effectName = "createOneShot";

    private final Runnable advanceRunnable = new Runnable() {
        @Override
        public void run() {
            advanceStep();
        }
    };

    private int stepIndex = 0;

    public VibrationBurstController(Context context) {
        Context appContext = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager =
                    (VibratorManager) appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vibratorManager != null ? vibratorManager.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    public void startPhase2Burst() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "No vibrator available; state is still recorded as OFF");
            running.set(false);
            continuousRunning.set(false);
            setInactiveState();
            return;
        }
        stop();
        running.set(true);
        burstId.set(0);
        stepIndex = 0;
        effectName = "createOneShot";
        setOffWindow();
    }

    public void startContinuous() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.w(TAG, "No vibrator available; state is still recorded as OFF");
            running.set(false);
            continuousRunning.set(false);
            setInactiveState();
            return;
        }
        if (continuousRunning.get() && running.get()) {
            return;
        }
        handler.removeCallbacks(advanceRunnable);
        running.set(true);
        continuousRunning.set(true);
        burstId.set(0);
        vibState.set(STATE_ON);
        effectName = "createWaveformContinuous";
        vibrateContinuous();
    }

    public void stop() {
        running.set(false);
        continuousRunning.set(false);
        handler.removeCallbacks(advanceRunnable);
        if (vibrator != null) {
            vibrator.cancel();
        }
        setInactiveState();
    }

    public int getVibState() {
        return vibState.get();
    }

    public int getBurstId() {
        return burstId.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getEffectName() {
        return effectName;
    }

    public String getActuatorTypeHint() {
        return "unknown";
    }

    private void setInactiveState() {
        vibState.set(STATE_OFF);
    }

    private void advanceStep() {
        if (!running.get()) {
            setInactiveState();
            return;
        }
        stepIndex = (stepIndex + 1) % 4;
        switch (stepIndex) {
            case 0:
                burstId.incrementAndGet();
                setOffWindow();
                break;
            case 1:
                setGuardWindow();
                break;
            case 2:
                setOnWindow();
                break;
            case 3:
                setGuardWindow();
                break;
            default:
                setOffWindow();
                break;
        }
    }

    private void setOffWindow() {
        if (vibrator != null) {
            vibrator.cancel();
        }
        vibState.set(STATE_OFF);
        handler.postDelayed(advanceRunnable, OFF_MS);
    }

    private void setGuardWindow() {
        if (vibrator != null) {
            vibrator.cancel();
        }
        vibState.set(STATE_GUARD);
        handler.postDelayed(advanceRunnable, GUARD_MS);
    }

    private void setOnWindow() {
        vibState.set(STATE_ON);
        vibrateOnWindow();
        handler.postDelayed(advanceRunnable, ON_MS);
    }

    private void vibrateOnWindow() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect = VibrationEffect.createOneShot(ON_MS, DEVICE_MAX_AMPLITUDE);
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(ON_MS);
        }
    }

    private void vibrateContinuous() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VibrationEffect effect = VibrationEffect.createWaveform(
                    new long[]{0L, ON_MS},
                    new int[]{0, DEVICE_MAX_AMPLITUDE},
                    0);
            vibrator.vibrate(effect);
        } else {
            vibrator.vibrate(new long[]{0L, ON_MS}, 0);
        }
    }
}
