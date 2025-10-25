// ─────────────────── GreenValueAnalyzer.java ───────────────────
package com.nakazawa.realtimeibibp;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.os.*;
import android.hardware.camera2.CaptureRequest;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.core.Preview;
import androidx.camera.core.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import android.graphics.Color;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.TotalCaptureResult;

public class GreenValueAnalyzer implements LifecycleObserver {

    // ===== UI =====
    private final Context ctx;
    private final LineChart chart;
    private final TextView tvValue, tvIbi, tvMsg, tvSd, tvHr, tvSmIbi, tvSmHr;

    // ===== グラフデータ =====
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> idealCurveEntries = new ArrayList<>();  // 理想曲線用
    private final LineDataSet dataSet;
    private LineDataSet idealCurveDataSet;  // 理想曲線用
    private final LineData data;
    private String activeLogic = "Logic1";
    private long chartStartTime = 0;  // チャート開始時刻（理想曲線の時刻計算用）

    // ===== 記録 =====
    private final List<Double> recValue = new ArrayList<>(),
            recIbi   = new ArrayList<>(),
            recSd    = new ArrayList<>(),
            recSmIbi = new ArrayList<>(),
            recSmBpm = new ArrayList<>();
    private final List<Long>   recValTs = new ArrayList<>(),
            recIbiTs = new ArrayList<>();
    
    // ===== Sin波記録用 =====
    private final List<Double> recSinWave = new ArrayList<>();  // 理想曲線の値
    private final List<Double> recSinAmplitude = new ArrayList<>();  // 振幅
    private final List<Double> recSinMean = new ArrayList<>();  // 平均値
    private final List<Double> recSinIBI = new ArrayList<>();  // IBI
    private final List<Long> recSinTs = new ArrayList<>();  // タイムスタンプ

    // ===== 状態 =====
    private boolean camOpen;
    private double  IBI;
    private boolean isRecordingActive = false;

    // ISO管理
    private int currentISO = 600; // デフォルト値
    private boolean isDetectionEnabled = true; // 検出有効フラグ
    
    // 直前の有効な値を保持（ISO < 300の時に使用）
    private double lastValidBpm = 0.0;
    private double lastValidSd = 0.0;

    // カメラ設定情報
    private float currentFNumber = 0.0f;
    private long currentExposureTime = 0;
    private int currentWhiteBalanceMode = 0;
    private float currentFocusDistance = 0.0f;
    
    // カメラ関連
    private ProcessCameraProvider cameraProvider;
    private CameraSelector cameraSelector;

    // ===== ロジック =====
    private final Map<String, LogicProcessor> logicMap = new HashMap<>();

    // ===== ハンドラ =====
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 外部から注入されるBP推定器
    private RealtimeBP bpEstimator;
    private SinBP sinBP;  // SinBP推定器

    // MainActivity側の同じReatimeBPをセット
    public void setBpEstimator(RealtimeBP estimator) {
        this.bpEstimator = estimator;
        // Logic1とLogic2への参照を設定
        if (estimator != null) {
            Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
            Logic2 l2 = (Logic2) logicMap.computeIfAbsent("Logic2", k -> new Logic2());
            estimator.setLogicRef(l1);  // デフォルトでLogic1を設定
        }
    }
    
    // SinBPをセット
    public void setSinBP(SinBP estimator) {
        this.sinBP = estimator;
        // Logic1とLogic2への参照を設定
        if (estimator != null) {
            Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
            Logic2 l2 = (Logic2) logicMap.computeIfAbsent("Logic2", k -> new Logic2());
            estimator.setLogicRef(l1);  // デフォルトでLogic1を設定
        }
    }
    
    // Camera X API 色温度関連情報のコールバック
    public interface CameraInfoCallback {
        void onCameraInfoUpdated(float fNumber, int iso, long exposureTime, 
                                float colorTemperature, int whiteBalanceMode, 
                                float focusDistance, float aperture, float sensorSensitivity);
    }
    private CameraInfoCallback cameraInfoCallback;
    public void setCameraInfoCallback(CameraInfoCallback callback) {
        this.cameraInfoCallback = callback;
    }

    // ===== コンストラクタ =====
    public GreenValueAnalyzer(
            Context c, LineChart lc,
            TextView tvV, TextView tvI, TextView tvM,
            TextView tvSd, TextView tvHr,
            TextView tvSmI, TextView tvSmH) {

        ctx      = c; chart = lc;
        tvValue  = tvV; tvIbi = tvI; tvMsg = tvM;
        this.tvSd = tvSd; this.tvHr = tvHr;
        tvSmIbi = tvSmI; tvSmHr = tvSmH;

        chart.getLegend().setEnabled(true);  // 凡例を有効化
        
        // 実測波形データセット
        dataSet = new LineDataSet(entries, "実測波形");
        dataSet.setLineWidth(2);
        dataSet.setColor(Color.parseColor("#78CCCC"));
        dataSet.setDrawValues(false);  // 値ラベルを非表示
        dataSet.setDrawCircles(false);  // 点を非表示
        
        // 理想曲線データセット
        idealCurveDataSet = new LineDataSet(idealCurveEntries, "理想曲線(非対称sin)");
        idealCurveDataSet.setLineWidth(2);
        idealCurveDataSet.setColor(Color.parseColor("#FF6B6B"));  // 赤色
        idealCurveDataSet.setDrawValues(false);
        idealCurveDataSet.setDrawCircles(false);
        // 実線表示（破線を無効化）

        data = new LineData(dataSet, idealCurveDataSet);
        chart.setData(data);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setTextColor(Color.WHITE);  // 凡例の文字色を白に
        
        chartStartTime = System.currentTimeMillis();  // チャート開始時刻を記録

        XAxis x = chart.getXAxis();
        x.setPosition(XAxis.XAxisPosition.BOTTOM);
        x.setDrawGridLines(false);
        x.setTextSize(14f);  // X軸の目盛数字サイズを 14dp 相当に

        YAxis y = chart.getAxisLeft();
        y.setAxisMinimum(0);
        y.setAxisMaximum(255);
        y.setDrawGridLines(false);
        y.setTextSize(14f);  // Y軸の目盛数字サイズを 14dp 相当に

        chart.getAxisRight().setEnabled(false);

        ((LifecycleOwner) ctx).getLifecycle().addObserver(this);
        startCamera();
    }

    // ===== ロジック選択 =====
    public void setActiveLogic(String n) {
        this.activeLogic = n;                // ★修正
        LogicProcessor logicProcessor = logicMap.computeIfAbsent(n, k -> {
            switch (k) {
                default:       return new Logic1();
                case "Logic2": return new Logic2();
            }
        });
        
        // アクティブなロジックに応じてRealtimeBPとSinBPの参照を更新
        if (logicProcessor instanceof BaseLogic) {
            BaseLogic logic = (BaseLogic) logicProcessor;
            if (bpEstimator != null) {
                bpEstimator.setLogicRef(logic);
            }
            if (sinBP != null) {
                sinBP.setLogicRef(logic);
            }
        }
    }

    public double getLatestIbi() { return IBI; }
    
    public double getCurrentIBI() { return IBI; }

    // ===== カメラ起動 =====
    public void startCamera() {
        // Logic1のコールバック設定
        Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
        if (bpEstimator != null) {
            l1.setBPFrameCallback(bpEstimator::update);
        }
        if (sinBP != null) {
            l1.setSinBPCallback(sinBP::update);
        }
        
        // Logic2のコールバック設定
        Logic2 l2 = (Logic2) logicMap.computeIfAbsent("Logic2", k -> new Logic2());
        if (bpEstimator != null) {
            l2.setBPFrameCallback(bpEstimator::update);
        }
        if (sinBP != null) {
            l2.setSinBPCallback(sinBP::update);
        }

        if (camOpen) return;
        ListenableFuture<ProcessCameraProvider> f =
                ProcessCameraProvider.getInstance(ctx);

        f.addListener(() -> {
            try {
                ProcessCameraProvider p = f.get();
                bindImageAnalysis(p);   // bindAnalysis → bindImageAnalysis
                camOpen = true;
            } catch (Exception ignored) { }
        }, ContextCompat.getMainExecutor(ctx));  // ‘context’ → ‘ctx’
    }


    // Camera2Interop の Experimental API を利用するので OptIn を付与
    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(240, 180))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

        // Camera2InteropでCaptureCallbackを追加
        Camera2Interop.Extender<ImageAnalysis> ext = new Camera2Interop.Extender<>(builder);
        ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(30, 30));
        ext.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
                // 動的な値を取得
                Float aperture = result.get(CaptureResult.LENS_APERTURE);
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Long exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer wbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
                Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                // nullチェックとデフォルト値
                float fNumberValue = (aperture != null) ? aperture : 0.0f;
                int isoValue = (iso != null) ? iso : 0;
                long exposureTimeValue = (exposure != null) ? exposure : 0;
                int wbModeValue = (wbMode != null) ? wbMode : 0;
                float focusValue = (focus != null) ? focus : 0.0f;

                                 // コールバックでUIに反映
                 if (cameraInfoCallback != null) {
                     cameraInfoCallback.onCameraInfoUpdated(
                             fNumberValue, isoValue, exposureTimeValue, 0.0f, // 色温度は取得不可
                             wbModeValue, focusValue, fNumberValue, isoValue
                     );
                 }
                 
                 // RealtimeBPにISO値を更新
                 if (bpEstimator != null) {
                     bpEstimator.updateISO(isoValue);
                 }
            }
        });

        ImageAnalysis imageAnalysis = builder.build();
        Executor processingExecutor = Executors.newSingleThreadExecutor();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        imageAnalysis.setAnalyzer(processingExecutor, this::processImage);

        cameraProvider.bindToLifecycle(
                (LifecycleOwner) ctx,
                cameraSelector,
                imageAnalysis);
    }
    
    // Camera X API の色温度関連情報を取得
    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void setupCameraInfoCapture(ProcessCameraProvider cameraProvider, CameraSelector cameraSelector) {
        try {
            // Camera2CameraInfo を取得
            Camera camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) ctx,
                    cameraSelector,
                    new Preview.Builder().build());
            
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            String cameraId = camera2Info.getCameraId();
            
            // Camera2CameraControl を取得
            Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            
            // 定期的にカメラ情報を取得
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (camOpen && cameraInfoCallback != null) {
                        try {
                            // Camera2CameraInfo から情報を取得
                            CameraCharacteristics characteristics = 
                                ((CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE))
                                .getCameraCharacteristics(cameraId);
                            
                            // 現在のカメラ設定値を取得するため、ImageAnalysisのCaptureResultを使用
                            // 注意: 実際の値はImageAnalysisのコールバックで取得する必要があります
                            
                            // F-Number (レンズの絞り値) - 利用可能な値の範囲から取得
                            float[] fNumbers = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                            float fNumberValue = (fNumbers != null && fNumbers.length > 0) ? fNumbers[0] : 0.0f;
                            
                            // ISO感度範囲 - 利用可能な値の範囲から取得
                            Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                            int isoValue = (isoRange != null) ? isoRange.getLower() : 0;
                            
                            // 露出時間範囲 - 利用可能な値の範囲から取得
                            Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                            long exposureTimeValue = (exposureRange != null) ? exposureRange.getLower() : 0;
                            
                            // 利用可能なホワイトバランスモード
                            int[] awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                            float colorTempValue = (awbModes != null && awbModes.length > 0) ? awbModes[0] : 0.0f;
                            
                            // 現在のホワイトバランスモード（実際の値はCaptureRequestから取得する必要がある）
                            int wbModeValue = 0; // デフォルト値
                            
                            // フォーカス距離
                            Float focusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            float focusDistanceValue = (focusDistance != null) ? focusDistance : 0.0f;
                            
                            // 絞り値（F-Numberと同じ）
                            float apertureValue = fNumberValue;
                            
                            // センサー感度（ISOと同じ）
                            float sensorSensitivityValue = isoValue;
                            
                            // コールバックで情報を送信
                            cameraInfoCallback.onCameraInfoUpdated(
                                fNumberValue, isoValue, exposureTimeValue, colorTempValue,
                                wbModeValue, focusDistanceValue, apertureValue, sensorSensitivityValue
                            );
                            
                        } catch (Exception e) {
                            Log.e("GreenValueAnalyzer", "Error getting camera info: " + e.getMessage());
                        }
                    }
                    handler.postDelayed(this, 1000); // 1秒ごとに更新
                }
            }, 1000);
            
        } catch (Exception e) {
            Log.e("GreenValueAnalyzer", "Error setting up camera info capture: " + e.getMessage());
        }
    }

    // ===== フレーム解析 =====
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImage(ImageProxy proxy) {
        Image img = proxy.getImage();
        if (img != null &&
                img.getFormat() == ImageFormat.YUV_420_888) {

            // カメラ設定情報を取得（1秒ごとに更新）
            if (cameraInfoCallback != null && System.currentTimeMillis() % 1000 < 33) { // 約30fpsなので1秒に1回程度
                try {
                    // フロントカメラのIDを直接指定（一般的に"1"）
                    String cameraId = "1";
                    
                    // CameraCharacteristicsから現在の設定値を取得
                    CameraCharacteristics characteristics = 
                        ((CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE))
                        .getCameraCharacteristics(cameraId);
                    
                    // 実際の動的な値を取得するため、Camera2CameraControlを使用
                    Camera camera = cameraProvider.bindToLifecycle(
                            (LifecycleOwner) ctx,
                            cameraSelector,
                            new Preview.Builder().build());
                    
                    Camera2CameraControl camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                    
                    // 現在のカメラ設定値を取得
                    // 注意: 一部の値は依然として固定値になる可能性があります
                    
                    // F-Number (利用可能な絞り値の範囲から取得)
                    float[] fNumbers = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
                    float fNumberValue = (fNumbers != null && fNumbers.length > 0) ? fNumbers[0] : 0.0f;
                    
                    // ISO感度 (利用可能な範囲の最小値)
                    Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                    int isoValue = (isoRange != null) ? isoRange.getLower() : 0;
                    
                    // 露出時間 (利用可能な範囲の最小値)
                    Range<Long> exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                    long exposureTimeValue = (exposureRange != null) ? exposureRange.getLower() : 0;
                    
                    // ホワイトバランスモード (利用可能なモードの最初の値)
                    int[] awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                    float colorTempValue = (awbModes != null && awbModes.length > 0) ? awbModes[0] : 0.0f;
                    
                    // 現在のホワイトバランスモード (デフォルト値)
                    int wbModeValue = 0;
                    
                    // フォーカス距離 (最小フォーカス距離)
                    Float focusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    float focusDistanceValue = (focusDistance != null) ? focusDistance : 0.0f;
                    
                    // 絞り値（F-Numberと同じ）
                    float apertureValue = fNumberValue;
                    
                    // センサー感度（ISOと同じ）
                    float sensorSensitivityValue = isoValue;
                    
                    // コールバックで情報を送信
                    cameraInfoCallback.onCameraInfoUpdated(
                        fNumberValue, isoValue, exposureTimeValue, colorTempValue,
                        wbModeValue, focusDistanceValue, apertureValue, sensorSensitivityValue
                    );
                    
                } catch (Exception e) {
                    Log.e("GreenValueAnalyzer", "Error getting camera info in processImage: " + e.getMessage());
                }
            }

            double g = getGreen(img);
            LogicProcessor lp = logicMap.get(activeLogic);

            if (lp != null) {
                LogicResult r = lp.processGreenValueData(g);
                
                // ISOが300未満の場合でもUI更新とチャート表示は行う
                if (r != null) {
                    lp.calculateSmoothedValueRealTime(r.getIbi(), r.getBpmSd());

                    // 有効な値を保存（ISO < 300の時に使用）
                    if (r.getHeartRate() > 0) {
                        lastValidBpm = r.getHeartRate();
                    }
                    if (r.getBpmSd() > 0) {
                        lastValidSd = r.getBpmSd();
                    }

                    // ★ isRecordingActive フラグが true の場合のみデータを記録
                    if (isRecordingActive && isDetectionValid()) {
                        recValue.add(r.getCorrectedGreenValue());
                        recIbi.add(r.getIbi());
                        recSd.add(r.getBpmSd());
                        recValTs.add(System.currentTimeMillis());
                        recIbiTs.add(System.currentTimeMillis());

                        lp.calculateSmoothedValueRealTime(
                                r.getIbi(), r.getBpmSd());

                        double smI = lp.getLastSmoothedIbi();
                        double smB = (60_000) / smI;

                        recSmIbi.add(smI);
                        recSmBpm.add(smB);
                        
                        // Sin波データの記録
                        long currentTime = System.currentTimeMillis();
                        if (sinBP != null && sinBP.hasIdealCurve()) {
                            double sinWaveValue = sinBP.getIdealCurveValue(currentTime);
                            recSinWave.add(sinWaveValue);
                            recSinAmplitude.add(sinBP.getCurrentAmplitude());
                            recSinMean.add(sinBP.getCurrentMean());
                            recSinIBI.add(sinBP.getCurrentIBI());
                            recSinTs.add(currentTime);
                        } else {
                            // SinBPが利用できない場合は0を記録
                            recSinWave.add(0.0);
                            recSinAmplitude.add(0.0);
                            recSinMean.add(0.0);
                            recSinIBI.add(0.0);
                            recSinTs.add(currentTime);
                        }
                    } else if (isRecordingActive && !isDetectionValid()) {
                        Log.d("GreenValueAnalyzer-ISO", "CSV recording skipped: ISO=" + currentISO);
                    }
                    // ★ IBIの更新とUI更新は記録状態に関わらず行う (リアルタイム表示のため)
                    IBI = r.getIbi();
                    double currentSmI = lp.getLastSmoothedIbi(); // 記録していなくても平滑化IBIは計算される可能性があるため取得
                    double currentSmB = (currentSmI > 0) ? (60_000 / currentSmI) : 0;

                    updateUi(r.getCorrectedGreenValue(),
                            r.getIbi(), r.getHeartRate(),
                            r.getBpmSd(),
                            // ★ UI表示用の平滑化値は、記録中はその時の最新、記録中でなければ直近の計算値を使う
                            isRecordingActive ? recSmIbi.isEmpty() ? 0 : recSmIbi.get(recSmIbi.size()-1) : currentSmI,
                            isRecordingActive ? recSmBpm.isEmpty() ? 0 : recSmBpm.get(recSmBpm.size()-1) : currentSmB
                    );
                } else {
                    // ISOが300未満の場合、UI更新のみ行う（検出は停止）
                    Log.d("GreenValueAnalyzer-ISO", "Detection skipped, UI update only: ISO=" + currentISO);
                    
                    // 前回の値を保持してUI更新
                    double currentSmI = lp.getLastSmoothedIbi();
                    double currentSmB = (currentSmI > 0) ? (60_000 / currentSmI) : 0;
                    
                    updateUi(g, IBI, lastValidBpm, lastValidSd, currentSmI, currentSmB);
                }
            }
        }
        proxy.close();
    }


    private double getGreen(Image img) {
        ByteBuffer u = img.getPlanes()[1].getBuffer();
        int w = img.getWidth(), h = img.getHeight(),
                sx = w / 4, ex = w * 3 / 4,
                sy = h / 4, ey = h * 3 / 4,
                sum = 0, c = 0;

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (y < sy || y >= ey || x < sx || x >= ex) {
                    int idx = y * w + x;
                    if (idx < u.capacity()) {
                        sum += u.get(idx) & 0xFF; c++;
                    }
                }
        return c > 0 ? (double) sum / c : 0;
    }

    // ===== UI更新 =====
    private void updateUi(double v, double ibi,
                          double hr, double sd,
                          double smI, double smB) {
        ui.post(() -> {
            // テキストの更新
            tvValue.setText(String.format("Value : %.2f", v));
            tvIbi.setText(  String.format("IBI : %.2f", ibi));
            tvHr.setText(   String.format("HeartRate : %.2f", hr));
            tvSd.setText(   String.format("BPMSD : %.2f", sd));
            tvSmIbi.setText(String.format("IBI(Smooth) : %.2f", smI));
            tvSmHr.setText(String.format("HR(Smooth) : %.2f", smB));

            // グラフデータの更新
            if (entries.size() > 100) {
                entries.remove(0);
                for (int i = 0; i < entries.size(); i++) {
                    entries.get(i).setX(i);
                }
            }
            entries.add(new Entry(entries.size(), (float) v));
            
            // 理想曲線の更新
            updateIdealCurve();

            // デバッグログ（同フレームの実測値と理想曲線値）
            double idealValForLog = Double.NaN;
            boolean hasIdeal = false;
            if (sinBP != null && sinBP.hasIdealCurve()) {
                long now = System.currentTimeMillis();
                idealValForLog = sinBP.getIdealCurveValue(now);  // 現在時刻を渡す
                hasIdeal = true;
            }
            try {
                Log.d("sindebug", String.format(Locale.getDefault(),
                        "frame=%d, actual=%.4f, ideal=%.4f, hasIdeal=%b",
                        entries.size() - 1, v, idealValForLog, hasIdeal));
            } catch (Exception ignore) {}

            // Y軸の最小/最大を自動調整し、目盛りラベルと軸線を表示
            float minY = entries.stream().map(Entry::getY).min(Float::compare).orElse(0f);
            float maxY = entries.stream().map(Entry::getY).max(Float::compare).orElse(255f);
            float margin = 10f;
            YAxis yAxis = chart.getAxisLeft();
            yAxis.setAxisMinimum(minY - margin);
            yAxis.setAxisMaximum(maxY + margin);
            yAxis.setDrawLabels(true);
            yAxis.setDrawAxisLine(true);
            yAxis.setTextColor(ContextCompat.getColor(ctx, R.color.white));  // 目盛りの文字色を白に

            // X軸の目盛りラベルと軸線を再表示
            XAxis xAxis = chart.getXAxis();
            xAxis.setDrawLabels(true);
            xAxis.setDrawAxisLine(true);
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setDrawGridLines(false);
            xAxis.setTextColor(ContextCompat.getColor(ctx, R.color.white));  // 目盛りの文字色を白に

            // チャート更新
            dataSet.notifyDataSetChanged();
            idealCurveDataSet.notifyDataSetChanged();
            data.notifyDataChanged();
            chart.notifyDataSetChanged();
            chart.setVisibleXRangeMaximum(200);
            chart.moveViewToX(data.getEntryCount());
            chart.invalidate();
        });
    }
    
    /**
     * 理想曲線を更新
     */
    private void updateIdealCurve() {
        if (sinBP == null || !sinBP.hasIdealCurve()) {
            // 理想曲線データがない場合はクリア
            idealCurveEntries.clear();
            return;
        }
        
        // 理想曲線のIBI（周期）を取得
        double ibi = sinBP.getCurrentIBI();
        if (ibi <= 0) {
            idealCurveEntries.clear();
            return;
        }
        
        // 実測波形と同じ数のポイントで理想曲線を生成
        int numPoints = entries.size();
        if (numPoints == 0) {
            idealCurveEntries.clear();
            return;
        }
        
        // 古いエントリーを削除（実測波形と同期）
        if (idealCurveEntries.size() > 100) {
            idealCurveEntries.remove(0);
            // X座標を再調整
            for (int i = 0; i < idealCurveEntries.size(); i++) {
                idealCurveEntries.get(i).setX(i);
            }
        }
        
        // 現在時刻を取得（実測波形と同期）
        long currentTime = System.currentTimeMillis();
        double elapsedTime = currentTime - chartStartTime;  // ms
        
        // 30fps想定で時刻を計算
        double frameInterval = 1000.0 / 30.0;  // ms per frame
        
        // 最新のフレームに対応する理想曲線の値を追加
        long now = System.currentTimeMillis();
        
        // 理想曲線の値を取得（振幅とmeanは自動的に反映される）
        double idealValue = sinBP.getIdealCurveValue(now);
        
        // 新しいエントリーを追加（実測波形と同じX座標）
        idealCurveEntries.add(new Entry(idealCurveEntries.size(), (float) idealValue));
    }

    // ===== リセット ／ 記録制御 =====
    public void reset() {
        // 各 LogicProcessor 実装の reset() を呼び出す
        for (LogicProcessor lp : logicMap.values()) {
            if (lp instanceof Logic1)    ((Logic1)lp).reset();
            else if (lp instanceof Logic2)((Logic2)lp).reset();
        }

        // RealtimeBPの血圧値をリセット
        if (bpEstimator != null) {
            bpEstimator.reset();
        }

        // 記録データリストをクリア
        clearRecordedData(); // ★ 既存のクリアメソッドを呼び出す

        // UI を初期状態に更新
        updateUi(0, 0, 0, 0, 0, 0);
        isRecordingActive = false; // ★ リセット時に記録フラグもオフにする
    }

    // ★ 修正: startRecording メソッド
    public void startRecording() {
        clearRecordedData(); // 新しい記録を開始する前に、以前のデータをクリア
        isRecordingActive = true;
    }

    public void stopRecording()  {
        isRecordingActive = false;
    }

    public void clearRecordedData() {
        recValue.clear();
        recIbi.clear();
        recSd.clear();
        recSmIbi.clear();
        recSmBpm.clear();
        recValTs.clear();
        recIbiTs.clear();
    }

    // ===== CSV保存 =====
    public void saveIbiToCsv(String name) {

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_IBI_data.csv");

        // recIbi が空の場合はトースト表示して終了
        if (recIbi.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録された心拍間隔データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            // ヘッダー行（SinBP値を追加）
            writer.append("IBI, bpmSD, Smoothed IBI, Smoothed BPM, SBP, DBP, SBP_Avg, DBP_Avg, SinSBP, SinDBP, SinSBP_Avg, SinDBP_Avg, Timestamp\n");

            // 記録データを CSV に書き出し
            double prevIbi = Double.NaN;
            for (int i = 0; i < recIbi.size(); i++) {
                double ibi = recIbi.get(i);
                // IBI が更新されていない場合は行を追加しない (このロジックは維持)
                if (!Double.isNaN(prevIbi) && Double.compare(ibi, prevIbi) == 0) {
                    // ただし、タイムスタンプが異なる場合は別のイベントとして記録するべきかもしれない。
                    // 現状はIBI値のみで判断している。
                    // もし、同じIBIでも連続して記録したい場合はこの continue を削除または条件変更。
                    continue;
                }
                prevIbi = ibi;

                String ts = sdf.format(new Date(recIbiTs.get(i))); // recIbiTs を使う
                writer
                        .append(String.format(Locale.getDefault(), "%.2f", recIbi.get(i))).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", recSd.get(i))).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", recSmIbi.get(i))).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", recSmBpm.get(i))).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", bpEstimator.getLastSbp())).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", bpEstimator.getLastDbp())).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", bpEstimator.getLastSbpAvg())).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", bpEstimator.getLastDbpAvg())).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBP != null ? sinBP.getLastSinSBP() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBP != null ? sinBP.getLastSinDBP() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBP != null ? sinBP.getLastSinSBPAvg() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBP != null ? sinBP.getLastSinDBPAvg() : 0.0)).append(", ")
                        .append(ts)
                        .append("\n");
            }

            ui.post(() ->
                    Toast.makeText(ctx, "心拍間隔データ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "心拍間隔データ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
    }

    public void saveGreenValuesToCsv(String name) {
        // こちらも recValue が空の場合の処理を追加した方が親切
        if (recValue.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録された画像信号データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }
        
        // Sin波データも含めたCSVを保存
        saveGreenValuesWithSinWaveToCsv(name);
    }
    
    private void saveGreenValuesWithSinWaveToCsv(String name) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_Green_with_SinWave.csv");

        try (FileWriter writer = new FileWriter(csvFile)) {
            // ヘッダー行
            writer.append("Green, SinWave, SinAmplitude, SinMean, SinIBI, Timestamp\n");

            // 記録データを CSV に書き出し
            for (int i = 0; i < recValue.size(); i++) {
                String ts = sdf.format(new Date(recValTs.get(i)));
                writer
                        .append(String.format(Locale.getDefault(), "%.2f", recValue.get(i))).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", 
                                i < recSinWave.size() ? recSinWave.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", 
                                i < recSinAmplitude.size() ? recSinAmplitude.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", 
                                i < recSinMean.size() ? recSinMean.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", 
                                i < recSinIBI.size() ? recSinIBI.get(i) : 0.0)).append(", ")
                        .append(ts)
                        .append("\n");
            }

            ui.post(() ->
                    Toast.makeText(ctx, "画像信号データ（Sin波含む）保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "画像信号データ（Sin波含む）保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void saveCsv(
            String n,
            List<String> head,
            List<Double> vals,
            List<Long> ts) {

        File dir = Environment
                .getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
        File f = new File(dir, n + ".csv");

        try (FileWriter w = new FileWriter(f)) {
            w.append(String.join(",", head)).append("\n");
            SimpleDateFormat sdf =
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
            for (int i = 0; i < vals.size(); i++)
                w.append(String.format("%.2f", vals.get(i)))
                        .append(",")
                        .append(sdf.format(new Date(ts.get(i))))
                        .append("\n");
            ui.post(() -> Toast.makeText(ctx, "画像信号データ 保存完了",
                    Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            ui.post(() -> Toast.makeText(ctx, "画像信号データ 保存失敗",
                    Toast.LENGTH_SHORT).show());
        }
    }

    public double getLatestSmoothedBpm() {
        if (recSmBpm.isEmpty()) {
            return 0.0;
        }
        return recSmBpm.get(recSmBpm.size() - 1);
    }

    // ===== カメラ操作 =====
    public void stop()            { camOpen = false; }
    public void restartCamera()   { stop(); startCamera(); }

    public LogicProcessor getLogicProcessor(String key) {
        return logicMap.get(key);
    }

    /**
     * ISO値を更新し、検出の有効/無効を制御
     */
    public void updateISO(int iso) {
        this.currentISO = iso;
        boolean shouldEnable = iso >= 300;
        
        if (isDetectionEnabled != shouldEnable) {
            isDetectionEnabled = shouldEnable;
            if (shouldEnable) {
                Log.d("GreenValueAnalyzer-ISO", "Detection enabled: ISO=" + iso);
            } else {
                Log.d("GreenValueAnalyzer-ISO", "Detection disabled: ISO=" + iso);
            }
        }
    }

    /**
     * 検出が有効かチェック
     */
    private boolean isDetectionValid() {
        return isDetectionEnabled && currentISO >= 300;
    }
}