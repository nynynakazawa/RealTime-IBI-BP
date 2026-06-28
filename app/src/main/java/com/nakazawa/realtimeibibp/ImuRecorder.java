package com.nakazawa.realtimeibibp;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ImuRecorder implements SensorEventListener {
    private static final String TAG = "ImuRecorder";
    private static final String CSV_HEADER =
            "t_elapsed_ns, accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z, vib_state, burst_id, phase\n";

    public interface PhaseProvider {
        int getCurrentPhaseForImu();
    }

    private static final class Sample {
        final long tElapsedNs;
        final float accelX;
        final float accelY;
        final float accelZ;
        final float gyroX;
        final float gyroY;
        final float gyroZ;
        final int vibState;
        final int burstId;
        final int phase;

        Sample(
                long tElapsedNs,
                float accelX,
                float accelY,
                float accelZ,
                float gyroX,
                float gyroY,
                float gyroZ,
                int vibState,
                int burstId,
                int phase) {
            this.tElapsedNs = tElapsedNs;
            this.accelX = accelX;
            this.accelY = accelY;
            this.accelZ = accelZ;
            this.gyroX = gyroX;
            this.gyroY = gyroY;
            this.gyroZ = gyroZ;
            this.vibState = vibState;
            this.burstId = burstId;
            this.phase = phase;
        }
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final VibrationBurstController vibrationBurstController;
    private final PhaseProvider phaseProvider;
    private final Object lock = new Object();
    private final List<Sample> samples = new ArrayList<>();

    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private boolean recording = false;
    private long firstSampleNs = 0L;
    private long lastSampleNs = 0L;
    private float accelX = 0f;
    private float accelY = 0f;
    private float accelZ = 0f;
    private float gyroX = 0f;
    private float gyroY = 0f;
    private float gyroZ = 0f;
    private boolean accelRmsWindowOn = false;
    private double accelMagSumOn = 0.0;
    private double accelMagSumSqOn = 0.0;
    private int accelMagCountOn = 0;
    private double lastBurstAccelAcRms = 0.0;

    public ImuRecorder(
            Context context,
            VibrationBurstController vibrationBurstController,
            PhaseProvider phaseProvider) {
        Context appContext = context.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        gyroscope = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        this.vibrationBurstController = vibrationBurstController;
        this.phaseProvider = phaseProvider;
    }

    public void start() {
        stop();
        synchronized (lock) {
            samples.clear();
            firstSampleNs = 0L;
            lastSampleNs = 0L;
            accelX = accelY = accelZ = 0f;
            gyroX = gyroY = gyroZ = 0f;
            resetAccelRmsWindowLocked();
            accelRmsWindowOn = false;
            lastBurstAccelAcRms = 0.0;
            recording = true;
        }
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager unavailable");
            return;
        }
        sensorThread = new HandlerThread("RTBP-IMU");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        if (accelerometer != null) {
            registerSensorSafely(accelerometer, "Accelerometer");
        } else {
            Log.w(TAG, "Accelerometer unavailable");
        }
        if (gyroscope != null) {
            registerSensorSafely(gyroscope, "Gyroscope");
        } else {
            Log.w(TAG, "Gyroscope unavailable");
        }
    }

    // WHY: HIGH_SAMPLING_RATE_SENSORS が無い/拒否された端末では FASTEST(0μs) 登録が SecurityException を投げる。
    //      アプリ全体を巻き添えに落とさないよう、FASTEST失敗時は GAME(約50Hz) へ静かにフォールバックする。
    private void registerSensorSafely(Sensor sensor, String label) {
        try {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
        } catch (SecurityException e) {
            Log.w(TAG, label + ": FASTEST不可のためGAMEへフォールバック (HIGH_SAMPLING_RATE_SENSORS未付与)", e);
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, sensorHandler);
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        synchronized (lock) {
            recording = false;
        }
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
            sensorHandler = null;
        }
    }

    public void clear() {
        synchronized (lock) {
            samples.clear();
            firstSampleNs = 0L;
            lastSampleNs = 0L;
            resetAccelRmsWindowLocked();
            accelRmsWindowOn = false;
            lastBurstAccelAcRms = 0.0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null) {
            return;
        }
        synchronized (lock) {
            if (!recording) {
                return;
            }
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                accelX = event.values[0];
                accelY = event.values[1];
                accelZ = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                gyroX = event.values[0];
                gyroY = event.values[1];
                gyroZ = event.values[2];
            } else {
                return;
            }

            long tElapsedNs = SystemClock.elapsedRealtimeNanos();
            if (firstSampleNs == 0L) {
                firstSampleNs = tElapsedNs;
            }
            lastSampleNs = tElapsedNs;
            int phase = phaseProvider != null ? phaseProvider.getCurrentPhaseForImu() : 0;
            int vibState = vibrationBurstController != null
                    ? vibrationBurstController.getVibState()
                    : VibrationBurstController.STATE_OFF;
            updateLastBurstAccelAcRmsLocked(vibState, event.sensor.getType() == Sensor.TYPE_ACCELEROMETER);
            int burstId = vibrationBurstController != null ? vibrationBurstController.getBurstId() : 0;
            samples.add(new Sample(
                    tElapsedNs,
                    accelX,
                    accelY,
                    accelZ,
                    gyroX,
                    gyroY,
                    gyroZ,
                    vibState,
                    burstId,
                    phase));
        }
    }

    private void updateLastBurstAccelAcRmsLocked(int vibState, boolean isAccelEvent) {
        boolean isOn = vibState == VibrationBurstController.STATE_ON;
        if (isOn && !accelRmsWindowOn) {
            resetAccelRmsWindowLocked();
            accelRmsWindowOn = true;
        } else if (!isOn && accelRmsWindowOn) {
            finalizeAccelRmsWindowLocked();
            resetAccelRmsWindowLocked();
            accelRmsWindowOn = false;
            return;
        }

        if (isOn && isAccelEvent) {
            // WHY: 重力/DC成分はON窓内平均として後で引くため、ここでは加速度マグニチュードだけを逐次集計する。
            double mag = Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);
            accelMagSumOn += mag;
            accelMagSumSqOn += mag * mag;
            accelMagCountOn++;
        }
    }

    private void finalizeAccelRmsWindowLocked() {
        if (accelMagCountOn <= 0) {
            lastBurstAccelAcRms = 0.0;
            return;
        }
        double mean = accelMagSumOn / accelMagCountOn;
        double variance = accelMagSumSqOn / accelMagCountOn - mean * mean;
        lastBurstAccelAcRms = Math.sqrt(Math.max(0.0, variance));
    }

    private void resetAccelRmsWindowLocked() {
        accelMagSumOn = 0.0;
        accelMagSumSqOn = 0.0;
        accelMagCountOn = 0;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 生値収録なので端末側補正は行わない。精度変化は必要ならPC側でSensorEvent時刻から評価する。
    }

    public double getSampleRateHintHz() {
        long nowElapsedNs = SystemClock.elapsedRealtimeNanos();
        synchronized (lock) {
            if (samples.size() < 2 || lastSampleNs <= 0L) {
                return 0.0;
            }
            // WHY: 開始以来の累積平均だと録画/TEST中の変化が見えず、停止後も古い平均が残って見える。
            //      UI表示用は直近約1秒のサンプルだけからライブ推定する。
            long windowStartNs = Math.max(lastSampleNs - 1_000_000_000L, nowElapsedNs - 1_000_000_000L);
            int count = 0;
            long oldestNs = 0L;
            long newestNs = 0L;
            for (int i = samples.size() - 1; i >= 0; i--) {
                Sample sample = samples.get(i);
                if (sample.tElapsedNs < windowStartNs) {
                    break;
                }
                if (count == 0) {
                    newestNs = sample.tElapsedNs;
                }
                oldestNs = sample.tElapsedNs;
                count++;
            }
            if (count < 2 || newestNs <= oldestNs) {
                return 0.0;
            }
            double durationSeconds = (newestNs - oldestNs) / 1_000_000_000.0;
            return durationSeconds > 0.0 ? (count - 1) / durationSeconds : 0.0;
        }
    }

    public double getLastBurstAccelAcRms() {
        synchronized (lock) {
            return lastBurstAccelAcRms;
        }
    }

    public double computeAccelRmsOnMedian(long startElapsedNs, long endElapsedNs) {
        if (startElapsedNs <= 0L || endElapsedNs <= startElapsedNs) {
            return 0.0;
        }
        List<Sample> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(samples);
        }
        List<Double> burstRmsValues = new ArrayList<>();
        int currentBurstId = Integer.MIN_VALUE;
        double sumSquares = 0.0;
        int count = 0;
        for (Sample sample : snapshot) {
            if (sample.tElapsedNs < startElapsedNs || sample.tElapsedNs > endElapsedNs) {
                continue;
            }
            if (sample.vibState != VibrationBurstController.STATE_ON) {
                if (count > 0) {
                    burstRmsValues.add(Math.sqrt(sumSquares / count));
                    sumSquares = 0.0;
                    count = 0;
                    currentBurstId = Integer.MIN_VALUE;
                }
                continue;
            }
            if (currentBurstId != Integer.MIN_VALUE && sample.burstId != currentBurstId && count > 0) {
                burstRmsValues.add(Math.sqrt(sumSquares / count));
                sumSquares = 0.0;
                count = 0;
            }
            currentBurstId = sample.burstId;
            double magnitudeSquared =
                    sample.accelX * sample.accelX
                            + sample.accelY * sample.accelY
                            + sample.accelZ * sample.accelZ;
            sumSquares += magnitudeSquared;
            count++;
        }
        if (count > 0) {
            burstRmsValues.add(Math.sqrt(sumSquares / count));
        }
        if (burstRmsValues.isEmpty()) {
            return 0.0;
        }
        java.util.Collections.sort(burstRmsValues);
        int middle = burstRmsValues.size() / 2;
        if ((burstRmsValues.size() & 1) == 1) {
            return burstRmsValues.get(middle);
        }
        return (burstRmsValues.get(middle - 1) + burstRmsValues.get(middle)) / 2.0;
    }

    public String buildCsv() {
        List<Sample> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(samples);
        }
        StringBuilder csv = new StringBuilder();
        csv.append(CSV_HEADER);
        for (Sample sample : snapshot) {
            csv.append(sample.tElapsedNs).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.accelX)).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.accelY)).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.accelZ)).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.gyroX)).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.gyroY)).append(", ")
                    .append(String.format(Locale.US, "%.6f", sample.gyroZ)).append(", ")
                    .append(sample.vibState).append(", ")
                    .append(sample.burstId).append(", ")
                    .append(sample.phase)
                    .append("\n");
        }
        return csv.toString();
    }
}
