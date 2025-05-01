package com.example.realtimehribicontrol;

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

    /* ---------- 固定パラメータ ---------- */
    private static final int CAM_PERM       = 88;
    private static final long EXPOSURE_NS   = 8_000_000L;        // 8 ms
    private static final int  ISO           = 800;
    private static final int  BLOCK_MS      = 8_000;             // 8 s × 3 レベル

    /* ---------- UI ---------- */
    private Button btnGuide;

    /* ---------- Camera / Analysis ---------- */
    private ListenableFuture<ProcessCameraProvider> providerFuture;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

    /* ---------- 生信号バッファ ---------- */
    private final double[] amps = new double[3];
    private int   blockIndex   = 0;
    private double sumAmp      = 0;
    private int    ampSamples  = 0;

    /* ---------- HR & mNPV ---------- */
    private static final int  SMOOTH_W   = 5;
    private static final int  LONG_W     = 40;
    private static final double PEAK_TH  = 20.0;          // 追加：ピーク検出閾値
    private final Deque<Double> recentG  = new ArrayDeque<>();
    private final Deque<Double> normWinG = new ArrayDeque<>();
    private final List<Long>    peakTimes= new ArrayList<>();
    private double lastSmooth = Double.NaN;               // 追加：直前の平滑値

    /* ---------- UI Handler ---------- */
    private final Handler uiH = new Handler(Looper.getMainLooper());

    /* ---------- ML ---------- */
    // ---★ ここから追加：モデル読み込み＆係数 -------------------------
    private Interpreter tflite;
    private double[] linCoefSBP = { 80, 0.5, 0.30 };      // 修正：実験的な小さい係数
    private double[] linCoefDBP = { 50, 0.35, 0.25 };



    private void loadModels() {
        try {                                  // ① TFLite を優先
            AssetFileDescriptor afd = getAssets().openFd("bp_model.tflite");
            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
            FileChannel fc = fis.getChannel();
            MappedByteBuffer mb = fc.map(
                    FileChannel.MapMode.READ_ONLY,
                    afd.getStartOffset(),
                    afd.getLength()
            );
            fis.close();
            tflite = new Interpreter(mb);
        } catch (IOException e) {
            tflite = null;                     // 無ければ線形回帰へフォールバック
        }
        try {                                  // ② オプション：LightGBM 係数
            InputStream is = getAssets().open("bp_lgbm.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            // （簡易）ファイル先頭に intercept, HRcoef, mNPVcoef が CSV で入っている想定
            String[] sbpParts = br.readLine().split(",");
            String[] dbpParts = br.readLine().split(",");
            for (int i = 0; i < 3; i++) {
                linCoefSBP[i] = Double.parseDouble(sbpParts[i]);
                linCoefDBP[i] = Double.parseDouble(dbpParts[i]);
            }
            br.close();
        } catch (Exception ignore) {}
    }
    // ---★ ここまで追加 ---------------------------------------------

    /* ---------- lifecycle ---------- */
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_pressure_analyze);

        loadModels();
        Log.d("PressureAnalyze", "loadModels: tflite is " + (tflite != null ? "available" : "null"));

        btnGuide = findViewById(R.id.btnPressGuide);
        btnGuide.setOnClickListener(v -> startMeasurement());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        } else initCamera();
    }

    @Override public void onRequestPermissionsResult(int r,@NonNull String[] p,@NonNull int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==CAM_PERM && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) initCamera();
        else{
            Toast.makeText(this,"Camera permission denied",Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    /* ---------- camera ---------- */
    private void initCamera() {
        providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                bindAnalysis(provider);
            } catch (ExecutionException | InterruptedException e) { e.printStackTrace(); }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void bindAnalysis(ProcessCameraProvider provider) {
        ImageAnalysis.Builder b = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(240,180));

        Camera2Interop.Extender ext = new Camera2Interop.Extender<>(b);
        try{
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, ISO);
        }catch(Exception ignored){}
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK,true);
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(30,30));

        ImageAnalysis ia = b.build();
        ia.setAnalyzer(analyzerExecutor,this::processFrame);

        CameraSelector cs = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();

        provider.unbindAll();
        provider.bindToLifecycle(this,cs,ia);
    }

    /* ---------- measurement flow ---------- */
    private void startMeasurement(){
        btnGuide.setEnabled(false);
        blockIndex=0;sumAmp=0;ampSamples=0;
        recentG.clear(); normWinG.clear(); peakTimes.clear();
        updateLabel(0);
        uiH.postDelayed(blockSwitcher, BLOCK_MS);
    }
    private final Runnable blockSwitcher = new Runnable() {
        @Override
        public void run() {
            amps[blockIndex] = ampSamples > 0 ? sumAmp / ampSamples : 0;
            Log.d("PressureAnalyze", String.format(
                    "blockSwitcher: finished block %d, avgAmp=%.2f", blockIndex, amps[blockIndex]));
            blockIndex++;
            sumAmp = 0;
            ampSamples = 0;
            recentG.clear();

            if (blockIndex < 3) {
                updateLabel(blockIndex);
                uiH.postDelayed(this, BLOCK_MS);
            } else {
                estimateBP();
            }
        }
    };
    private void updateLabel(int i){
        switch(i){
            case 0:btnGuide.setText("軽く押してください");break;
            case 1:btnGuide.setText("中程度に押してください");break;
            case 2:btnGuide.setText("強く押してください");break;
        }
    }

    /* ---------- per-frame analysis ---------- */
    private void processFrame(@NonNull ImageProxy proxy){
        @OptIn(markerClass = ExperimentalGetImage.class)
        android.media.Image img = proxy.getImage();
        if(img!=null && img.getFormat()==ImageFormat.YUV_420_888){
            double g = extractGreen(img);

            /* --- スムージング & ローカル正規化 --- */
            recentG.addLast(g); if(recentG.size()>SMOOTH_W) recentG.removeFirst();
            double sG = recentG.stream().mapToDouble(d->d).average().orElse(g);

            normWinG.addLast(sG); if(normWinG.size()>LONG_W) normWinG.removeFirst();
            double min = normWinG.stream().mapToDouble(d->d).min().orElse(sG);
            double max = normWinG.stream().mapToDouble(d->d).max().orElse(sG);
            double nG  = ((sG - min) / Math.max(max - min, 1)) * 100;

            sumAmp += nG;
            ampSamples++;

            Log.d("PressureAnalyze", String.format(
                    "processFrame: rawG=%.2f, smoothG=%.2f, normalizedG=%.2f, sumAmp=%.2f, ampSamples=%d",
                    g, sG, nG, sumAmp, ampSamples));

            /* --- mNPV 計算用ピーク探索（単純ゼロ交差） --- */
            if (!Double.isNaN(lastSmooth) && lastSmooth < PEAK_TH && sG >= PEAK_TH) {
                peakTimes.add(System.currentTimeMillis());
            }
            lastSmooth = sG;
        }
        proxy.close();
    }

    private double extractGreen(android.media.Image img){
        ByteBuffer u = img.getPlanes()[1].getBuffer();
        int w=img.getWidth(),h=img.getHeight(), sx=w/4,sy=h/4, ex=3*w/4,ey=3*h/4;
        int s=0,c=0;
        for(int y=sy;y<ey;y++) for(int x=sx;x<ex;x++){
            int idx=y*w+x; if(idx<u.capacity()){ s+=u.get(idx)&0xFF; c++;}}
        return c>0?(double)s/c:0;
    }

    /* ---------- BP estimation ---------- */
    private void estimateBP(){

        // --- IBI（心拍間隔）を計算する ---
        List<Long> ibi = new ArrayList<>();
        for(int i = 1; i < peakTimes.size(); i++){
            ibi.add(peakTimes.get(i) - peakTimes.get(i-1));
        }

        double meanHR;
        if(ibi.size() >= 1){
            // 1回以上ピークが検出できたら平均HR を算出
            meanHR = 60000.0 / ibi.stream().mapToLong(l -> l).average().orElse(800);
        } else {
            // 検出できなければ従来のデフォルト
            meanHR = 75;
        }

        // --- mNPV は常にブロック毎の平均振幅から算出 ---
        double meanNPV = DoubleStream.of(amps)
                .average()
                .orElse(1);

        /* --- 回帰 or ML 推論 --- */
        double sbp, dbp;
        if(tflite != null){
            float[] in  = { (float)meanHR, (float)meanNPV };
            float[][] out= new float[1][2];
            tflite.run(in, out);
            sbp = out[0][0];
            dbp = out[0][1];
        } else {
            sbp = linCoefSBP[0] + linCoefSBP[1] * meanHR + linCoefSBP[2] * meanNPV;
            dbp = linCoefDBP[0] + linCoefDBP[1] * meanHR + linCoefDBP[2] * meanNPV;
        }

        Intent ret = new Intent();
        ret.putExtra("BP_MAX", sbp);
        ret.putExtra("BP_MIN", dbp);
        setResult(Activity.RESULT_OK, ret);
        finish();
    }

    @Override protected void onDestroy(){
        super.onDestroy();
        analyzerExecutor.shutdown();
        uiH.removeCallbacks(blockSwitcher);
    }
}