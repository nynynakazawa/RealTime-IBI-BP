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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PressureAnalyze extends AppCompatActivity {

    /* ======== constants ======== */
    private static final int CAM_PERM = 88;
    private static final long EXPOSURE_NS = 8_000_000L;   // 8 ms
    private static final int ISO = 800;
    private static final int BLOCK_MS = 8_000;            // 8 s each level

    /* ======== ui ======== */
    private Button btnGuide;

    /* ======== camera / analysis ======== */
    private ListenableFuture<ProcessCameraProvider> providerFuture;
    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();

    /* ======== signal buffers ======== */
    private double sumAmp;
    private int ampSamples;
    private final double[] amps = new double[3];
    private int blockIndex = 0; // 0‑LIGHT,1‑MED,2‑STRONG

    /* ======== smoothing & normalization buffers ======== */
    private static final int SMOOTHING_WINDOW = 5;
    private final Deque<Double> recentValues = new ArrayDeque<>();
    private final Deque<Double> correctedWindow = new ArrayDeque<>();
    private static final int LONG_WINDOW = 40;

    /* ======== handler for timed UI updates ======== */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pressure_analyze);

        btnGuide = findViewById(R.id.btnPressGuide);
        btnGuide.setOnClickListener(v -> startMeasurement());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAM_PERM);
        } else {
            initCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == CAM_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            initCamera();
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

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

    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void bindAnalysis(ProcessCameraProvider provider) {
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(240, 180));

        Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
        try {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, EXPOSURE_NS);
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, ISO);
        } catch (Exception ignored) {
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true);
        }
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true);
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(30, 30));

        ImageAnalysis analysis = builder.build();
        analysis.setAnalyzer(analyzerExecutor, this::processFrame);

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        provider.unbindAll();
        provider.bindToLifecycle(this, selector, analysis);
    }

    private void startMeasurement() {
        btnGuide.setEnabled(false);
        blockIndex = 0;
        sumAmp = 0; ampSamples = 0;
        recentValues.clear();
        correctedWindow.clear();
        updateLabel(blockIndex);
        uiHandler.postDelayed(blockSwitcher, BLOCK_MS);
    }

    private final Runnable blockSwitcher = new Runnable() {
        @Override public void run() {
            amps[blockIndex] = ampSamples > 0 ? sumAmp / ampSamples : 0;
            blockIndex++;
            sumAmp = 0; ampSamples = 0;
            recentValues.clear();

            if (blockIndex < 3) {
                updateLabel(blockIndex);
                uiHandler.postDelayed(this, BLOCK_MS);
            } else {
                estimateBP();
            }
        }
    };

    private void updateLabel(int idx) {
        switch (idx) {
            case 0: btnGuide.setText("軽く押してください"); break;
            case 1: btnGuide.setText("中程度に押してください"); break;
            case 2: btnGuide.setText("強く押してください"); break;
        }
    }

    private void processFrame(@NonNull ImageProxy proxy) {
        @OptIn(markerClass = ExperimentalGetImage.class)
        android.media.Image img = proxy.getImage();
        if (img != null && img.getFormat() == ImageFormat.YUV_420_888) {
            double g = extractGreen(img);
            recentValues.addLast(g);
            if (recentValues.size() > SMOOTHING_WINDOW) recentValues.removeFirst();
            double smoothG = recentValues.stream().mapToDouble(d->d).average().orElse(g);

            // advanced normalization and dynamic range enhancement
            correctedWindow.addLast(smoothG);
            if (correctedWindow.size() > LONG_WINDOW) correctedWindow.removeFirst();

            // compute local range
            double min=Double.MAX_VALUE, max=Double.MIN_VALUE;
            for(double v: correctedWindow){ min=Math.min(min,v); max=Math.max(max,v);}
            double range = max - min; if(range<1) range=1;
            double normalized = ((smoothG - min)/range)*100;

            sumAmp += normalized;
            ampSamples++;
        }
        proxy.close();
    }

    private double extractGreen(android.media.Image img) {
        ByteBuffer uBuf = img.getPlanes()[1].getBuffer();
        int w=img.getWidth(),h=img.getHeight();
        int sx=w/4,sy=h/4,ex=3*w/4,ey=3*h/4;
        int sum=0,cnt=0;
        for(int y=sy;y<ey;y++) for(int x=sx;x<ex;x++){
            int idx=y*w+x; if(idx<uBuf.capacity()){ sum+=uBuf.get(idx)&0xFF; cnt++;}}
        return cnt>0?(double)sum/cnt:0;
    }

    private void estimateBP() {
        double ratio1 = amps[1]/amps[0];
        ratio1 = Math.abs(ratio1);
        double ratio2 = amps[2]/amps[1];
        ratio2 = Math.abs(ratio2);

        double sbp = 110 + 40*ratio1;
        double dbp =  70 + 25*ratio2;

        Intent out = new Intent();
        out.putExtra("BP_MAX", sbp);
        out.putExtra("BP_MIN", dbp);
        setResult(Activity.RESULT_OK, out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analyzerExecutor.shutdown();
        uiHandler.removeCallbacks(blockSwitcher);
    }
}
