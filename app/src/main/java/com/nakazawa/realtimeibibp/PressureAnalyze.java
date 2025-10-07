package com.nakazawa.realtimeibibp;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.widget.Button;
import android.widget.Toast;
import android.util.Log;
import android.content.res.AssetFileDescriptor;
import android.widget.TextView;
import android.os.CountDownTimer;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.DoubleStream;

public class PressureAnalyze extends AppCompatActivity {

    // ===== 固定パラメータ =====
    private static final int CAM_PERM     = 88;           // カメラ権限リクエストコード
    private static final long EXPOSURE_NS = 8_000_000L;   // 露出時間 (8ms)
    private static final int ISO          = 800;          // ISO感度
    private static final int BLOCK_MS     = 8_000;        // 各圧力レベルの測定時間 (8秒)
    // 「軽く押してください」だけ 20 秒にするための定数
    private static final int FIRST_BLOCK_MS = 20_000;
    // ブロック開始時刻
    private long blockStartTime;
    private TextView tvCountdown;         // カウントダウン表示用
    private CountDownTimer countDownTimer; // 各ブロックのカウントダウンタイマー
    // ===== UI =====
    private Button btnGuide;  // 指示用ボタン

    // ===== Camera / Analysis =====
    private ListenableFuture<ProcessCameraProvider> providerFuture;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

    // ===== 生信号バッファ =====
    private final double[] amps = new double[3];  // 3段階の平均振幅
    private int blockIndex = 0;                   // 現在のブロックインデックス (0,1,2)
    private double sumAmp = 0;                    // 合計振幅
    private int ampSamples = 0;                   // サンプル数

    // ===== HR & mNPV =====

    // ===== 信号補正用バッファ =====
    private final List<Double> greenValues                   = new ArrayList<>();
    private final List<Double> recentCorrectedGreenValues    = new ArrayList<>();
    private final List<Double> smoothedCorrectedGreenValues  = new ArrayList<>();

    // 一連の correctedGreenValue 保持数（お好みで調整）
    private static final int CORRECTED_GREEN_VALUE_WINDOW_SIZE = 40;
    private final Deque<Double> recentG    = new ArrayDeque<>(); // 最近の生グリーン値
    private final Deque<Double> normWinG   = new ArrayDeque<>(); // 正規化用ウィンドウ
    private final List<Long>    peakTimes  = new ArrayList<>();  // ピーク時刻リスト
    private final List<Double> smoothedBpmHistory = new ArrayList<>();
    private double lastSmoothedBpmValue = 0.0;
    private static final int WINDOW_SIZE = 8;          // 8 フレーム分の履歴
    private final double[] window = new double[WINDOW_SIZE];
    private int windowIndex = 0;
    private static final int REFRACTORY_FRAMES = 6;    // Refractory period ≈ 200 ms（30 fps 前提）
    private int framesSinceLastPeak = REFRACTORY_FRAMES;
    private final List<Double> bpmHistory = new ArrayList<>();
    private static final int BPM_HISTORY_SIZE = 10;
    private long lastPeakTime = 0;                     // 直前のピーク時刻

    // ===== UI Handler =====
    private final Handler uiH = new Handler(Looper.getMainLooper());

    // ===== ML =====
    private Interpreter tflite;  // TFLiteモデル用インタプリタ
    private double[] linCoefSBP = { 55, 0.5,  0.03 };   // 線形回帰SBP係数 {切片, HR係数, mNPV係数}
    private double[] linCoefDBP = { 40, 0.3,  0.02 };  // 線形回帰DBP係数

    // ===== モデル読み込みメソッド =====
    private void loadModels() {
        try {
            // TFLiteモデルをassetsから読み込む
            AssetFileDescriptor afd = getAssets().openFd("bp_model.tflite");
            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
            FileChannel fc = fis.getChannel();
            MappedByteBuffer mb = fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
            fis.close();
            tflite = new Interpreter(mb);
        } catch (IOException e) {
            // モデルがなければnullにして線形回帰にフォールバック
            tflite = null;
        }
        try {
            // 追加でLightGBM等の係数ファイルを読み込む (bp_lgbm.txt)
            InputStream is = getAssets().open("bp_lgbm.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            String[] sbpParts = br.readLine().split(",");
            String[] dbpParts = br.readLine().split(",");
            for (int i = 0; i < 3; i++) {
                linCoefSBP[i] = Double.parseDouble(sbpParts[i]);
                linCoefDBP[i] = Double.parseDouble(dbpParts[i]);
            }
            br.close();
        } catch (Exception ignore) {}
    }

    // ===== ライフサイクル：onCreate =====
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_pressure_analyze);

        // モデル＆係数読み込み
        loadModels();
        Log.d("PressureAnalyze", "loadModels: tflite is " + (tflite != null ? "available" : "null"));

        // UI初期化
        btnGuide = findViewById(R.id.btnPressGuide);
        tvCountdown = findViewById(R.id.tv_countdown);
        btnGuide.setOnClickListener(v -> startMeasurement());

        // カメラ権限チェック
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        } else {
            initCamera();
        }
    }

    // ===== カメラ権限結果ハンドラ =====
    @Override
    public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == CAM_PERM && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) {
            initCamera();
        } else {
            Toast.makeText(this,"Camera permission denied",Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ===== カメラ初期化メソッド =====
    private void initCamera() {
        providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                bindAnalysis(provider);
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ===== ImageAnalysisバインドメソッド =====
    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void bindAnalysis(ProcessCameraProvider provider) {
        ImageAnalysis.Builder b = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(240,180));

        Camera2Interop.Extender ext = new Camera2Interop.Extender<>(b);
        try {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, ISO);
        } catch (Exception ignored) {}
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30,30));

        ImageAnalysis ia = b.build();
        ia.setAnalyzer(analyzerExecutor, this::processFrame);

        CameraSelector cs = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        provider.unbindAll();
        provider.bindToLifecycle(this, cs, ia);
    }

    // ===== 測定開始メソッド =====
    private void startMeasurement() {
        btnGuide.setEnabled(false);   // ボタンを無効化して連打防止
        blockIndex = 0;               // ブロックインデックス初期化
        sumAmp = 0; ampSamples = 0;   // 振幅バッファ初期化
        recentG.clear(); normWinG.clear(); peakTimes.clear(); // リストクリア
        updateLabel(0);               // 最初の指示表示
        startBlockCountdown();// 8秒後に次ブロック実行
    }

    // ===== 各ブロック８秒カウントダウン開始メソッド =====
    private void startBlockCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        // ブロックごとに継続時間を切り替え
        long duration = (blockIndex == 0) ? FIRST_BLOCK_MS : BLOCK_MS;
        blockStartTime = System.currentTimeMillis();
        countDownTimer = new CountDownTimer(duration, 1000) {
            @Override public void onTick(long millisUntilFinished) {
                tvCountdown.setText(String.valueOf((int)(millisUntilFinished/1000)));
            }
            @Override public void onFinish() {
                tvCountdown.setText("0");
                uiH.post(blockSwitcher);
            }
        }.start();
    }

    // ===== 各ブロック完了後の切り替え処理 =====
    private final Runnable blockSwitcher = new Runnable() {
        @Override
        public void run() {
            amps[blockIndex] = ampSamples > 0 ? sumAmp / ampSamples : 0;
            Log.d("PressureAnalyze", String.format(
                    "blockSwitcher: finished block %d, avgAmp=%.2f",
                    blockIndex, amps[blockIndex]));
            blockIndex++;
            sumAmp = 0;
            ampSamples = 0;
            recentG.clear();

            if (blockIndex < 3) {
                updateLabel(blockIndex);
                startBlockCountdown();
            } else {
                estimateBP();
            }
        }
    };

    // ===== 指示テキスト更新メソッド =====
    private void updateLabel(int i) {
        switch(i) {
            case 0: btnGuide.setText("軽く押してください");    break;
            case 1: btnGuide.setText("中程度に押してください"); break;
            case 2: btnGuide.setText("強く押してください");    break;
        }
    }

    // ===== 各フレーム解析メソッド =====
    private void processFrame(@NonNull ImageProxy proxy) {
        @OptIn(markerClass = ExperimentalGetImage.class)
        android.media.Image img = proxy.getImage();
        if (img != null && img.getFormat() == ImageFormat.YUV_420_888) {
            double g = extractGreen(img);  // 生グリーン値取得

            // --- ①: 生グリーン値を補正バッファに追加 ---
            greenValues.add(g);

            // --- ②: 新しい信号補正処理（既に組み込まれているもの）---
            double correctedGreenValue = greenValues.get(greenValues.size() - 1) * 10;

            if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
                recentCorrectedGreenValues.remove(0);
            }
            recentCorrectedGreenValues.add(correctedGreenValue);

            if (recentCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {

                double smoothedCorrectedGreenValue = 0.0;
                int smoothingWindowSize1 = 6;
                for (int i = 0; i < smoothingWindowSize1; i++) {
                    int index = recentCorrectedGreenValues.size() - 1 - i;
                    if (index >= 0) {
                        smoothedCorrectedGreenValue += recentCorrectedGreenValues.get(index);
                    }
                }
                smoothedCorrectedGreenValue /= Math.min(smoothingWindowSize1, recentCorrectedGreenValues.size());

                if (smoothedCorrectedGreenValues.size() >= CORRECTED_GREEN_VALUE_WINDOW_SIZE) {
                    smoothedCorrectedGreenValues.remove(0);
                }
                smoothedCorrectedGreenValues.add(smoothedCorrectedGreenValue);

                double twiceSmoothedValue = 0.0;
                int smoothingWindowSize2 = 4;
                for (int i = 0; i < smoothingWindowSize2; i++) {
                    int index = smoothedCorrectedGreenValues.size() - 1 - i;
                    if (index >= 0) {
                        twiceSmoothedValue += smoothedCorrectedGreenValues.get(index);
                    }
                }
                twiceSmoothedValue /= Math.min(smoothingWindowSize2, smoothedCorrectedGreenValues.size());
                correctedGreenValue = twiceSmoothedValue;
            }

            sumAmp += correctedGreenValue;
            ampSamples++;


            Log.d("PressureAnalyze", String.format(
                    "processFrame: rawG=%.2f, correctedG=%.2f, sumAmp=%.2f, ampSamples=%d",
                    g, correctedGreenValue, sumAmp, ampSamples
            ));

            // --- 高精度ピーク検出ロジック ---
            window[windowIndex] = correctedGreenValue;
            windowIndex = (windowIndex + 1) % WINDOW_SIZE;

            double cur  = window[(windowIndex + WINDOW_SIZE - 1) % WINDOW_SIZE];
            double p1   = window[(windowIndex + WINDOW_SIZE - 2) % WINDOW_SIZE];
            double p2   = window[(windowIndex + WINDOW_SIZE - 3) % WINDOW_SIZE];
            double p3   = window[(windowIndex + WINDOW_SIZE - 4) % WINDOW_SIZE];
            double p4   = window[(windowIndex + WINDOW_SIZE - 5) % WINDOW_SIZE];

            // 下降後に山形になっている箇所をピークと判断
            if (framesSinceLastPeak >= REFRACTORY_FRAMES
                    && p1 > p2 && p2 > p3 && p3 > p4 && p1 > cur) {
                framesSinceLastPeak = 0;
                long now = System.currentTimeMillis();

                // --- ピーク時刻の記録＆BPM検出＋平滑化（最初の12秒でも実行）---
                peakTimes.add(now);
                if (lastPeakTime != 0) {
                    double intervalSec = (now - lastPeakTime) / 1000.0;   // RR間隔
                    if (intervalSec > 0.4 && intervalSec < 1.5) {
                        double newBPM = 60.0 / intervalSec;

                        if (bpmHistory.size() >= BPM_HISTORY_SIZE) {
                            bpmHistory.remove(0);
                        }
                        bpmHistory.add(newBPM);

                        double prev = smoothedBpmHistory.isEmpty()
                                ? newBPM
                                : smoothedBpmHistory.get(smoothedBpmHistory.size() - 1);
                        double smoothedBpmValue = (prev + newBPM) / 2.0;
                        smoothedBpmHistory.add(smoothedBpmValue);
                        lastSmoothedBpmValue = smoothedBpmValue;
                    }
                }
                lastPeakTime = now;

                // --- sumAmp／ampSamples は最後の8秒のみ反映 ---
                if (blockIndex != 0
                        || now >= blockStartTime + (FIRST_BLOCK_MS - BLOCK_MS)) {
                    sumAmp += correctedGreenValue;
                    ampSamples++;
                }
            }
            framesSinceLastPeak++;
        }
        proxy.close(); // リソース解放
    }

    // ===== グリーン値抽出メソッド =====
    private double extractGreen(android.media.Image img) {
        ByteBuffer u = img.getPlanes()[1].getBuffer(); // UプレーンからG相当値
        int w = img.getWidth(), h = img.getHeight();
        int sx = w/4, sy = h/4, ex = 3*w/4, ey = 3*h/4;
        int sum = 0, cnt = 0;
        for (int y = sy; y < ey; y++) {
            for (int x = sx; x < ex; x++) {
                int idx = y * w + x;
                if (idx < u.capacity()) {
                    sum += u.get(idx) & 0xFF;
                    cnt++;
                }
            }
        }
        return cnt > 0 ? (double) sum / cnt : 0;
    }

    // ===== 血圧推定メソッド =====
    private void estimateBP() {
        // --- IBI（心拍間隔）を計算 ---
        List<Long> ibi = new ArrayList<>();
        for (int i = 1; i < peakTimes.size(); i++) {
            ibi.add(peakTimes.get(i) - peakTimes.get(i-1));
        }

        double meanHR;
        if (ibi.size() >= 1) {
            // 平均心拍数 (ms→bpm)
            meanHR = 60000.0 / ibi.stream().mapToLong(l->l).average().orElse(800);
        } else {
            meanHR = 75; // ピークがない場合はデフォルト
        }

        // --- mNPV は3ブロック平均振幅の平均 ---
        double meanNPV = DoubleStream.of(amps).average().orElse(1);

        // --- LOG ---
        Log.d("PressureAnalyze", String.format(
                "CALIB: meanHR=%.2f, meanNPV=%.2f", meanHR, meanNPV
        ));

        // --- ML or 線形回帰で推定(現在は線形回帰) ---
        double sbp, dbp;
        if (tflite != null) {
            float[] in  = { (float) meanHR, (float) meanNPV };
            float[][] out= new float[1][2];
            tflite.run(in, out);
            sbp = out[0][0];
            dbp = out[0][1];
        } else {
            sbp = linCoefSBP[0] + linCoefSBP[1] * meanHR + linCoefSBP[2] * meanNPV;
            dbp = linCoefDBP[0] + linCoefDBP[1] * meanHR + linCoefDBP[2] * meanNPV;
        }

        // 結果をMainActivityに返却
        Intent ret = new Intent();
        ret.putExtra("BP_MAX", sbp);
        ret.putExtra("BP_MIN", dbp);
        setResult(Activity.RESULT_OK, ret);
        finish();
    }

    // ===== リソース解放メソッド =====
    @Override
    protected void onDestroy() {
        super.onDestroy();
        analyzerExecutor.shutdown();         // Executor停止
        uiH.removeCallbacks(blockSwitcher);   // Handler解除
    }
}