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
import java.util.Locale;
import android.graphics.Color;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.TotalCaptureResult;
import org.json.JSONObject;

public class GreenValueAnalyzer implements LifecycleObserver {
    private static final String REALTIME_LOG_TAG = "RealtimeSession";
    private static final int DEFAULT_FPS = 30;

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
    
    // 拍ごとのデータ範囲を追跡（rPPGとSIN近似を1対1で対応させるため）
    private long lastIdealEndTime = 0;  // 前回の理想曲線の終了時刻（新しい拍の検出用）
    private float lastIdealValue = Float.NaN;  // 直近の理想曲線値（NaN維持防止用）
    private final List<Float> lastIdealWaveform = new ArrayList<>();  // 直近の理想曲線波形
    private int lastIdealWaveformCursor = 0;
    private boolean idealCurveNeedsRefresh = false;  // 理想曲線のデータセットを再設定する必要があるか
    private static final double DEFAULT_SIN_PHASE_OFFSET = 0.16;
    
    // Sin波の位相オフセット（遅延補正用、-1.0～1.0の範囲、正の値で前方シフト）
    private double sinPhaseOffset = DEFAULT_SIN_PHASE_OFFSET;  // 表示用の位相を少しだけ前へ寄せる

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

    // ===== 学習用データ記録 =====
    private final List<Long> recTrainingTs = new ArrayList<>();  // タイムスタンプ
    // Method1 (RealtimeBP) 特徴量
    private final List<Double> recM1_A = new ArrayList<>();
    private final List<Double> recM1_HR = new ArrayList<>();
    private final List<Double> recM1_V2P_relTTP = new ArrayList<>();
    private final List<Double> recM1_P2V_relTTP = new ArrayList<>();
    private final List<Double> recM1_SBP = new ArrayList<>();
    private final List<Double> recM1_DBP = new ArrayList<>();
    private final List<Double> recM1_IbiInput = new ArrayList<>();
    private final List<Double> recM1_SmoothedIbi = new ArrayList<>();
    private final List<Integer> recM1_UsedSmoothedIbi = new ArrayList<>();
    private final List<Double> recM1_A_Used = new ArrayList<>();
    private final List<Double> recM1_HR_Used = new ArrayList<>();
    private final List<Double> recM1_V2P_relTTP_Used = new ArrayList<>();
    private final List<Double> recM1_P2V_relTTP_Used = new ArrayList<>();
    private final List<Double> recM1_SBP_Raw = new ArrayList<>();
    private final List<Double> recM1_DBP_Raw = new ArrayList<>();
    private final List<Integer> recM1_ClampApplied = new ArrayList<>();
    private final List<Integer> recM1_FeatureClampApplied = new ArrayList<>();
    private final List<Integer> recM1_OutputValid = new ArrayList<>();
    private final List<String> recM1_FeatureClampReason = new ArrayList<>();
    private final List<String> recM1_RejectReason = new ArrayList<>();
    // Method2 (SinBP) 特徴量
    private final List<Double> recM2_A = new ArrayList<>();
    private final List<Double> recM2_HR = new ArrayList<>();
    private final List<Double> recM2_V2P_relTTP = new ArrayList<>();
    private final List<Double> recM2_P2V_relTTP = new ArrayList<>();
    private final List<Double> recM2_E = new ArrayList<>();
    private final List<Double> recM2_Stiffness = new ArrayList<>();
    private final List<Double> recM2_SBP = new ArrayList<>();
    private final List<Double> recM2_DBP = new ArrayList<>();
    private final List<Double> recM2_Mean = new ArrayList<>();
    private final List<Double> recM2_Phi = new ArrayList<>();
    private final List<Double> recM2_SinPhi = new ArrayList<>();
    private final List<Double> recM2_CosPhi = new ArrayList<>();
    private final List<Double> recM2_FitA = new ArrayList<>();
    private final List<Double> recM2_FitB = new ArrayList<>();
    private final List<Double> recM2_IbiCurrent = new ArrayList<>();
    private final List<Double> recM2_SmoothedIbi = new ArrayList<>();
    private final List<Integer> recM2_UsedSmoothedIbi = new ArrayList<>();
    private final List<Double> recM2_A_Used = new ArrayList<>();
    private final List<Double> recM2_HR_Used = new ArrayList<>();
    private final List<Double> recM2_V2P_relTTP_Used = new ArrayList<>();
    private final List<Double> recM2_P2V_relTTP_Used = new ArrayList<>();
    private final List<Double> recM2_E_Used = new ArrayList<>();
    private final List<Double> recM2_Stiffness_Used = new ArrayList<>();
    private final List<Integer> recM2_BeatSampleCount = new ArrayList<>();
    private final List<Double> recM2_BeatMin = new ArrayList<>();
    private final List<Double> recM2_BeatMax = new ArrayList<>();
    private final List<Double> recM2_BeatRange = new ArrayList<>();
    private final List<Double> recM2_BeatStd = new ArrayList<>();
    private final List<Double> recM2_SystoleRatio = new ArrayList<>();
    private final List<Double> recM2_DiastoleRatio = new ArrayList<>();
    private final List<Double> recM2_SBP_Raw = new ArrayList<>();
    private final List<Double> recM2_DBP_Raw = new ArrayList<>();
    private final List<Double> recM2_SBP_Base = new ArrayList<>();
    private final List<Double> recM2_DBP_Base = new ArrayList<>();
    private final List<Double> recM2_SBP_Correction = new ArrayList<>();
    private final List<Double> recM2_DBP_Correction = new ArrayList<>();
    private final List<Double> recM2_SBP_AttemptFinal = new ArrayList<>();
    private final List<Double> recM2_DBP_AttemptFinal = new ArrayList<>();
    private final List<Integer> recM2_ConstraintApplied = new ArrayList<>();
    private final List<Integer> recM2_ClampApplied = new ArrayList<>();
    private final List<Integer> recM2_FeatureClampApplied = new ArrayList<>();
    private final List<Integer> recM2_OutputValid = new ArrayList<>();
    private final List<String> recM2_FeatureClampReason = new ArrayList<>();
    private final List<String> recM2_RejectReason = new ArrayList<>();
    // Method3 (SinBP_M) 特徴量
    private final List<Double> recM3_A = new ArrayList<>();
    private final List<Double> recM3_HR = new ArrayList<>();
    private final List<Double> recM3_Mean = new ArrayList<>();
    private final List<Double> recM3_Phi = new ArrayList<>();
    private final List<Double> recM3_SBP = new ArrayList<>();
    private final List<Double> recM3_DBP = new ArrayList<>();
    private final List<Double> recM3_SinPhi = new ArrayList<>();
    private final List<Double> recM3_CosPhi = new ArrayList<>();
    private final List<Double> recM3_FitA = new ArrayList<>();
    private final List<Double> recM3_FitB = new ArrayList<>();
    private final List<Double> recM3_FitRMSE = new ArrayList<>();
    private final List<Double> recM3_IbiCurrent = new ArrayList<>();
    private final List<Double> recM3_SmoothedIbi = new ArrayList<>();
    private final List<Integer> recM3_UsedSmoothedIbi = new ArrayList<>();
    private final List<Double> recM3_A_Used = new ArrayList<>();
    private final List<Double> recM3_HR_Used = new ArrayList<>();
    private final List<Double> recM3_Mean_Used = new ArrayList<>();
    private final List<Double> recM3_SinPhi_Used = new ArrayList<>();
    private final List<Double> recM3_CosPhi_Used = new ArrayList<>();
    private final List<Integer> recM3_BeatSampleCount = new ArrayList<>();
    private final List<Double> recM3_BeatMin = new ArrayList<>();
    private final List<Double> recM3_BeatMax = new ArrayList<>();
    private final List<Double> recM3_BeatRange = new ArrayList<>();
    private final List<Double> recM3_BeatStd = new ArrayList<>();
    private final List<Double> recM3_SystoleRatio = new ArrayList<>();
    private final List<Double> recM3_DiastoleRatio = new ArrayList<>();
    private final List<Double> recM3_SBP_Raw = new ArrayList<>();
    private final List<Double> recM3_DBP_Raw = new ArrayList<>();
    private final List<Double> recM3_SBP_AttemptFinal = new ArrayList<>();
    private final List<Double> recM3_DBP_AttemptFinal = new ArrayList<>();
    private final List<Integer> recM3_ConstraintApplied = new ArrayList<>();
    private final List<Integer> recM3_ClampApplied = new ArrayList<>();
    private final List<Integer> recM3_FeatureClampApplied = new ArrayList<>();
    private final List<Integer> recM3_OutputValid = new ArrayList<>();
    private final List<String> recM3_FeatureClampReason = new ArrayList<>();
    private final List<String> recM3_RejectReason = new ArrayList<>();
    private final List<Integer> recBeatIndex = new ArrayList<>();
    private final List<Integer> recModeIndex = new ArrayList<>();
    private final List<Integer> recIso = new ArrayList<>();
    private final List<Long> recExposureTime = new ArrayList<>();
    private final List<Integer> recWhiteBalanceMode = new ArrayList<>();
    private final List<Double> recFocusDistance = new ArrayList<>();
    private final List<Double> recFNumber = new ArrayList<>();
    private final List<Double> recAperture = new ArrayList<>();
    private final List<Double> recSensorSensitivity = new ArrayList<>();
    private final List<Double> recColorTemperature = new ArrayList<>();
    private final List<Integer> recFps = new ArrayList<>();
    private final List<Integer> recIsValidBeat = new ArrayList<>();
    private final List<Integer> recArtifactFlag = new ArrayList<>();

    // ===== 状態 =====
    private boolean camOpen;
    private double  IBI;
    private boolean isRecordingActive = false;
    // autosave は描画系で同期実行すると波形を止めるため、
    // 頻度をさらに下げ、バックグラウンドへ逃がす。
    private static final int TRAINING_AUTOSAVE_EVERY_BEATS = 60;
    private long recordingStartTime = 0; // 記録開始時点（ミリ秒）
    private int beatCounter = 0;
    private String sessionId = "";
    private String subjectId = "";
    private int sessionNumber = 0;
    private int activeMode = -1;
    private String appVersion = "";
    private String coefficientVersion = "";
    private String outputBaseName = "";

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
    private float currentColorTemperature = 0.0f;
    private float currentAperture = 0.0f;
    private float currentSensorSensitivity = 0.0f;
    
    // カメラ関連
    private ProcessCameraProvider cameraProvider;
    private CameraSelector cameraSelector;

    // ===== ロジック =====
    private final Map<String, LogicProcessor> logicMap = new HashMap<>();

    // ===== ハンドラ =====
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService autosaveExecutor = Executors.newSingleThreadExecutor();
    private final Object trainingCsvSaveLock = new Object();
    private final Object autosaveStateLock = new Object();
    private boolean trainingAutosaveInFlight = false;

    // 外部から注入されるBP推定器
    private RealtimeBP bpEstimator;
    private SinBPDistortion sinBPDistortion;  // SinBP(D)推定器
    private SinBPModel sinBPModel;  // SinBP(M)推定器

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
    
    // SinBP(D)をセット
    public void setSinBPDistortion(SinBPDistortion estimator) {
        this.sinBPDistortion = estimator;
        // Logic1とLogic2への参照を設定
        if (estimator != null) {
            Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
            Logic2 l2 = (Logic2) logicMap.computeIfAbsent("Logic2", k -> new Logic2());
            estimator.setLogicRef(l1);  // デフォルトでLogic1を設定
        }
    }
    
    // SinBP(M)をセット
    public void setSinBPModel(SinBPModel estimator) {
        this.sinBPModel = estimator;
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

    public void configureSession(
            String sessionId,
            String subjectId,
            int sessionNumber,
            int mode,
            String appVersion,
            String coefficientVersion,
            String outputBaseName) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.subjectId = subjectId != null ? subjectId : "";
        this.sessionNumber = sessionNumber;
        this.activeMode = mode;
        this.appVersion = appVersion != null ? appVersion : "";
        this.coefficientVersion = coefficientVersion != null ? coefficientVersion : "";
        this.outputBaseName = outputBaseName != null ? outputBaseName : "";
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
        idealCurveDataSet.setMode(LineDataSet.Mode.LINEAR);  // 線形補間モード
        idealCurveDataSet.setDrawFilled(false);  // 塗りつぶしを無効化
        // 実線表示（破線を無効化）

        data = new LineData(dataSet, idealCurveDataSet);
        chart.setData(data);
        chart.getDescription().setEnabled(false);
        chart.getLegend().setTextColor(Color.WHITE);  // 凡例の文字色を白に

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
        
        // アクティブなロジックに応じてRealtimeBPとSinBPDistortionの参照を更新
        if (logicProcessor instanceof BaseLogic) {
            BaseLogic logic = (BaseLogic) logicProcessor;
            if (bpEstimator != null) {
                bpEstimator.setLogicRef(logic);
            }
            if (sinBPDistortion != null) {
                sinBPDistortion.setLogicRef(logic);
            }
        }
    }

    public double getLatestIbi() { return IBI; }
    
    public double getCurrentIBI() { return IBI; }
    
    /**
     * Sin波の位相オフセットを設定（遅延補正用）
     * @param offset 位相オフセット（0.0～1.0の範囲、正の値で前方シフト、負の値で後方シフト）
     *                例: 0.1 = 周期の10%前方シフト（遅延を補正）
     */
    public void setSinPhaseOffset(double offset) {
        // -1.0～1.0の範囲に制限
        this.sinPhaseOffset = Math.max(-1.0, Math.min(1.0, offset));
        Log.d("GreenValueAnalyzer", String.format(Locale.getDefault(),
                "Sin phase offset set to: %.4f (%.2f%% of cycle)", 
                sinPhaseOffset, sinPhaseOffset * 100.0));
    }
    
    /**
     * Sin波の位相オフセットを取得
     */
    public double getSinPhaseOffset() {
        return sinPhaseOffset;
    }

    // ===== カメラ起動 =====
    public void startCamera() {
        // Logic1のコールバック設定
        Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
        if (bpEstimator != null) {
            l1.setBPFrameCallback(bpEstimator::update);
        }
        if (sinBPDistortion != null) {
            l1.setSinBPCallback(sinBPDistortion::update);
        }
        if (sinBPModel != null) {
            l1.setSinBPModelCallback(sinBPModel::update);
        }
        
        // Logic2のコールバック設定
        Logic2 l2 = (Logic2) logicMap.computeIfAbsent("Logic2", k -> new Logic2());
        if (bpEstimator != null) {
            l2.setBPFrameCallback(bpEstimator::update);
        }
        if (sinBPDistortion != null) {
            l2.setSinBPCallback(sinBPDistortion::update);
        }
        if (sinBPModel != null) {
            l2.setSinBPModelCallback(sinBPModel::update);
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
                currentFNumber = fNumberValue;
                currentISO = isoValue;
                currentExposureTime = exposureTimeValue;
                currentWhiteBalanceMode = wbModeValue;
                currentFocusDistance = focusValue;
                currentAperture = fNumberValue;
                currentSensorSensitivity = isoValue;
                currentColorTemperature = 0.0f;

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
                    
                    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
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
                        long currentTimestamp = System.currentTimeMillis();
                        recIbiTs.add(currentTimestamp);
                        
                        // 学習用データの記録（IBIが更新されたタイミングで記録）
                        // IBIが前回と異なる場合（新しい拍が検出された場合）のみ記録
                        boolean isNewBeat = recIbi.size() == 1 || 
                            (recIbi.size() > 1 && Double.compare(recIbi.get(recIbi.size() - 1), recIbi.get(recIbi.size() - 2)) != 0);
                        
                        if (isNewBeat) {
                            recTrainingTs.add(currentTimestamp);
                            beatCounter += 1;
                            recBeatIndex.add(beatCounter);
                            recModeIndex.add(activeMode);
                            recIso.add(currentISO);
                            recExposureTime.add(currentExposureTime);
                            recWhiteBalanceMode.add(currentWhiteBalanceMode);
                            recFocusDistance.add((double) currentFocusDistance);
                            recFNumber.add((double) currentFNumber);
                            recAperture.add((double) currentAperture);
                            recSensorSensitivity.add((double) currentSensorSensitivity);
                            recColorTemperature.add((double) currentColorTemperature);
                            recFps.add(DEFAULT_FPS);
                            recIsValidBeat.add(isDetectionValid() ? 1 : 0);
                            recArtifactFlag.add(0);
                            
                            // Method1 (RealtimeBP) 特徴量
                            if (bpEstimator != null) {
                                recM1_A.add(bpEstimator.getLastAmplitude());
                                recM1_HR.add(bpEstimator.getLastValidHr());
                                recM1_V2P_relTTP.add(bpEstimator.getLastValleyToPeakRelTTP());
                                recM1_P2V_relTTP.add(bpEstimator.getLastPeakToValleyRelTTP());
                                recM1_SBP.add(bpEstimator.getLastSbp());
                                recM1_DBP.add(bpEstimator.getLastDbp());
                                recM1_IbiInput.add(bpEstimator.getLastInputIbiMs());
                                recM1_SmoothedIbi.add(bpEstimator.getLastSmoothedIbiMs());
                                recM1_UsedSmoothedIbi.add(bpEstimator.getLastUsedSmoothedIbi());
                                recM1_A_Used.add(bpEstimator.getLastUsedAmplitude());
                                recM1_HR_Used.add(bpEstimator.getLastUsedHr());
                                recM1_V2P_relTTP_Used.add(bpEstimator.getLastUsedValleyToPeakRelTTP());
                                recM1_P2V_relTTP_Used.add(bpEstimator.getLastUsedPeakToValleyRelTTP());
                                recM1_SBP_Raw.add(bpEstimator.getLastRawSbp());
                                recM1_DBP_Raw.add(bpEstimator.getLastRawDbp());
                                recM1_ClampApplied.add(bpEstimator.getLastClampApplied());
                                recM1_FeatureClampApplied.add(bpEstimator.getLastFeatureClampApplied());
                                recM1_OutputValid.add(bpEstimator.getLastOutputValid());
                                recM1_FeatureClampReason.add(bpEstimator.getLastFeatureClampReason());
                                recM1_RejectReason.add(bpEstimator.getLastRejectReason());
                            } else {
                                recM1_A.add(0.0);
                                recM1_HR.add(0.0);
                                recM1_V2P_relTTP.add(0.0);
                                recM1_P2V_relTTP.add(0.0);
                                recM1_SBP.add(0.0);
                                recM1_DBP.add(0.0);
                                recM1_IbiInput.add(0.0);
                                recM1_SmoothedIbi.add(0.0);
                                recM1_UsedSmoothedIbi.add(0);
                                recM1_A_Used.add(0.0);
                                recM1_HR_Used.add(0.0);
                                recM1_V2P_relTTP_Used.add(0.0);
                                recM1_P2V_relTTP_Used.add(0.0);
                                recM1_SBP_Raw.add(0.0);
                                recM1_DBP_Raw.add(0.0);
                                recM1_ClampApplied.add(0);
                                recM1_FeatureClampApplied.add(0);
                                recM1_OutputValid.add(0);
                                recM1_FeatureClampReason.add("estimator_missing");
                                recM1_RejectReason.add("estimator_missing");
                            }
                            
                            // Method2 (SinBP_D) 特徴量
                            if (sinBPDistortion != null) {
                                recM2_A.add(sinBPDistortion.getCurrentRegressionAmplitude());
                                recM2_HR.add(sinBPDistortion.getCurrentHR());
                                recM2_V2P_relTTP.add(sinBPDistortion.getCurrentValleyToPeakRelTTP());
                                recM2_P2V_relTTP.add(sinBPDistortion.getCurrentPeakToValleyRelTTP());
                                recM2_E.add(sinBPDistortion.getCurrentDistortion());
                                recM2_Stiffness.add(sinBPDistortion.getCurrentStiffnessSin());
                                recM2_SBP.add(sinBPDistortion.getLastSinSBP());
                                recM2_DBP.add(sinBPDistortion.getLastSinDBP());
                                recM2_Mean.add(sinBPDistortion.getCurrentMean());
                                recM2_Phi.add(sinBPDistortion.getCurrentPhi());
                                recM2_SinPhi.add(sinBPDistortion.getCurrentSinPhi());
                                recM2_CosPhi.add(sinBPDistortion.getCurrentCosPhi());
                                recM2_FitA.add(sinBPDistortion.getCurrentFitAComponent());
                                recM2_FitB.add(sinBPDistortion.getCurrentFitBComponent());
                                recM2_IbiCurrent.add(sinBPDistortion.getCurrentIBI());
                                recM2_SmoothedIbi.add(sinBPDistortion.getCurrentSmoothedIbiMs());
                                recM2_UsedSmoothedIbi.add(sinBPDistortion.getCurrentUsedSmoothedIbi());
                                recM2_A_Used.add(sinBPDistortion.getCurrentUsedRegressionAmplitude());
                                recM2_HR_Used.add(sinBPDistortion.getCurrentHR());
                                recM2_V2P_relTTP_Used.add(sinBPDistortion.getCurrentUsedValleyToPeakRelTTP());
                                recM2_P2V_relTTP_Used.add(sinBPDistortion.getCurrentUsedPeakToValleyRelTTP());
                                recM2_E_Used.add(sinBPDistortion.getCurrentUsedDistortion());
                                recM2_Stiffness_Used.add(sinBPDistortion.getCurrentUsedStiffnessSin());
                                recM2_BeatSampleCount.add(sinBPDistortion.getCurrentBeatWindowSampleCount());
                                recM2_BeatMin.add(sinBPDistortion.getCurrentBeatMin());
                                recM2_BeatMax.add(sinBPDistortion.getCurrentBeatMax());
                                recM2_BeatRange.add(sinBPDistortion.getCurrentBeatRange());
                                recM2_BeatStd.add(sinBPDistortion.getCurrentBeatStd());
                                recM2_SystoleRatio.add(sinBPDistortion.getCurrentSystoleRatio());
                                recM2_DiastoleRatio.add(sinBPDistortion.getCurrentDiastoleRatio());
                                recM2_SBP_Raw.add(sinBPDistortion.getCurrentRawSbp());
                                recM2_DBP_Raw.add(sinBPDistortion.getCurrentRawDbp());
                                recM2_SBP_Base.add(sinBPDistortion.getCurrentBaseSbp());
                                recM2_DBP_Base.add(sinBPDistortion.getCurrentBaseDbp());
                                recM2_SBP_Correction.add(sinBPDistortion.getCurrentSbpCorrection());
                                recM2_DBP_Correction.add(sinBPDistortion.getCurrentDbpCorrection());
                                recM2_SBP_AttemptFinal.add(sinBPDistortion.getCurrentConstrainedSbp());
                                recM2_DBP_AttemptFinal.add(sinBPDistortion.getCurrentConstrainedDbp());
                                recM2_ConstraintApplied.add(sinBPDistortion.getCurrentConstraintApplied());
                                recM2_ClampApplied.add(sinBPDistortion.getCurrentClampApplied());
                                recM2_FeatureClampApplied.add(sinBPDistortion.getCurrentFeatureClampApplied());
                                recM2_OutputValid.add(sinBPDistortion.getCurrentOutputValid());
                                recM2_FeatureClampReason.add(sinBPDistortion.getCurrentFeatureClampReason());
                                recM2_RejectReason.add(sinBPDistortion.getCurrentRejectReason());
                            } else {
                                recM2_A.add(0.0);
                                recM2_HR.add(0.0);
                                recM2_V2P_relTTP.add(0.0);
                                recM2_P2V_relTTP.add(0.0);
                                recM2_E.add(0.0);
                                recM2_Stiffness.add(0.0);
                                recM2_SBP.add(0.0);
                                recM2_DBP.add(0.0);
                                recM2_Mean.add(0.0);
                                recM2_Phi.add(0.0);
                                recM2_SinPhi.add(0.0);
                                recM2_CosPhi.add(1.0);
                                recM2_FitA.add(0.0);
                                recM2_FitB.add(0.0);
                                recM2_IbiCurrent.add(0.0);
                                recM2_SmoothedIbi.add(0.0);
                                recM2_UsedSmoothedIbi.add(0);
                                recM2_A_Used.add(0.0);
                                recM2_HR_Used.add(0.0);
                                recM2_V2P_relTTP_Used.add(0.0);
                                recM2_P2V_relTTP_Used.add(0.0);
                                recM2_E_Used.add(0.0);
                                recM2_Stiffness_Used.add(0.0);
                                recM2_BeatSampleCount.add(0);
                                recM2_BeatMin.add(0.0);
                                recM2_BeatMax.add(0.0);
                                recM2_BeatRange.add(0.0);
                                recM2_BeatStd.add(0.0);
                                recM2_SystoleRatio.add(0.0);
                                recM2_DiastoleRatio.add(0.0);
                                recM2_SBP_Raw.add(0.0);
                                recM2_DBP_Raw.add(0.0);
                                recM2_SBP_Base.add(0.0);
                                recM2_DBP_Base.add(0.0);
                                recM2_SBP_Correction.add(0.0);
                                recM2_DBP_Correction.add(0.0);
                                recM2_SBP_AttemptFinal.add(0.0);
                                recM2_DBP_AttemptFinal.add(0.0);
                                recM2_ConstraintApplied.add(0);
                                recM2_ClampApplied.add(0);
                                recM2_FeatureClampApplied.add(0);
                                recM2_OutputValid.add(0);
                                recM2_FeatureClampReason.add("estimator_missing");
                                recM2_RejectReason.add("estimator_missing");
                            }
                            
                            // Method3 (SinBP_M) 特徴量
                            if (sinBPModel != null) {
                                recM3_A.add(sinBPModel.getCurrentAmplitude());
                                recM3_HR.add(sinBPModel.getCurrentHR());
                                recM3_Mean.add(sinBPModel.getCurrentMean());
                                recM3_Phi.add(sinBPModel.getCurrentPhase());
                                recM3_SBP.add(sinBPModel.getLastSinSBP());
                                recM3_DBP.add(sinBPModel.getLastSinDBP());
                                recM3_SinPhi.add(sinBPModel.getCurrentSinPhi());
                                recM3_CosPhi.add(sinBPModel.getCurrentCosPhi());
                                recM3_FitA.add(sinBPModel.getCurrentFitAComponent());
                                recM3_FitB.add(sinBPModel.getCurrentFitBComponent());
                                recM3_FitRMSE.add(sinBPModel.getCurrentFitRMSE());
                                recM3_IbiCurrent.add(sinBPModel.getCurrentIBI());
                                recM3_SmoothedIbi.add(sinBPModel.getCurrentSmoothedIbiMs());
                                recM3_UsedSmoothedIbi.add(sinBPModel.getCurrentUsedSmoothedIbi());
                                recM3_A_Used.add(sinBPModel.getCurrentUsedAmplitude());
                                recM3_HR_Used.add(sinBPModel.getCurrentHR());
                                recM3_Mean_Used.add(sinBPModel.getCurrentUsedMean());
                                recM3_SinPhi_Used.add(sinBPModel.getCurrentUsedSinPhi());
                                recM3_CosPhi_Used.add(sinBPModel.getCurrentUsedCosPhi());
                                recM3_BeatSampleCount.add(sinBPModel.getCurrentBeatSampleCount());
                                recM3_BeatMin.add(sinBPModel.getCurrentBeatMin());
                                recM3_BeatMax.add(sinBPModel.getCurrentBeatMax());
                                recM3_BeatRange.add(sinBPModel.getCurrentBeatRange());
                                recM3_BeatStd.add(sinBPModel.getCurrentBeatStd());
                                recM3_SystoleRatio.add(sinBPModel.getCurrentSystoleRatio());
                                recM3_DiastoleRatio.add(sinBPModel.getCurrentDiastoleRatio());
                                recM3_SBP_Raw.add(sinBPModel.getCurrentRawSbp());
                                recM3_DBP_Raw.add(sinBPModel.getCurrentRawDbp());
                                recM3_SBP_AttemptFinal.add(sinBPModel.getCurrentConstrainedSbp());
                                recM3_DBP_AttemptFinal.add(sinBPModel.getCurrentConstrainedDbp());
                                recM3_ConstraintApplied.add(sinBPModel.getCurrentConstraintApplied());
                                recM3_ClampApplied.add(sinBPModel.getCurrentClampApplied());
                                recM3_FeatureClampApplied.add(sinBPModel.getCurrentFeatureClampApplied());
                                recM3_OutputValid.add(sinBPModel.getCurrentOutputValid());
                                recM3_FeatureClampReason.add(sinBPModel.getCurrentFeatureClampReason());
                                recM3_RejectReason.add(sinBPModel.getCurrentRejectReason());
                            } else {
                                recM3_A.add(0.0);
                                recM3_HR.add(0.0);
                                recM3_Mean.add(0.0);
                                recM3_Phi.add(0.0);
                                recM3_SBP.add(0.0);
                                recM3_DBP.add(0.0);
                                recM3_SinPhi.add(0.0);
                                recM3_CosPhi.add(1.0);
                                recM3_FitA.add(0.0);
                                recM3_FitB.add(0.0);
                                recM3_FitRMSE.add(0.0);
                                recM3_IbiCurrent.add(0.0);
                                recM3_SmoothedIbi.add(0.0);
                                recM3_UsedSmoothedIbi.add(0);
                                recM3_A_Used.add(0.0);
                                recM3_HR_Used.add(0.0);
                                recM3_Mean_Used.add(0.0);
                                recM3_SinPhi_Used.add(0.0);
                                recM3_CosPhi_Used.add(1.0);
                                recM3_BeatSampleCount.add(0);
                                recM3_BeatMin.add(0.0);
                                recM3_BeatMax.add(0.0);
                                recM3_BeatRange.add(0.0);
                                recM3_BeatStd.add(0.0);
                                recM3_SystoleRatio.add(0.0);
                                recM3_DiastoleRatio.add(0.0);
                                recM3_SBP_Raw.add(0.0);
                                recM3_DBP_Raw.add(0.0);
                                recM3_SBP_AttemptFinal.add(0.0);
                                recM3_DBP_AttemptFinal.add(0.0);
                                recM3_ConstraintApplied.add(0);
                                recM3_ClampApplied.add(0);
                                recM3_FeatureClampApplied.add(0);
                                recM3_OutputValid.add(0);
                                recM3_FeatureClampReason.add("estimator_missing");
                                recM3_RejectReason.add("estimator_missing");
                            }

                            emitRealtimeBeatLog(currentTimestamp, beatCounter);
                            autosaveTrainingDataSnapshotIfNeeded();
                        }

                        lp.calculateSmoothedValueRealTime(
                                r.getIbi(), r.getBpmSd());

                        double smI = lp.getLastSmoothedIbi();
                        double smB = (60_000) / smI;

                        recSmIbi.add(smI);
                        recSmBpm.add(smB);
                        
                        // Sin波データの記録
                        long currentTime = System.currentTimeMillis();
                        double sinWaveValue = 0.0;
                        if (sinBPDistortion != null && sinBPDistortion.hasIdealCurve()) {
                            // 理想曲線パラメータを使って相対位置から値を計算
                            long idealStartTime = sinBPDistortion.getIdealCurveStartTime();
                            long idealEndTime = sinBPDistortion.getIdealCurveEndTime();
                            
                            if (idealStartTime > 0 && idealEndTime > 0 && idealEndTime > idealStartTime) {
                                long duration = idealEndTime - idealStartTime;
                                
                                // 現在時刻を理想曲線の周期内に折り返し（繰り返し波形として扱う）
                                long relativeTime = currentTime - idealStartTime;
                                long wrappedTime = relativeTime % duration;
                                if (wrappedTime < 0) {
                                    wrappedTime += duration;
                                }
                                
                                // 相対位置を計算（0.0～1.0）
                                double relativePosition = (double) wrappedTime / duration;
                                
                                // チャートと同じ位相補正を適用
                                double adjustedRelativePosition = relativePosition + sinPhaseOffset;
                                if (adjustedRelativePosition < 0.0) {
                                    adjustedRelativePosition += 1.0;
                                } else if (adjustedRelativePosition > 1.0) {
                                    adjustedRelativePosition -= 1.0;
                                }
                                
                                // 相対位置から理想曲線の値を取得
                                double valueByPosition = sinBPDistortion.getScaledIdealCurveValueByRelativePosition(adjustedRelativePosition);
                                if (!Double.isNaN(valueByPosition)) {
                                    sinWaveValue = valueByPosition;
                                }
                            }
                            recSinWave.add(sinWaveValue);
                            recSinAmplitude.add(sinBPDistortion.getCurrentAmplitude());
                            recSinMean.add(sinBPDistortion.getCurrentMean());
                            recSinIBI.add(sinBPDistortion.getCurrentIBI());
                            recSinTs.add(currentTime);
                        } else {
                            // SinBPDistortionが利用できない場合は0を記録
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
                // 理想曲線のエントリーも削除（チャートのスクロールに対応）
                // ただし、理想曲線は新しい拍が検出された時に追加されるため、ここでは削除のみ
                if (!idealCurveEntries.isEmpty()) {
                    idealCurveEntries.remove(0);
                }
                // X座標を再設定
                for (int i = 0; i < entries.size(); i++) {
                    entries.get(i).setX(i);
                    if (i < idealCurveEntries.size()) {
                        idealCurveEntries.get(i).setX(i);
                    }
                }
                // 末尾の最新理想値を更新
                lastIdealValue = Float.NaN;
                for (int i = idealCurveEntries.size() - 1; i >= 0; i--) {
                    float candidate = idealCurveEntries.get(i).getY();
                    if (!Float.isNaN(candidate)) {
                        lastIdealValue = candidate;
                        break;
                    }
                }
            }
            entries.add(new Entry(entries.size(), (float) v));
            
            // idealCurveEntriesのサイズをentriesと一致させる（新しいエントリーが追加された時）
            while (idealCurveEntries.size() < entries.size()) {
                float initialIdealValue = Float.NaN;
                if (!lastIdealWaveform.isEmpty()) {
                    initialIdealValue = lastIdealWaveform.get(lastIdealWaveformCursor);
                    lastIdealWaveformCursor = (lastIdealWaveformCursor + 1) % lastIdealWaveform.size();
                } else if (!Float.isNaN(lastIdealValue)) {
                    initialIdealValue = lastIdealValue;
                }
                idealCurveEntries.add(new Entry(idealCurveEntries.size(), initialIdealValue));
            }
            
            // 理想曲線の更新（新しい拍が検出された時に追加）
            updateIdealCurve();

            // 同時刻のrPPGとSin近似値をログ出力
            if (!entries.isEmpty() && !idealCurveEntries.isEmpty()) {
                int lastIndex = entries.size() - 1;
                if (lastIndex >= 0 && lastIndex < entries.size() && lastIndex < idealCurveEntries.size()) {
                    float rppgValue = entries.get(lastIndex).getY();
                    float sinValue = idealCurveEntries.get(lastIndex).getY();
                    if (!Float.isNaN(sinValue)) {
                        Log.d("rPPG-SinComparison", String.format(Locale.getDefault(),
                                "Index=%d: rPPG=%.2f, SinApprox=%.2f, diff=%.2f, phaseOffset=%.4f",
                                lastIndex, rppgValue, sinValue, Math.abs(rppgValue - sinValue), sinPhaseOffset));
                    } else {
                        Log.d("rPPG-SinComparison", String.format(Locale.getDefault(),
                                "Index=%d: rPPG=%.2f, SinApprox=NaN, phaseOffset=%.4f",
                                lastIndex, rppgValue, sinPhaseOffset));
                    }
                }
            }

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
            
            // 理想曲線のエントリー数を確認（デバッグ用）
            int nonNaNCount = 0;
            int firstNonNaNIndex = -1;
            int lastNonNaNIndex = -1;
            for (int i = 0; i < idealCurveEntries.size(); i++) {
                Entry entry = idealCurveEntries.get(i);
                if (!Float.isNaN(entry.getY())) {
                    nonNaNCount++;
                    if (firstNonNaNIndex == -1) {
                        firstNonNaNIndex = i;
                    }
                    lastNonNaNIndex = i;
                }
            }
            
            // デバッグログ（毎回出力）
            if (nonNaNCount > 0) {
                Log.d("IdealCurve-UI", String.format(Locale.getDefault(),
                        "UI Update: idealCurveEntries.size()=%d, nonNaNCount=%d, range=[%d-%d], needsRefresh=%b",
                        idealCurveEntries.size(), nonNaNCount, firstNonNaNIndex, lastNonNaNIndex, idealCurveNeedsRefresh));
                
                // 最初と最後の数点の値を確認
                if (firstNonNaNIndex >= 0 && lastNonNaNIndex >= 0) {
                    int sampleCount = Math.min(5, nonNaNCount);
                    for (int i = 0; i < sampleCount && firstNonNaNIndex + i <= lastNonNaNIndex; i++) {
                        Entry entry = idealCurveEntries.get(firstNonNaNIndex + i);
                        Log.d("IdealCurve-UI", String.format(Locale.getDefault(),
                                "  Entry[%d]: x=%.1f, y=%.2f", firstNonNaNIndex + i, entry.getX(), entry.getY()));
                    }
                }
            }
            
            // 理想曲線のデータセット更新（Entryのy値は既にupdateIdealCurve()で更新済み）
            // LineDataSetはコンストラクタで渡したList<Entry>を参照しているため、
            // Entryのy値を直接更新してnotifyDataSetChanged()を呼べば更新される
            if (idealCurveNeedsRefresh && nonNaNCount > 0) {
                Log.d("IdealCurve-UI", String.format(Locale.getDefault(),
                        "Ideal curve data updated: idealCurveEntries.size()=%d, nonNaNCount=%d, range=[%d-%d]",
                        idealCurveEntries.size(), nonNaNCount, firstNonNaNIndex, lastNonNaNIndex));
                idealCurveNeedsRefresh = false;
            }
            
            // データセットの状態を確認
            int datasetEntryCount = idealCurveDataSet.getEntryCount();
            int datasetNonNaNCount = 0;
            if (datasetEntryCount > 0) {
                for (int i = 0; i < datasetEntryCount; i++) {
                    Entry entry = idealCurveDataSet.getEntryForIndex(i);
                    if (entry != null && !Float.isNaN(entry.getY())) {
                        datasetNonNaNCount++;
                    }
                }
            }
            
            if (nonNaNCount > 0) {
                Log.d("IdealCurve-Dataset", String.format(Locale.getDefault(),
                        "Dataset state: idealCurveEntries.size()=%d, nonNaNCount=%d, datasetEntryCount=%d, datasetNonNaNCount=%d",
                        idealCurveEntries.size(), nonNaNCount, datasetEntryCount, datasetNonNaNCount));
                
                if (datasetEntryCount != idealCurveEntries.size()) {
                    Log.w("IdealCurve-Dataset", String.format(Locale.getDefault(),
                            "WARNING: Size mismatch! idealCurveEntries.size()=%d, datasetEntryCount=%d",
                            idealCurveEntries.size(), datasetEntryCount));
                }
                
                if (datasetNonNaNCount != nonNaNCount) {
                    Log.w("IdealCurve-Dataset", String.format(Locale.getDefault(),
                            "WARNING: Non-NaN count mismatch! idealCurveEntries nonNaN=%d, dataset nonNaN=%d",
                            nonNaNCount, datasetNonNaNCount));
                }
            }
            
            idealCurveDataSet.notifyDataSetChanged();
            data.notifyDataChanged();
            chart.notifyDataSetChanged();
            
            // 凡例が表示されていることを確認
            if (!chart.getLegend().isEnabled()) {
                chart.getLegend().setEnabled(true);
            }
            
            chart.setVisibleXRangeMaximum(200);
            chart.moveViewToX(data.getEntryCount());
            chart.invalidate();
            
        });
    }
    
    /**
     * 理想曲線を更新（拍ごとにrPPGとSIN近似を1対1で対応させる）
     * 新しい拍が検出された時だけ理想曲線を更新し、その後のフレームでは既存のデータを保持する
     */
    private void updateIdealCurve() {
        // 理想曲線のデータが削除される前に、有効なエントリー数を確認
        int nonNaNCountBefore = 0;
        for (Entry entry : idealCurveEntries) {
            if (!Float.isNaN(entry.getY())) {
                nonNaNCountBefore++;
            }
        }
        
        // X座標を設定（常に更新）
        // 注意: idealCurveEntriesのサイズは、updateUi()でentriesと一緒に調整されるため、
        // ここではサイズ調整を行わない（新しい拍が検出された時に追加する）
        for (int i = 0; i < idealCurveEntries.size(); i++) {
            idealCurveEntries.get(i).setX(i);
        }
        
        // 理想曲線がない場合はNaNで埋める
        if (sinBPDistortion == null || !sinBPDistortion.hasIdealCurve()) {
            // 新しい拍が検出されていない場合は、既存のデータを保持（上書きしない）
            return;
        }
        
        // 理想曲線の終了時刻を取得
        long idealEndTime = sinBPDistortion.getIdealCurveEndTime();
        
        // 新しい拍が検出されたかチェック
        boolean newBeatDetected = (idealEndTime != lastIdealEndTime && idealEndTime > 0);
        
        // 理想曲線のデータが削除された後に、有効なエントリー数を確認
        int nonNaNCountAfter = 0;
        for (Entry entry : idealCurveEntries) {
            if (!Float.isNaN(entry.getY())) {
                nonNaNCountAfter++;
            }
        }
        
        Log.d("IdealCurve", String.format(Locale.getDefault(),
                "updateIdealCurve: entries.size()=%d, idealCurveEntries.size()=%d, idealEndTime=%d, lastIdealEndTime=%d, newBeatDetected=%b, nonNaNBefore=%d, nonNaNAfter=%d",
                entries.size(), idealCurveEntries.size(), idealEndTime, lastIdealEndTime, newBeatDetected, nonNaNCountBefore, nonNaNCountAfter));
        
        if (newBeatDetected) {
            // 新しい拍が検出された場合のみ、その拍の理想曲線を生成
            int beatSampleCount = sinBPDistortion.getCurrentBeatSampleCount();
            
            if (beatSampleCount > 0 && beatSampleCount <= entries.size()) {
                lastIdealWaveform.clear();
                lastIdealWaveformCursor = 0;

                // 拍の範囲を計算（最新のエントリーから逆算）
                int beatStartIndex = Math.max(0, entries.size() - beatSampleCount);
                int beatEndIndex = entries.size() - 1;
                
                Log.d("IdealCurve", String.format(Locale.getDefault(),
                        "New beat: beatRange=[%d-%d], sampleCount=%d, entries.size()=%d",
                        beatStartIndex, beatEndIndex, beatSampleCount, entries.size()));
                
                // その拍のrPPGデータの範囲を取得
                float minRppg = Float.MAX_VALUE;
                float maxRppg = Float.MIN_VALUE;
                for (int j = beatStartIndex; j <= beatEndIndex && j < entries.size(); j++) {
                    float rppgValue = entries.get(j).getY();
                    minRppg = Math.min(minRppg, rppgValue);
                    maxRppg = Math.max(maxRppg, rppgValue);
                }
                
                // 理想曲線の値の範囲を取得
                double minIdeal = Double.MAX_VALUE;
                double maxIdeal = Double.MIN_VALUE;
                for (int j = 0; j < beatSampleCount; j++) {
                    double relativePos = (double) j / Math.max(1, beatSampleCount - 1);
                    double testValue = sinBPDistortion.getIdealCurveValueByRelativePosition(relativePos);
                    if (!Double.isNaN(testValue)) {
                        minIdeal = Math.min(minIdeal, testValue);
                        maxIdeal = Math.max(maxIdeal, testValue);
                    }
                }

                boolean hasIdealRange = minIdeal != Double.MAX_VALUE && maxIdeal != Double.MIN_VALUE && maxIdeal > minIdeal;
                boolean hasRppgRange = maxRppg > minRppg;
                if (sinBPDistortion != null && hasIdealRange && hasRppgRange) {
                    sinBPDistortion.updateScaledCurveRange(minRppg, maxRppg, minIdeal, maxIdeal);
                }
                
                // その拍の理想曲線を生成（1拍分のデータのみ）
                // 理想曲線のエントリーが不足している場合は追加
                while (idealCurveEntries.size() < entries.size()) {
                    idealCurveEntries.add(new Entry(idealCurveEntries.size(), Float.NaN));
                }
                
                int validCount = 0;
                for (int i = 0; i < beatSampleCount; i++) {
                    int entryIndex = beatStartIndex + i;
                    if (entryIndex >= 0 && entryIndex < idealCurveEntries.size() && entryIndex < entries.size()) {
                        // 位相オフセットを適用（遅延補正）
                        double relativePos = (double) i / Math.max(1, beatSampleCount - 1);
                        double adjustedRelativePos = relativePos + sinPhaseOffset;
                        // 0.0～1.0の範囲に正規化（周期境界を越えた場合の処理）
                        if (adjustedRelativePos < 0.0) {
                            adjustedRelativePos += 1.0;
                        } else if (adjustedRelativePos > 1.0) {
                            adjustedRelativePos -= 1.0;
                        }
                        double idealValue = sinBPDistortion.getIdealCurveValueByRelativePosition(adjustedRelativePos);
                        
                        if (!Double.isNaN(idealValue) && maxIdeal > minIdeal && maxRppg > minRppg) {
                            // 実測波形の範囲にスケーリング
                            idealValue = minRppg + (idealValue - minIdeal) * (maxRppg - minRppg) / (maxIdeal - minIdeal);
                            idealCurveEntries.get(entryIndex).setY((float) idealValue);
                            lastIdealValue = (float) idealValue;
                            lastIdealWaveform.add((float) idealValue);
                            validCount++;
                            
                            // デバッグログ（最初と最後の数点のみ）
                            if (i < 3 || i >= beatSampleCount - 3) {
                                Log.d("IdealCurve-Detail", String.format(Locale.getDefault(),
                                        "entryIndex=%d, relativePos=%.3f, idealValue=%.2f, scaled=%.2f",
                                        entryIndex, relativePos, idealValue, idealCurveEntries.get(entryIndex).getY()));
                            }
                        } else {
                            // 波形持続のため、既存値があれば維持し、なければ最後の理想値を利用
                            float currentValue = idealCurveEntries.get(entryIndex).getY();
                            if (Float.isNaN(currentValue) && !Float.isNaN(lastIdealValue)) {
                                idealCurveEntries.get(entryIndex).setY(lastIdealValue);
                                lastIdealWaveform.add(lastIdealValue);
                            } else {
                                idealCurveEntries.get(entryIndex).setY(currentValue);
                                if (!Float.isNaN(currentValue)) {
                                    lastIdealWaveform.add(currentValue);
                                }
                            }
                        }
                    }
                }
                
                Log.d("IdealCurve", String.format(Locale.getDefault(),
                        "Ideal curve updated: beatRange=[%d-%d], validEntries=%d/%d, rPPG=[%.2f-%.2f], ideal=[%.4f-%.4f], phaseOffset=%.4f",
                        beatStartIndex, beatEndIndex, validCount, beatSampleCount, minRppg, maxRppg, minIdeal, maxIdeal, sinPhaseOffset));
                
                // 理想曲線のデータセットを再設定する必要があることをマーク
                idealCurveNeedsRefresh = true;
            } else {
                Log.w("IdealCurve", String.format(Locale.getDefault(),
                        "Invalid beatSampleCount: %d, entries.size()=%d", beatSampleCount, entries.size()));
            }
            
            // 理想曲線の終了時刻を更新
            lastIdealEndTime = idealEndTime;
        }
        // 新しい拍が検出されていない場合は、既存の理想曲線データを保持（上書きしない）
        // これにより、理想曲線は1拍分のデータとして表示され、チャートがスクロールする際に一緒に流れる
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
        
        // 拍ごとのデータ範囲をリセット
        lastIdealEndTime = 0;
        lastIdealValue = Float.NaN;
        lastIdealWaveform.clear();
        lastIdealWaveformCursor = 0;
        sinPhaseOffset = DEFAULT_SIN_PHASE_OFFSET;  // 位相オフセットをデフォルト値にリセット
    }

    // ★ 修正: startRecording メソッド
    public void startRecording() {
        clearRecordedData(); // 新しい記録を開始する前に、以前のデータをクリア
        recordingStartTime = System.currentTimeMillis(); // 記録開始時点を記録
        isRecordingActive = true;
        beatCounter = 0;
    }

    public void stopRecording()  {
        isRecordingActive = false;
    }

    public boolean hasRecordedTrainingData() {
        return !recTrainingTs.isEmpty();
    }

    private void autosaveTrainingDataSnapshotIfNeeded() {
        if (!isRecordingActive) {
            return;
        }
        if (outputBaseName == null || outputBaseName.isEmpty()) {
            return;
        }
        if (beatCounter <= 0 || beatCounter % TRAINING_AUTOSAVE_EVERY_BEATS != 0) {
            return;
        }
        final String autosaveName = outputBaseName;
        final int autosaveBeat = beatCounter;
        synchronized (autosaveStateLock) {
            if (trainingAutosaveInFlight) {
                Log.d("GreenValueAnalyzer", "Training data autosave skipped because a previous autosave is still running");
                return;
            }
            trainingAutosaveInFlight = true;
        }
        autosaveExecutor.execute(() -> {
            try {
                if (!isRecordingActive && autosaveBeat < beatCounter) {
                    Log.i("GreenValueAnalyzer", "Training data autosave cancelled after recording stopped");
                    return;
                }
                saveTrainingDataToCsvInternal(autosaveName, false, false);
                Log.i("GreenValueAnalyzer", "Training data autosaved at beat=" + autosaveBeat + " sessionId=" + sessionId);
            } catch (Exception e) {
                Log.e("GreenValueAnalyzer", "Training data autosave failed", e);
            } finally {
                synchronized (autosaveStateLock) {
                    trainingAutosaveInFlight = false;
                }
            }
        });
    }

    public void clearRecordedData() {
        recValue.clear();
        recIbi.clear();
        recSd.clear();
        recSmIbi.clear();
        recSmBpm.clear();
        recValTs.clear();
        recIbiTs.clear();
        
        // Sin波データのクリア
        recSinWave.clear();
        recSinAmplitude.clear();
        recSinMean.clear();
        recSinIBI.clear();
        recSinTs.clear();
        
        // 学習用データのクリア
        recTrainingTs.clear();
        recM1_A.clear();
        recM1_HR.clear();
        recM1_V2P_relTTP.clear();
        recM1_P2V_relTTP.clear();
        recM1_SBP.clear();
        recM1_DBP.clear();
        recM1_IbiInput.clear();
        recM1_SmoothedIbi.clear();
        recM1_UsedSmoothedIbi.clear();
        recM1_A_Used.clear();
        recM1_HR_Used.clear();
        recM1_V2P_relTTP_Used.clear();
        recM1_P2V_relTTP_Used.clear();
        recM1_SBP_Raw.clear();
        recM1_DBP_Raw.clear();
        recM1_ClampApplied.clear();
        recM1_FeatureClampApplied.clear();
        recM1_OutputValid.clear();
        recM1_FeatureClampReason.clear();
        recM1_RejectReason.clear();
        recM2_A.clear();
        recM2_HR.clear();
        recM2_V2P_relTTP.clear();
        recM2_P2V_relTTP.clear();
        recM2_E.clear();
        recM2_Stiffness.clear();
        recM2_SBP.clear();
        recM2_DBP.clear();
        recM2_Mean.clear();
        recM2_Phi.clear();
        recM2_SinPhi.clear();
        recM2_CosPhi.clear();
        recM2_FitA.clear();
        recM2_FitB.clear();
        recM2_IbiCurrent.clear();
        recM2_SmoothedIbi.clear();
        recM2_UsedSmoothedIbi.clear();
        recM2_A_Used.clear();
        recM2_HR_Used.clear();
        recM2_V2P_relTTP_Used.clear();
        recM2_P2V_relTTP_Used.clear();
        recM2_E_Used.clear();
        recM2_Stiffness_Used.clear();
        recM2_BeatSampleCount.clear();
        recM2_BeatMin.clear();
        recM2_BeatMax.clear();
        recM2_BeatRange.clear();
        recM2_BeatStd.clear();
        recM2_SystoleRatio.clear();
        recM2_DiastoleRatio.clear();
        recM2_SBP_Raw.clear();
        recM2_DBP_Raw.clear();
        recM2_SBP_Base.clear();
        recM2_DBP_Base.clear();
        recM2_SBP_Correction.clear();
        recM2_DBP_Correction.clear();
        recM2_SBP_AttemptFinal.clear();
        recM2_DBP_AttemptFinal.clear();
        recM2_ConstraintApplied.clear();
        recM2_ClampApplied.clear();
        recM2_FeatureClampApplied.clear();
        recM2_OutputValid.clear();
        recM2_FeatureClampReason.clear();
        recM2_RejectReason.clear();
        recM3_A.clear();
        recM3_HR.clear();
        recM3_Mean.clear();
        recM3_Phi.clear();
        recM3_SBP.clear();
        recM3_DBP.clear();
        recM3_SinPhi.clear();
        recM3_CosPhi.clear();
        recM3_FitA.clear();
        recM3_FitB.clear();
        recM3_FitRMSE.clear();
        recM3_IbiCurrent.clear();
        recM3_SmoothedIbi.clear();
        recM3_UsedSmoothedIbi.clear();
        recM3_A_Used.clear();
        recM3_HR_Used.clear();
        recM3_Mean_Used.clear();
        recM3_SinPhi_Used.clear();
        recM3_CosPhi_Used.clear();
        recM3_BeatSampleCount.clear();
        recM3_BeatMin.clear();
        recM3_BeatMax.clear();
        recM3_BeatRange.clear();
        recM3_BeatStd.clear();
        recM3_SystoleRatio.clear();
        recM3_DiastoleRatio.clear();
        recM3_SBP_Raw.clear();
        recM3_DBP_Raw.clear();
        recM3_SBP_AttemptFinal.clear();
        recM3_DBP_AttemptFinal.clear();
        recM3_ConstraintApplied.clear();
        recM3_ClampApplied.clear();
        recM3_FeatureClampApplied.clear();
        recM3_OutputValid.clear();
        recM3_FeatureClampReason.clear();
        recM3_RejectReason.clear();
        recBeatIndex.clear();
        recModeIndex.clear();
        recIso.clear();
        recExposureTime.clear();
        recWhiteBalanceMode.clear();
        recFocusDistance.clear();
        recFNumber.clear();
        recAperture.clear();
        recSensorSensitivity.clear();
        recColorTemperature.clear();
        recFps.clear();
        recIsValidBeat.clear();
        recArtifactFlag.clear();
    }

    private void emitRealtimeBeatLog(long timestampMs, int beatIndex) {
        try {
            JSONObject root = new JSONObject();
            root.put("event", "bp_beat");
            root.put("session_id", sessionId);
            root.put("subject_id", subjectId);
            root.put("session_number", sessionNumber);
            root.put("mode", activeMode);
            root.put("beat_index", beatIndex);
            root.put("timestamp_ms", timestampMs);
            root.put("elapsed_time_s", recordingStartTime > 0 ? (timestampMs - recordingStartTime) / 1000.0 : 0.0);
            root.put("is_valid_beat", isDetectionValid());
            root.put("app_version", appVersion);
            root.put("coefficient_version", coefficientVersion);

            JSONObject camera = new JSONObject();
            camera.put("iso", currentISO);
            camera.put("exposure_time_ns", currentExposureTime);
            camera.put("white_balance_mode", currentWhiteBalanceMode);
            camera.put("focus_distance", currentFocusDistance);
            camera.put("f_number", currentFNumber);
            camera.put("aperture", currentAperture);
            camera.put("sensor_sensitivity", currentSensorSensitivity);
            camera.put("color_temperature", currentColorTemperature);
            camera.put("fps", DEFAULT_FPS);
            root.put("camera", camera);

            JSONObject rtbp = new JSONObject();
            rtbp.put("sbp", bpEstimator != null ? bpEstimator.getLastSbp() : 0.0);
            rtbp.put("dbp", bpEstimator != null ? bpEstimator.getLastDbp() : 0.0);
            rtbp.put("sbp_avg", bpEstimator != null ? bpEstimator.getLastSbpAvg() : 0.0);
            rtbp.put("dbp_avg", bpEstimator != null ? bpEstimator.getLastDbpAvg() : 0.0);
            root.put("rtbp", rtbp);

            JSONObject sinD = new JSONObject();
            sinD.put("sbp", sinBPDistortion != null ? sinBPDistortion.getLastSinSBP() : 0.0);
            sinD.put("dbp", sinBPDistortion != null ? sinBPDistortion.getLastSinDBP() : 0.0);
            sinD.put("sbp_avg", sinBPDistortion != null ? sinBPDistortion.getLastSinSBPAvg() : 0.0);
            sinD.put("dbp_avg", sinBPDistortion != null ? sinBPDistortion.getLastSinDBPAvg() : 0.0);
            root.put("sinbp_d", sinD);

            JSONObject sinM = new JSONObject();
            sinM.put("sbp", sinBPModel != null ? sinBPModel.getLastSinSBP() : 0.0);
            sinM.put("dbp", sinBPModel != null ? sinBPModel.getLastSinDBP() : 0.0);
            sinM.put("sbp_avg", sinBPModel != null ? sinBPModel.getLastSinSBPAvg() : 0.0);
            sinM.put("dbp_avg", sinBPModel != null ? sinBPModel.getLastSinDBPAvg() : 0.0);
            root.put("sinbp_m", sinM);

            Log.i(REALTIME_LOG_TAG, root.toString());
        } catch (Exception e) {
            Log.e(REALTIME_LOG_TAG, "Failed to emit beat log", e);
        }
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
            // ヘッダー行（SinBPDistortion値を追加）
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
                        .append(String.format(Locale.getDefault(), "%.2f", sinBPDistortion != null ? sinBPDistortion.getLastSinSBP() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBPDistortion != null ? sinBPDistortion.getLastSinDBP() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBPDistortion != null ? sinBPDistortion.getLastSinSBPAvg() : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", sinBPDistortion != null ? sinBPDistortion.getLastSinDBPAvg() : 0.0)).append(", ")
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

    // ===== PCへの保存パス検出 =====
    private File getPCSaveDirectory(String baseName) {
        // USB接続時にアクセス可能なパスを検出
        // macOSの場合、Android File Transfer経由でアクセス可能なパスを試す
        String[] possiblePaths = {
            "/storage/emulated/0/Android/data/com.nakazawa.realtimeibibp/files/PC_Sync",
            "/storage/emulated/0/Download/PC_Sync",
            "/mnt/usb",
            "/storage/usb",
            "/mnt/media_rw"
        };
        
        for (String path : possiblePaths) {
            File dir = new File(path);
            if (dir.exists() && dir.canWrite()) {
                File targetDir = new File(dir, "Analysis/Data/Smartphone/" + baseName);
                if (targetDir.mkdirs() || targetDir.exists()) {
                    return targetDir;
                }
            }
        }
        
        // フォールバック: 外部ストレージのDownloadフォルダ内にPC_Syncフォルダを作成
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File pcSyncFolder = new File(downloadFolder, "PC_Sync/Analysis/Data/Smartphone/" + baseName);
        if (pcSyncFolder.mkdirs() || pcSyncFolder.exists()) {
            return pcSyncFolder;
        }
        
        return null; // PCへの保存ができない場合
    }
    
    // ===== ファイルをPCにも保存 =====
    private void saveToPCIfConnected(String baseName, String fileName, String csvContent, boolean isMode1) {
        if (!isMode1) return; // mode-1の時のみ
        
        File pcDir = getPCSaveDirectory(baseName);
        if (pcDir != null) {
            try {
                File pcFile = new File(pcDir, fileName);
                try (FileWriter writer = new FileWriter(pcFile)) {
                    writer.write(csvContent);
                }
                Log.d("GreenValueAnalyzer", "PCに保存成功: " + pcFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e("GreenValueAnalyzer", "PCへの保存失敗", e);
            }
        }
    }

    // ===== 元データCSV保存 =====
    public void saveRawDataToCsv(String name) {
        saveRawDataToCsv(name, false);
    }
    
    public void saveRawDataToCsv(String name, boolean isMode1) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_元データ.csv");

        if (recValue.isEmpty() && recIbi.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録された元データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("経過時間_秒, Green, IBI, Smoothed_IBI, bpmSD, Smoothed_BPM\n");

        // IBIデータとGreenデータをマージして保存
        // IBIデータを基準に、対応するGreen値を探す
        for (int i = 0; i < recIbi.size(); i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recIbiTs.get(i) - recordingStartTime) / 1000.0 : 0.0;
            
            // 対応するGreen値を探す（タイムスタンプが最も近いもの）
            double greenValue = 0.0;
            if (!recValue.isEmpty() && !recValTs.isEmpty()) {
                long ibiTime = recIbiTs.get(i);
                long minDiff = Long.MAX_VALUE;
                int closestIdx = 0;
                for (int j = 0; j < recValTs.size(); j++) {
                    long diff = Math.abs(recValTs.get(j) - ibiTime);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestIdx = j;
                    }
                }
                if (minDiff < 1000) { // 1秒以内
                    greenValue = recValue.get(closestIdx);
                }
            }
            
            csvContent.append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", greenValue)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", recIbi.get(i))).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recSmIbi.size() ? recSmIbi.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recSd.size() ? recSd.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recSmBpm.size() ? recSmBpm.get(i) : 0.0))
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            ui.post(() ->
                    Toast.makeText(ctx, "元データ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "元データ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
        
        // PCにも保存（mode-1の場合）
        String baseName = name; // ファイル名から共通部分を抽出（拡張子とサフィックスを除く）
        saveToPCIfConnected(baseName, name + "_元データ.csv", csvContent.toString(), isMode1);
    }

    // ===== Wave Data CSV保存 =====
    public void saveWaveDataToCsv(String name) {
        saveWaveDataToCsv(name, false);
    }
    
    public void saveWaveDataToCsv(String name, boolean isMode1) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_Wave_Data.csv");

        if (recValue.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録されたWaveデータがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("経過時間_秒, Green, SinWave\n");

        // Green値とSinWave値を時間でマッチング
        for (int i = 0; i < recValue.size(); i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recValTs.get(i) - recordingStartTime) / 1000.0 : 0.0;
            
            double greenValue = recValue.get(i);
            
            // 記録時点で保存したSinWave値をそのまま利用（UIで表示される理想波形と一致）
            double sinWaveValue = 0.0;
            if (i < recSinWave.size() && !Double.isNaN(recSinWave.get(i))) {
                sinWaveValue = recSinWave.get(i);
            }
            
            csvContent.append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", greenValue)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", sinWaveValue))
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            ui.post(() ->
                    Toast.makeText(ctx, "Waveデータ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "Waveデータ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
        
        // PCにも保存（mode-1の場合）
        saveToPCIfConnected(name, name + "_Wave_Data.csv", csvContent.toString(), isMode1);
    }

    // ===== RTBP専用CSV保存 =====
    public void saveRTBPToCsv(String name) {
        saveRTBPToCsv(name, false);
    }
    
    public void saveRTBPToCsv(String name, boolean isMode1) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_RTBP.csv");

        if (recTrainingTs.isEmpty() || recM1_A.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録されたRTBPデータがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("経過時間_秒, A, HR, V2P_relTTP, P2V_relTTP, A_used, HR_used, V2P_relTTP_used, P2V_relTTP_used, SBP, DBP, IBI_input_ms, IBI_smoothed_ms, used_smoothed_ibi, SBP_raw, DBP_raw, clamp_applied, feature_clamp_applied, output_valid, feature_clamp_reason, reject_reason\n");

        int maxSize = recTrainingTs.size();
        for (int i = 0; i < maxSize; i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recTrainingTs.get(i) - recordingStartTime) / 1000.0 : 0.0;
            
            csvContent.append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_A.size() ? recM1_A.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_HR.size() ? recM1_HR.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_V2P_relTTP.size() ? recM1_V2P_relTTP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_P2V_relTTP.size() ? recM1_P2V_relTTP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_A_Used.size() ? recM1_A_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_HR_Used.size() ? recM1_HR_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_V2P_relTTP_Used.size() ? recM1_V2P_relTTP_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_P2V_relTTP_Used.size() ? recM1_P2V_relTTP_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM1_SBP.size() ? recM1_SBP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM1_DBP.size() ? recM1_DBP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_IbiInput.size() ? recM1_IbiInput.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM1_SmoothedIbi.size() ? recM1_SmoothedIbi.get(i) : 0.0)).append(", ")
                    .append(i < recM1_UsedSmoothedIbi.size() ? recM1_UsedSmoothedIbi.get(i) : 0).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM1_SBP_Raw.size() ? recM1_SBP_Raw.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM1_DBP_Raw.size() ? recM1_DBP_Raw.get(i) : 0.0)).append(", ")
                    .append(i < recM1_ClampApplied.size() ? recM1_ClampApplied.get(i) : 0).append(", ")
                    .append(i < recM1_FeatureClampApplied.size() ? recM1_FeatureClampApplied.get(i) : 0).append(", ")
                    .append(i < recM1_OutputValid.size() ? recM1_OutputValid.get(i) : 0).append(", ")
                    .append(sanitizeCsvText(i < recM1_FeatureClampReason.size() ? recM1_FeatureClampReason.get(i) : "missing")).append(", ")
                    .append(sanitizeCsvText(i < recM1_RejectReason.size() ? recM1_RejectReason.get(i) : "missing"))
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            ui.post(() ->
                    Toast.makeText(ctx, "RTBPデータ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "RTBPデータ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
        
        // PCにも保存（mode-1の場合）
        saveToPCIfConnected(name, name + "_RTBP.csv", csvContent.toString(), isMode1);
    }

    // ===== SinBP(M)専用CSV保存 =====
    public void saveSinBPMToCsv(String name) {
        saveSinBPMToCsv(name, false);
    }
    
    public void saveSinBPMToCsv(String name, boolean isMode1) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_SinBP_M.csv");

        if (recTrainingTs.isEmpty() || recM3_A.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録されたSinBP(M)データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("経過時間_秒, A, HR, Mean, Phi, A_used, HR_used, Mean_used, sinPhi_used, cosPhi_used, SBP, DBP, sinPhi, cosPhi, fit_a, fit_b, fit_rmse, IBI_current_ms, IBI_smoothed_ms, used_smoothed_ibi, beat_sample_count, beat_min, beat_max, beat_range, beat_std, systole_ratio, diastole_ratio, SBP_raw, DBP_raw, SBP_attempt_final, DBP_attempt_final, constraint_applied, clamp_applied, feature_clamp_applied, output_valid, feature_clamp_reason, reject_reason\n");

        int maxSize = recTrainingTs.size();
        for (int i = 0; i < maxSize; i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recTrainingTs.get(i) - recordingStartTime) / 1000.0 : 0.0;
            
            csvContent.append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_A.size() ? recM3_A.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_HR.size() ? recM3_HR.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_Mean.size() ? recM3_Mean.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_Phi.size() ? recM3_Phi.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_A_Used.size() ? recM3_A_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_HR_Used.size() ? recM3_HR_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_Mean_Used.size() ? recM3_Mean_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_SinPhi_Used.size() ? recM3_SinPhi_Used.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_CosPhi_Used.size() ? recM3_CosPhi_Used.get(i) : 1.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_SBP.size() ? recM3_SBP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_DBP.size() ? recM3_DBP.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_SinPhi.size() ? recM3_SinPhi.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_CosPhi.size() ? recM3_CosPhi.get(i) : 1.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_FitA.size() ? recM3_FitA.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_FitB.size() ? recM3_FitB.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_FitRMSE.size() ? recM3_FitRMSE.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_IbiCurrent.size() ? recM3_IbiCurrent.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_SmoothedIbi.size() ? recM3_SmoothedIbi.get(i) : 0.0)).append(", ")
                    .append(i < recM3_UsedSmoothedIbi.size() ? recM3_UsedSmoothedIbi.get(i) : 0).append(", ")
                    .append(i < recM3_BeatSampleCount.size() ? recM3_BeatSampleCount.get(i) : 0).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_BeatMin.size() ? recM3_BeatMin.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_BeatMax.size() ? recM3_BeatMax.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_BeatRange.size() ? recM3_BeatRange.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_BeatStd.size() ? recM3_BeatStd.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_SystoleRatio.size() ? recM3_SystoleRatio.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recM3_DiastoleRatio.size() ? recM3_DiastoleRatio.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_SBP_Raw.size() ? recM3_SBP_Raw.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_DBP_Raw.size() ? recM3_DBP_Raw.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_SBP_AttemptFinal.size() ? recM3_SBP_AttemptFinal.get(i) : 0.0)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", i < recM3_DBP_AttemptFinal.size() ? recM3_DBP_AttemptFinal.get(i) : 0.0)).append(", ")
                    .append(i < recM3_ConstraintApplied.size() ? recM3_ConstraintApplied.get(i) : 0).append(", ")
                    .append(i < recM3_ClampApplied.size() ? recM3_ClampApplied.get(i) : 0).append(", ")
                    .append(i < recM3_FeatureClampApplied.size() ? recM3_FeatureClampApplied.get(i) : 0).append(", ")
                    .append(i < recM3_OutputValid.size() ? recM3_OutputValid.get(i) : 0).append(", ")
                    .append(sanitizeCsvText(i < recM3_FeatureClampReason.size() ? recM3_FeatureClampReason.get(i) : "missing")).append(", ")
                    .append(sanitizeCsvText(i < recM3_RejectReason.size() ? recM3_RejectReason.get(i) : "missing"))
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            ui.post(() ->
                    Toast.makeText(ctx, "SinBP(M)データ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "SinBP(M)データ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
        
        // PCにも保存（mode-1の場合）
        saveToPCIfConnected(name, name + "_SinBP_M.csv", csvContent.toString(), isMode1);
    }

    // ===== SinBP(D)専用CSV保存 =====
    public void saveSinBPDToCsv(String name) {
        saveSinBPDToCsv(name, false);
    }
    
    public void saveSinBPDToCsv(String name, boolean isMode1) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_SinBP_D.csv");
        double[] m2SbpCoefficients = SinBPDistortion.getSbpCoefficients();
        double[] m2DbpCoefficients = SinBPDistortion.getDbpCoefficients();
        double[] m2SbpBaseCoefficients = SinBPDistortion.getSbpBaseCoefficients();
        double[] m2DbpBaseCoefficients = SinBPDistortion.getDbpBaseCoefficients();
        double[] m2SbpCorrectionCoefficients = SinBPDistortion.getSbpCorrectionCoefficients();
        double[] m2DbpCorrectionCoefficients = SinBPDistortion.getDbpCorrectionCoefficients();

        if (recTrainingTs.isEmpty() || recM2_A.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録されたSinBP(D)データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("経過時間_秒, A, HR, V2P_relTTP, P2V_relTTP, E, Stiffness, A_used, HR_used, V2P_relTTP_used, P2V_relTTP_used, E_used, Stiffness_used, ")
                .append("SBP, DBP, Mean, Phi, sinPhi, cosPhi, fit_a, fit_b, IBI_current_ms, IBI_smoothed_ms, used_smoothed_ibi, ")
                .append("beat_sample_count, beat_min, beat_max, beat_range, beat_std, systole_ratio, diastole_ratio, ")
                .append("SBP_raw, DBP_raw, SBP_base, DBP_base, SBP_correction, DBP_correction, SBP_attempt_final, DBP_attempt_final, ")
                .append("constraint_applied, clamp_applied, feature_clamp_applied, output_valid, feature_clamp_reason, reject_reason, ")
                .append("M2_SBP_ALPHA0, M2_SBP_ALPHA1, M2_SBP_ALPHA2, M2_SBP_ALPHA3, M2_SBP_ALPHA4, M2_SBP_ALPHA5, M2_SBP_ALPHA6, ")
                .append("M2_DBP_BETA0, M2_DBP_BETA1, M2_DBP_BETA2, M2_DBP_BETA3, M2_DBP_BETA4, M2_DBP_BETA5, M2_DBP_BETA6, ")
                .append("M2_SBP_BASE_C0, M2_SBP_BASE_C1, M2_SBP_BASE_C2, M2_SBP_BASE_C3, M2_SBP_BASE_C4, ")
                .append("M2_DBP_BASE_D0, M2_DBP_BASE_D1, M2_DBP_BASE_D2, M2_DBP_BASE_D3, M2_DBP_BASE_D4, ")
                .append("M2_SBP_CORR_G0, M2_SBP_CORR_G1, M2_SBP_CORR_G2, ")
                .append("M2_DBP_CORR_H0, M2_DBP_CORR_H1, M2_DBP_CORR_H2, ")
                .append("M2_SBP_term_intercept, M2_SBP_term_A, M2_SBP_term_HR, M2_SBP_term_V2P_relTTP, M2_SBP_term_P2V_relTTP, M2_SBP_term_Stiffness, M2_SBP_term_E, ")
                .append("M2_DBP_term_intercept, M2_DBP_term_A, M2_DBP_term_HR, M2_DBP_term_V2P_relTTP, M2_DBP_term_P2V_relTTP, M2_DBP_term_Stiffness, M2_DBP_term_E, ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_E_ONLY, SinBPDistortionComparison.E_ONLY_LABELS);
        csvContent.append(", ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_E2, SinBPDistortionComparison.E2_LABELS);
        csvContent.append(", ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_LOCAL_A, SinBPDistortionComparison.LOCAL_A_LABELS);
        csvContent.append("\n");

        int maxSize = recTrainingTs.size();
        for (int i = 0; i < maxSize; i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recTrainingTs.get(i) - recordingStartTime) / 1000.0 : 0.0;

            double m2A = i < recM2_A.size() ? recM2_A.get(i) : 0.0;
            double m2Hr = i < recM2_HR.size() ? recM2_HR.get(i) : 0.0;
            double m2V2p = i < recM2_V2P_relTTP.size() ? recM2_V2P_relTTP.get(i) : 0.0;
            double m2P2v = i < recM2_P2V_relTTP.size() ? recM2_P2V_relTTP.get(i) : 0.0;
            double m2E = i < recM2_E.size() ? recM2_E.get(i) : 0.0;
            double m2Stiffness = i < recM2_Stiffness.size() ? recM2_Stiffness.get(i) : 0.0;
            double m2Sbp = i < recM2_SBP.size() ? recM2_SBP.get(i) : 0.0;
            double m2Dbp = i < recM2_DBP.size() ? recM2_DBP.get(i) : 0.0;
            double m2Mean = i < recM2_Mean.size() ? recM2_Mean.get(i) : 0.0;
            double m2Phi = i < recM2_Phi.size() ? recM2_Phi.get(i) : 0.0;
            double m2SinPhi = i < recM2_SinPhi.size() ? recM2_SinPhi.get(i) : 0.0;
            double m2CosPhi = i < recM2_CosPhi.size() ? recM2_CosPhi.get(i) : 1.0;
            double m2FitA = i < recM2_FitA.size() ? recM2_FitA.get(i) : 0.0;
            double m2FitB = i < recM2_FitB.size() ? recM2_FitB.get(i) : 0.0;
            double m2IbiCurrent = i < recM2_IbiCurrent.size() ? recM2_IbiCurrent.get(i) : 0.0;
            double m2SmoothedIbi = i < recM2_SmoothedIbi.size() ? recM2_SmoothedIbi.get(i) : 0.0;
            int m2UsedSmoothedIbi = i < recM2_UsedSmoothedIbi.size() ? recM2_UsedSmoothedIbi.get(i) : 0;
            double m2AUsed = i < recM2_A_Used.size() ? recM2_A_Used.get(i) : m2A;
            double m2HrUsed = i < recM2_HR_Used.size() ? recM2_HR_Used.get(i) : m2Hr;
            double m2V2pUsed = i < recM2_V2P_relTTP_Used.size() ? recM2_V2P_relTTP_Used.get(i) : m2V2p;
            double m2P2vUsed = i < recM2_P2V_relTTP_Used.size() ? recM2_P2V_relTTP_Used.get(i) : m2P2v;
            double m2EUsed = i < recM2_E_Used.size() ? recM2_E_Used.get(i) : m2E;
            double m2StiffnessUsed = i < recM2_Stiffness_Used.size() ? recM2_Stiffness_Used.get(i) : m2Stiffness;
            int m2BeatSampleCount = i < recM2_BeatSampleCount.size() ? recM2_BeatSampleCount.get(i) : 0;
            double m2BeatMin = i < recM2_BeatMin.size() ? recM2_BeatMin.get(i) : 0.0;
            double m2BeatMax = i < recM2_BeatMax.size() ? recM2_BeatMax.get(i) : 0.0;
            double m2BeatRange = i < recM2_BeatRange.size() ? recM2_BeatRange.get(i) : 0.0;
            double m2BeatStd = i < recM2_BeatStd.size() ? recM2_BeatStd.get(i) : 0.0;
            double m2SystoleRatio = i < recM2_SystoleRatio.size() ? recM2_SystoleRatio.get(i) : 0.0;
            double m2DiastoleRatio = i < recM2_DiastoleRatio.size() ? recM2_DiastoleRatio.get(i) : 0.0;
            double m2SbpRaw = i < recM2_SBP_Raw.size() ? recM2_SBP_Raw.get(i) : 0.0;
            double m2DbpRaw = i < recM2_DBP_Raw.size() ? recM2_DBP_Raw.get(i) : 0.0;
            double m2SbpBase = i < recM2_SBP_Base.size() ? recM2_SBP_Base.get(i) : 0.0;
            double m2DbpBase = i < recM2_DBP_Base.size() ? recM2_DBP_Base.get(i) : 0.0;
            double m2SbpCorrection = i < recM2_SBP_Correction.size() ? recM2_SBP_Correction.get(i) : 0.0;
            double m2DbpCorrection = i < recM2_DBP_Correction.size() ? recM2_DBP_Correction.get(i) : 0.0;
            double m2SbpAttemptFinal = i < recM2_SBP_AttemptFinal.size() ? recM2_SBP_AttemptFinal.get(i) : 0.0;
            double m2DbpAttemptFinal = i < recM2_DBP_AttemptFinal.size() ? recM2_DBP_AttemptFinal.get(i) : 0.0;
            int m2ConstraintApplied = i < recM2_ConstraintApplied.size() ? recM2_ConstraintApplied.get(i) : 0;
            int m2ClampApplied = i < recM2_ClampApplied.size() ? recM2_ClampApplied.get(i) : 0;
            int m2FeatureClampApplied = i < recM2_FeatureClampApplied.size() ? recM2_FeatureClampApplied.get(i) : 0;
            int m2OutputValid = i < recM2_OutputValid.size() ? recM2_OutputValid.get(i) : 0;
            String m2FeatureClampReason = i < recM2_FeatureClampReason.size() ? recM2_FeatureClampReason.get(i) : "missing";
            String m2RejectReason = i < recM2_RejectReason.size() ? recM2_RejectReason.get(i) : "missing";
            double[] m2Features = new double[] { m2AUsed, m2HrUsed, m2V2pUsed, m2P2vUsed, m2StiffnessUsed, m2EUsed };
            double[] m2SbpTerms = computeLinearTerms(
                    m2SbpCoefficients[0], Arrays.copyOfRange(m2SbpCoefficients, 1, m2SbpCoefficients.length), m2Features);
            double[] m2DbpTerms = computeLinearTerms(
                    m2DbpCoefficients[0], Arrays.copyOfRange(m2DbpCoefficients, 1, m2DbpCoefficients.length), m2Features);
            SinBPDistortionComparison.VariantResult m2EOnlyVariant =
                    SinBPDistortionComparison.estimateEOnly(m2A, m2Hr, m2V2p, m2P2v, m2E);
            SinBPDistortionComparison.VariantResult m2E2Variant =
                    SinBPDistortionComparison.estimateE2(m2A, m2Hr, m2V2p, m2P2v, m2E);
            SinBPDistortionComparison.VariantResult m2LocalAVariant =
                    SinBPDistortionComparison.estimateLocalA(m2BeatRange, m2Hr, m2V2p, m2P2v, m2E);

            csvContent.append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2A)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Hr)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2V2p)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2P2v)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2E)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Stiffness)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2AUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2HrUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2V2pUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2P2vUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2EUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2StiffnessUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2Sbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2Dbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Mean)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Phi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SinPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2CosPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2FitA)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2FitB)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2IbiCurrent)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SmoothedIbi)).append(", ")
                    .append(m2UsedSmoothedIbi).append(", ")
                    .append(m2BeatSampleCount).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatMin)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatMax)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatRange)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatStd)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SystoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2DiastoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpBase)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpBase)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpCorrection)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpCorrection)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpAttemptFinal)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpAttemptFinal)).append(", ")
                    .append(m2ConstraintApplied).append(", ")
                    .append(m2ClampApplied).append(", ")
                    .append(m2FeatureClampApplied).append(", ")
                    .append(m2OutputValid).append(", ")
                    .append(sanitizeCsvText(m2FeatureClampReason)).append(", ")
                    .append(sanitizeCsvText(m2RejectReason)).append(", ")
                    .append(formatCoefficients(m2SbpCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpCoefficients)).append(", ")
                    .append(formatCoefficients(m2SbpBaseCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpBaseCoefficients)).append(", ")
                    .append(formatCoefficients(m2SbpCorrectionCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpCorrectionCoefficients)).append(", ")
                    .append(formatValues(m2SbpTerms)).append(", ")
                    .append(formatValues(m2DbpTerms)).append(", ");
            appendVariantValues(csvContent, m2EOnlyVariant);
            csvContent.append(", ");
            appendVariantValues(csvContent, m2E2Variant);
            csvContent.append(", ");
            appendVariantValues(csvContent, m2LocalAVariant);
            csvContent
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            ui.post(() ->
                    Toast.makeText(ctx, "SinBP(D)データ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "SinBP(D)データ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
        
        // PCにも保存（mode-1の場合）
        saveToPCIfConnected(name, name + "_SinBP_D.csv", csvContent.toString(), isMode1);
    }

    // ===== 学習用CSV保存（BP_Analysis用、後方互換性のため残す） =====
    public void saveTrainingDataToCsv(String name) {
        saveTrainingDataToCsv(name, false);
    }
    
    public void saveTrainingDataToCsv(String name, boolean isMode1) {
        saveTrainingDataToCsvInternal(name, isMode1, true);
    }

    private void saveTrainingDataToCsvInternal(String name, boolean isMode1, boolean showToast) {
        synchronized (trainingCsvSaveLock) {
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_Training_Data.csv");
        double[] m1SbpCoefficients = RealtimeBP.getSbpCoefficients();
        double[] m1DbpCoefficients = RealtimeBP.getDbpCoefficients();
        double[] m2SbpCoefficients = SinBPDistortion.getSbpCoefficients();
        double[] m2DbpCoefficients = SinBPDistortion.getDbpCoefficients();
        double[] m2SbpBaseCoefficients = SinBPDistortion.getSbpBaseCoefficients();
        double[] m2DbpBaseCoefficients = SinBPDistortion.getDbpBaseCoefficients();
        double[] m2SbpCorrectionCoefficients = SinBPDistortion.getSbpCorrectionCoefficients();
        double[] m2DbpCorrectionCoefficients = SinBPDistortion.getDbpCorrectionCoefficients();
        double[] m3SbpCoefficients = SinBPModel.getSbpCoefficients();
        double[] m3DbpCoefficients = SinBPModel.getDbpCoefficients();

        // 学習用データが空の場合はトースト表示して終了
        if (recTrainingTs.isEmpty()) {
            if (showToast) {
                ui.post(() ->
                        Toast.makeText(ctx, "記録された学習用データがありません", Toast.LENGTH_SHORT).show()
                );
            }
            return;
        }

        StringBuilder csvContent = new StringBuilder();
        csvContent.append("session_id, subject_id, session_number, mode, beat_index, timestamp, timestamp_ms, wall_time_iso, 経過時間_秒, app_version, coefficient_version, ISO, exposure_time_ns, white_balance_mode, focus_distance, f_number, aperture, sensor_sensitivity, color_temperature, fps, is_valid_beat, artifact_flag, ref_SBP, ref_DBP, ")
                .append("M1_A, M1_HR, M1_V2P_relTTP, M1_P2V_relTTP, M1_SBP, M1_DBP, ")
                .append("M1_IBI_input_ms, M1_IBI_smoothed_ms, M1_used_smoothed_ibi, M1_A_used, M1_HR_used, M1_V2P_relTTP_used, M1_P2V_relTTP_used, M1_SBP_raw, M1_DBP_raw, M1_clamp_applied, M1_feature_clamp_applied, M1_output_valid, M1_feature_clamp_reason, M1_reject_reason, ")
                .append("M1_SBP_C0, M1_SBP_C1, M1_SBP_C2, M1_SBP_C3, M1_SBP_C4, M1_DBP_D0, M1_DBP_D1, M1_DBP_D2, M1_DBP_D3, M1_DBP_D4, ")
                .append("M1_SBP_term_intercept, M1_SBP_term_A, M1_SBP_term_HR, M1_SBP_term_V2P_relTTP, M1_SBP_term_P2V_relTTP, ")
                .append("M1_DBP_term_intercept, M1_DBP_term_A, M1_DBP_term_HR, M1_DBP_term_V2P_relTTP, M1_DBP_term_P2V_relTTP, ")
                .append("M2_A, M2_HR, M2_V2P_relTTP, M2_P2V_relTTP, M2_E, M2_Stiffness, M2_SBP, M2_DBP, ")
                .append("M2_Mean, M2_Phi, M2_sinPhi, M2_cosPhi, M2_fit_a, M2_fit_b, M2_IBI_current_ms, M2_IBI_smoothed_ms, M2_used_smoothed_ibi, ")
                .append("M2_A_used, M2_HR_used, M2_V2P_relTTP_used, M2_P2V_relTTP_used, M2_E_used, M2_Stiffness_used, ")
                .append("M2_beat_sample_count, M2_beat_min, M2_beat_max, M2_beat_range, M2_beat_std, M2_systole_ratio, M2_diastole_ratio, ")
                .append("M2_SBP_raw, M2_DBP_raw, M2_SBP_base, M2_DBP_base, M2_SBP_correction, M2_DBP_correction, M2_SBP_attempt_final, M2_DBP_attempt_final, M2_constraint_applied, M2_clamp_applied, M2_feature_clamp_applied, M2_output_valid, M2_feature_clamp_reason, M2_reject_reason, ")
                .append("M2_SBP_ALPHA0, M2_SBP_ALPHA1, M2_SBP_ALPHA2, M2_SBP_ALPHA3, M2_SBP_ALPHA4, M2_SBP_ALPHA5, M2_SBP_ALPHA6, ")
                .append("M2_DBP_BETA0, M2_DBP_BETA1, M2_DBP_BETA2, M2_DBP_BETA3, M2_DBP_BETA4, M2_DBP_BETA5, M2_DBP_BETA6, ")
                .append("M2_SBP_BASE_C0, M2_SBP_BASE_C1, M2_SBP_BASE_C2, M2_SBP_BASE_C3, M2_SBP_BASE_C4, ")
                .append("M2_DBP_BASE_D0, M2_DBP_BASE_D1, M2_DBP_BASE_D2, M2_DBP_BASE_D3, M2_DBP_BASE_D4, ")
                .append("M2_SBP_CORR_G0, M2_SBP_CORR_G1, M2_SBP_CORR_G2, ")
                .append("M2_DBP_CORR_H0, M2_DBP_CORR_H1, M2_DBP_CORR_H2, ")
                .append("M2_SBP_term_intercept, M2_SBP_term_A, M2_SBP_term_HR, M2_SBP_term_V2P_relTTP, M2_SBP_term_P2V_relTTP, M2_SBP_term_Stiffness, M2_SBP_term_E, ")
                .append("M2_DBP_term_intercept, M2_DBP_term_A, M2_DBP_term_HR, M2_DBP_term_V2P_relTTP, M2_DBP_term_P2V_relTTP, M2_DBP_term_Stiffness, M2_DBP_term_E, ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_E_ONLY, SinBPDistortionComparison.E_ONLY_LABELS);
        csvContent.append(", ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_E2, SinBPDistortionComparison.E2_LABELS);
        csvContent.append(", ");
        appendVariantHeader(csvContent, SinBPDistortionComparison.METHOD_LOCAL_A, SinBPDistortionComparison.LOCAL_A_LABELS);
        csvContent.append(", ")
                .append("M3_A, M3_HR, M3_Mean, M3_Phi, M3_SBP, M3_DBP, ")
                .append("M3_sinPhi, M3_cosPhi, M3_fit_a, M3_fit_b, M3_fit_rmse, M3_IBI_current_ms, M3_IBI_smoothed_ms, M3_used_smoothed_ibi, ")
                .append("M3_A_used, M3_HR_used, M3_Mean_used, M3_sinPhi_used, M3_cosPhi_used, ")
                .append("M3_beat_sample_count, M3_beat_min, M3_beat_max, M3_beat_range, M3_beat_std, M3_systole_ratio, M3_diastole_ratio, ")
                .append("M3_SBP_raw, M3_DBP_raw, M3_SBP_attempt_final, M3_DBP_attempt_final, M3_constraint_applied, M3_clamp_applied, M3_feature_clamp_applied, M3_output_valid, M3_feature_clamp_reason, M3_reject_reason, ")
                .append("M3_SBP_ALPHA0, M3_SBP_ALPHA1, M3_SBP_ALPHA2, M3_SBP_ALPHA3, M3_SBP_ALPHA4, M3_SBP_ALPHA5, ")
                .append("M3_DBP_BETA0, M3_DBP_BETA1, M3_DBP_BETA2, M3_DBP_BETA3, M3_DBP_BETA4, M3_DBP_BETA5, ")
                .append("M3_SBP_term_intercept, M3_SBP_term_A, M3_SBP_term_HR, M3_SBP_term_Mean, M3_SBP_term_sinPhi, M3_SBP_term_cosPhi, ")
                .append("M3_DBP_term_intercept, M3_DBP_term_A, M3_DBP_term_HR, M3_DBP_term_Mean, M3_DBP_term_sinPhi, M3_DBP_term_cosPhi\n");

        // 記録データを CSV に書き出し
        int maxSize = recTrainingTs.size();
        for (int i = 0; i < maxSize; i++) {
            // 開始時点からの経過時間（秒）を計算
            double elapsedSeconds = (recordingStartTime > 0) ? 
                (recTrainingTs.get(i) - recordingStartTime) / 1000.0 : 0.0;
            long timestampMs = recTrainingTs.get(i);
            String wallTimeIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.getDefault())
                    .format(new Date(timestampMs));
            double m1A = i < recM1_A.size() ? recM1_A.get(i) : 0.0;
            double m1Hr = i < recM1_HR.size() ? recM1_HR.get(i) : 0.0;
            double m1V2p = i < recM1_V2P_relTTP.size() ? recM1_V2P_relTTP.get(i) : 0.0;
            double m1P2v = i < recM1_P2V_relTTP.size() ? recM1_P2V_relTTP.get(i) : 0.0;
            double m1Sbp = i < recM1_SBP.size() ? recM1_SBP.get(i) : 0.0;
            double m1Dbp = i < recM1_DBP.size() ? recM1_DBP.get(i) : 0.0;
            double m1IbiInput = i < recM1_IbiInput.size() ? recM1_IbiInput.get(i) : 0.0;
            double m1SmoothedIbi = i < recM1_SmoothedIbi.size() ? recM1_SmoothedIbi.get(i) : 0.0;
            int m1UsedSmoothedIbi = i < recM1_UsedSmoothedIbi.size() ? recM1_UsedSmoothedIbi.get(i) : 0;
            double m1AUsed = i < recM1_A_Used.size() ? recM1_A_Used.get(i) : m1A;
            double m1HrUsed = i < recM1_HR_Used.size() ? recM1_HR_Used.get(i) : m1Hr;
            double m1V2pUsed = i < recM1_V2P_relTTP_Used.size() ? recM1_V2P_relTTP_Used.get(i) : m1V2p;
            double m1P2vUsed = i < recM1_P2V_relTTP_Used.size() ? recM1_P2V_relTTP_Used.get(i) : m1P2v;
            double m1SbpRaw = i < recM1_SBP_Raw.size() ? recM1_SBP_Raw.get(i) : 0.0;
            double m1DbpRaw = i < recM1_DBP_Raw.size() ? recM1_DBP_Raw.get(i) : 0.0;
            int m1ClampApplied = i < recM1_ClampApplied.size() ? recM1_ClampApplied.get(i) : 0;
            int m1FeatureClampApplied = i < recM1_FeatureClampApplied.size() ? recM1_FeatureClampApplied.get(i) : 0;
            int m1OutputValid = i < recM1_OutputValid.size() ? recM1_OutputValid.get(i) : 0;
            String m1FeatureClampReason = i < recM1_FeatureClampReason.size() ? recM1_FeatureClampReason.get(i) : "missing";
            String m1RejectReason = i < recM1_RejectReason.size() ? recM1_RejectReason.get(i) : "missing";
            double[] m1Features = new double[] { m1AUsed, m1HrUsed, m1V2pUsed, m1P2vUsed };
            double[] m1SbpTerms = computeLinearTerms(m1SbpCoefficients[0], Arrays.copyOfRange(m1SbpCoefficients, 1, m1SbpCoefficients.length), m1Features);
            double[] m1DbpTerms = computeLinearTerms(m1DbpCoefficients[0], Arrays.copyOfRange(m1DbpCoefficients, 1, m1DbpCoefficients.length), m1Features);

            double m2A = i < recM2_A.size() ? recM2_A.get(i) : 0.0;
            double m2Hr = i < recM2_HR.size() ? recM2_HR.get(i) : 0.0;
            double m2V2p = i < recM2_V2P_relTTP.size() ? recM2_V2P_relTTP.get(i) : 0.0;
            double m2P2v = i < recM2_P2V_relTTP.size() ? recM2_P2V_relTTP.get(i) : 0.0;
            double m2E = i < recM2_E.size() ? recM2_E.get(i) : 0.0;
            double m2Stiffness = i < recM2_Stiffness.size() ? recM2_Stiffness.get(i) : 0.0;
            double m2Sbp = i < recM2_SBP.size() ? recM2_SBP.get(i) : 0.0;
            double m2Dbp = i < recM2_DBP.size() ? recM2_DBP.get(i) : 0.0;
            double m2Mean = i < recM2_Mean.size() ? recM2_Mean.get(i) : 0.0;
            double m2Phi = i < recM2_Phi.size() ? recM2_Phi.get(i) : 0.0;
            double m2SinPhi = i < recM2_SinPhi.size() ? recM2_SinPhi.get(i) : 0.0;
            double m2CosPhi = i < recM2_CosPhi.size() ? recM2_CosPhi.get(i) : 1.0;
            double m2FitA = i < recM2_FitA.size() ? recM2_FitA.get(i) : 0.0;
            double m2FitB = i < recM2_FitB.size() ? recM2_FitB.get(i) : 0.0;
            double m2IbiCurrent = i < recM2_IbiCurrent.size() ? recM2_IbiCurrent.get(i) : 0.0;
            double m2SmoothedIbi = i < recM2_SmoothedIbi.size() ? recM2_SmoothedIbi.get(i) : 0.0;
            int m2UsedSmoothedIbi = i < recM2_UsedSmoothedIbi.size() ? recM2_UsedSmoothedIbi.get(i) : 0;
            double m2AUsed = i < recM2_A_Used.size() ? recM2_A_Used.get(i) : m2A;
            double m2HrUsed = i < recM2_HR_Used.size() ? recM2_HR_Used.get(i) : m2Hr;
            double m2V2pUsed = i < recM2_V2P_relTTP_Used.size() ? recM2_V2P_relTTP_Used.get(i) : m2V2p;
            double m2P2vUsed = i < recM2_P2V_relTTP_Used.size() ? recM2_P2V_relTTP_Used.get(i) : m2P2v;
            double m2EUsed = i < recM2_E_Used.size() ? recM2_E_Used.get(i) : m2E;
            double m2StiffnessUsed = i < recM2_Stiffness_Used.size() ? recM2_Stiffness_Used.get(i) : m2Stiffness;
            int m2BeatSampleCount = i < recM2_BeatSampleCount.size() ? recM2_BeatSampleCount.get(i) : 0;
            double m2BeatMin = i < recM2_BeatMin.size() ? recM2_BeatMin.get(i) : 0.0;
            double m2BeatMax = i < recM2_BeatMax.size() ? recM2_BeatMax.get(i) : 0.0;
            double m2BeatRange = i < recM2_BeatRange.size() ? recM2_BeatRange.get(i) : 0.0;
            double m2BeatStd = i < recM2_BeatStd.size() ? recM2_BeatStd.get(i) : 0.0;
            double m2SystoleRatio = i < recM2_SystoleRatio.size() ? recM2_SystoleRatio.get(i) : 0.0;
            double m2DiastoleRatio = i < recM2_DiastoleRatio.size() ? recM2_DiastoleRatio.get(i) : 0.0;
            double m2SbpRaw = i < recM2_SBP_Raw.size() ? recM2_SBP_Raw.get(i) : 0.0;
            double m2DbpRaw = i < recM2_DBP_Raw.size() ? recM2_DBP_Raw.get(i) : 0.0;
            double m2SbpBase = i < recM2_SBP_Base.size() ? recM2_SBP_Base.get(i) : 0.0;
            double m2DbpBase = i < recM2_DBP_Base.size() ? recM2_DBP_Base.get(i) : 0.0;
            double m2SbpCorrection = i < recM2_SBP_Correction.size() ? recM2_SBP_Correction.get(i) : 0.0;
            double m2DbpCorrection = i < recM2_DBP_Correction.size() ? recM2_DBP_Correction.get(i) : 0.0;
            double m2SbpAttemptFinal = i < recM2_SBP_AttemptFinal.size() ? recM2_SBP_AttemptFinal.get(i) : 0.0;
            double m2DbpAttemptFinal = i < recM2_DBP_AttemptFinal.size() ? recM2_DBP_AttemptFinal.get(i) : 0.0;
            int m2ConstraintApplied = i < recM2_ConstraintApplied.size() ? recM2_ConstraintApplied.get(i) : 0;
            int m2ClampApplied = i < recM2_ClampApplied.size() ? recM2_ClampApplied.get(i) : 0;
            int m2FeatureClampApplied = i < recM2_FeatureClampApplied.size() ? recM2_FeatureClampApplied.get(i) : 0;
            int m2OutputValid = i < recM2_OutputValid.size() ? recM2_OutputValid.get(i) : 0;
            String m2FeatureClampReason = i < recM2_FeatureClampReason.size() ? recM2_FeatureClampReason.get(i) : "missing";
            String m2RejectReason = i < recM2_RejectReason.size() ? recM2_RejectReason.get(i) : "missing";
            double[] m2Features = new double[] { m2AUsed, m2HrUsed, m2V2pUsed, m2P2vUsed, m2StiffnessUsed, m2EUsed };
            double[] m2SbpTerms = computeLinearTerms(m2SbpCoefficients[0], Arrays.copyOfRange(m2SbpCoefficients, 1, m2SbpCoefficients.length), m2Features);
            double[] m2DbpTerms = computeLinearTerms(m2DbpCoefficients[0], Arrays.copyOfRange(m2DbpCoefficients, 1, m2DbpCoefficients.length), m2Features);
            SinBPDistortionComparison.VariantResult m2EOnlyVariant =
                    SinBPDistortionComparison.estimateEOnly(m2A, m2Hr, m2V2p, m2P2v, m2E);
            SinBPDistortionComparison.VariantResult m2E2Variant =
                    SinBPDistortionComparison.estimateE2(m2A, m2Hr, m2V2p, m2P2v, m2E);
            SinBPDistortionComparison.VariantResult m2LocalAVariant =
                    SinBPDistortionComparison.estimateLocalA(m2BeatRange, m2Hr, m2V2p, m2P2v, m2E);

            double m3A = i < recM3_A.size() ? recM3_A.get(i) : 0.0;
            double m3Hr = i < recM3_HR.size() ? recM3_HR.get(i) : 0.0;
            double m3Mean = i < recM3_Mean.size() ? recM3_Mean.get(i) : 0.0;
            double m3Phi = i < recM3_Phi.size() ? recM3_Phi.get(i) : 0.0;
            double m3Sbp = i < recM3_SBP.size() ? recM3_SBP.get(i) : 0.0;
            double m3Dbp = i < recM3_DBP.size() ? recM3_DBP.get(i) : 0.0;
            double m3SinPhi = i < recM3_SinPhi.size() ? recM3_SinPhi.get(i) : 0.0;
            double m3CosPhi = i < recM3_CosPhi.size() ? recM3_CosPhi.get(i) : 1.0;
            double m3FitA = i < recM3_FitA.size() ? recM3_FitA.get(i) : 0.0;
            double m3FitB = i < recM3_FitB.size() ? recM3_FitB.get(i) : 0.0;
            double m3FitRMSE = i < recM3_FitRMSE.size() ? recM3_FitRMSE.get(i) : 0.0;
            double m3IbiCurrent = i < recM3_IbiCurrent.size() ? recM3_IbiCurrent.get(i) : 0.0;
            double m3SmoothedIbi = i < recM3_SmoothedIbi.size() ? recM3_SmoothedIbi.get(i) : 0.0;
            int m3UsedSmoothedIbi = i < recM3_UsedSmoothedIbi.size() ? recM3_UsedSmoothedIbi.get(i) : 0;
            double m3AUsed = i < recM3_A_Used.size() ? recM3_A_Used.get(i) : m3A;
            double m3HrUsed = i < recM3_HR_Used.size() ? recM3_HR_Used.get(i) : m3Hr;
            double m3MeanUsed = i < recM3_Mean_Used.size() ? recM3_Mean_Used.get(i) : m3Mean;
            double m3SinPhiUsed = i < recM3_SinPhi_Used.size() ? recM3_SinPhi_Used.get(i) : m3SinPhi;
            double m3CosPhiUsed = i < recM3_CosPhi_Used.size() ? recM3_CosPhi_Used.get(i) : m3CosPhi;
            int m3BeatSampleCount = i < recM3_BeatSampleCount.size() ? recM3_BeatSampleCount.get(i) : 0;
            double m3BeatMin = i < recM3_BeatMin.size() ? recM3_BeatMin.get(i) : 0.0;
            double m3BeatMax = i < recM3_BeatMax.size() ? recM3_BeatMax.get(i) : 0.0;
            double m3BeatRange = i < recM3_BeatRange.size() ? recM3_BeatRange.get(i) : 0.0;
            double m3BeatStd = i < recM3_BeatStd.size() ? recM3_BeatStd.get(i) : 0.0;
            double m3SystoleRatio = i < recM3_SystoleRatio.size() ? recM3_SystoleRatio.get(i) : 0.0;
            double m3DiastoleRatio = i < recM3_DiastoleRatio.size() ? recM3_DiastoleRatio.get(i) : 0.0;
            double m3SbpRaw = i < recM3_SBP_Raw.size() ? recM3_SBP_Raw.get(i) : 0.0;
            double m3DbpRaw = i < recM3_DBP_Raw.size() ? recM3_DBP_Raw.get(i) : 0.0;
            double m3SbpAttemptFinal = i < recM3_SBP_AttemptFinal.size() ? recM3_SBP_AttemptFinal.get(i) : 0.0;
            double m3DbpAttemptFinal = i < recM3_DBP_AttemptFinal.size() ? recM3_DBP_AttemptFinal.get(i) : 0.0;
            int m3ConstraintApplied = i < recM3_ConstraintApplied.size() ? recM3_ConstraintApplied.get(i) : 0;
            int m3ClampApplied = i < recM3_ClampApplied.size() ? recM3_ClampApplied.get(i) : 0;
            int m3FeatureClampApplied = i < recM3_FeatureClampApplied.size() ? recM3_FeatureClampApplied.get(i) : 0;
            int m3OutputValid = i < recM3_OutputValid.size() ? recM3_OutputValid.get(i) : 0;
            String m3FeatureClampReason = i < recM3_FeatureClampReason.size() ? recM3_FeatureClampReason.get(i) : "missing";
            String m3RejectReason = i < recM3_RejectReason.size() ? recM3_RejectReason.get(i) : "missing";
            double[] m3Features = new double[] { m3AUsed, m3HrUsed, m3MeanUsed, m3SinPhiUsed, m3CosPhiUsed };
            double[] m3SbpTerms = computeLinearTerms(m3SbpCoefficients[0], Arrays.copyOfRange(m3SbpCoefficients, 1, m3SbpCoefficients.length), m3Features);
            double[] m3DbpTerms = computeLinearTerms(m3DbpCoefficients[0], Arrays.copyOfRange(m3DbpCoefficients, 1, m3DbpCoefficients.length), m3Features);

            csvContent.append(sessionId).append(", ")
                    .append(subjectId).append(", ")
                    .append(sessionNumber).append(", ")
                    .append(i < recModeIndex.size() ? recModeIndex.get(i) : activeMode).append(", ")
                    .append(i < recBeatIndex.size() ? recBeatIndex.get(i) : (i + 1)).append(", ")
                    .append(timestampMs).append(", ")
                    .append(timestampMs).append(", ")
                    .append(wallTimeIso).append(", ")
                    .append(String.format(Locale.getDefault(), "%.3f", elapsedSeconds)).append(", ")
                    .append(appVersion).append(", ")
                    .append(coefficientVersion).append(", ")
                    .append(i < recIso.size() ? recIso.get(i) : currentISO).append(", ")
                    .append(i < recExposureTime.size() ? recExposureTime.get(i) : currentExposureTime).append(", ")
                    .append(i < recWhiteBalanceMode.size() ? recWhiteBalanceMode.get(i) : currentWhiteBalanceMode).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recFocusDistance.size() ? recFocusDistance.get(i) : (double) currentFocusDistance)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recFNumber.size() ? recFNumber.get(i) : (double) currentFNumber)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recAperture.size() ? recAperture.get(i) : (double) currentAperture)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recSensorSensitivity.size() ? recSensorSensitivity.get(i) : (double) currentSensorSensitivity)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", i < recColorTemperature.size() ? recColorTemperature.get(i) : (double) currentColorTemperature)).append(", ")
                    .append(i < recFps.size() ? recFps.get(i) : DEFAULT_FPS).append(", ")
                    .append(i < recIsValidBeat.size() ? recIsValidBeat.get(i) : 1).append(", ")
                    .append(i < recArtifactFlag.size() ? recArtifactFlag.get(i) : 0).append(", ")
                    .append("").append(", ")  // ref_SBP (連続血圧計の参照値、後で追加)
                    .append("").append(", ")  // ref_DBP (連続血圧計の参照値、後で追加)
                    // Method1 (RealTimeBP)
                    .append(String.format(Locale.getDefault(), "%.4f", m1A)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1Hr)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1V2p)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1P2v)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m1Sbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m1Dbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1IbiInput)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1SmoothedIbi)).append(", ")
                    .append(m1UsedSmoothedIbi).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1AUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1HrUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1V2pUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m1P2vUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m1SbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m1DbpRaw)).append(", ")
                    .append(m1ClampApplied).append(", ")
                    .append(m1FeatureClampApplied).append(", ")
                    .append(m1OutputValid).append(", ")
                    .append(sanitizeCsvText(m1FeatureClampReason)).append(", ")
                    .append(sanitizeCsvText(m1RejectReason)).append(", ")
                    .append(formatCoefficients(m1SbpCoefficients)).append(", ")
                    .append(formatCoefficients(m1DbpCoefficients)).append(", ")
                    .append(formatValues(m1SbpTerms)).append(", ")
                    .append(formatValues(m1DbpTerms)).append(", ")
                    // Method2
                    .append(String.format(Locale.getDefault(), "%.4f", m2A)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Hr)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2V2p)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2P2v)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2E)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Stiffness)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2Sbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2Dbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Mean)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2Phi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SinPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2CosPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2FitA)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2FitB)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2IbiCurrent)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SmoothedIbi)).append(", ")
                    .append(m2UsedSmoothedIbi).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2AUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2HrUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2V2pUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2P2vUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2EUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2StiffnessUsed)).append(", ")
                    .append(m2BeatSampleCount).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatMin)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatMax)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatRange)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2BeatStd)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2SystoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m2DiastoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpBase)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpBase)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpCorrection)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpCorrection)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2SbpAttemptFinal)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m2DbpAttemptFinal)).append(", ")
                    .append(m2ConstraintApplied).append(", ")
                    .append(m2ClampApplied).append(", ")
                    .append(m2FeatureClampApplied).append(", ")
                    .append(m2OutputValid).append(", ")
                    .append(sanitizeCsvText(m2FeatureClampReason)).append(", ")
                    .append(sanitizeCsvText(m2RejectReason)).append(", ")
                    .append(formatCoefficients(m2SbpCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpCoefficients)).append(", ")
                    .append(formatCoefficients(m2SbpBaseCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpBaseCoefficients)).append(", ")
                    .append(formatCoefficients(m2SbpCorrectionCoefficients)).append(", ")
                    .append(formatCoefficients(m2DbpCorrectionCoefficients)).append(", ")
                    .append(formatValues(m2SbpTerms)).append(", ")
                    .append(formatValues(m2DbpTerms)).append(", ");
            appendVariantValues(csvContent, m2EOnlyVariant);
            csvContent.append(", ");
            appendVariantValues(csvContent, m2E2Variant);
            csvContent.append(", ");
            appendVariantValues(csvContent, m2LocalAVariant);
            csvContent.append(", ")
                    // Method3 (SinBP_M)
                    .append(String.format(Locale.getDefault(), "%.4f", m3A)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3Hr)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3Mean)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3Phi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3Sbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3Dbp)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3SinPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3CosPhi)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3FitA)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3FitB)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3FitRMSE)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3IbiCurrent)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3SmoothedIbi)).append(", ")
                    .append(m3UsedSmoothedIbi).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3AUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3HrUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3MeanUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3SinPhiUsed)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3CosPhiUsed)).append(", ")
                    .append(m3BeatSampleCount).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3BeatMin)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3BeatMax)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3BeatRange)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3BeatStd)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3SystoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.4f", m3DiastoleRatio)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3SbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3DbpRaw)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3SbpAttemptFinal)).append(", ")
                    .append(String.format(Locale.getDefault(), "%.2f", m3DbpAttemptFinal)).append(", ")
                    .append(m3ConstraintApplied).append(", ")
                    .append(m3ClampApplied).append(", ")
                    .append(m3FeatureClampApplied).append(", ")
                    .append(m3OutputValid).append(", ")
                    .append(sanitizeCsvText(m3FeatureClampReason)).append(", ")
                    .append(sanitizeCsvText(m3RejectReason)).append(", ")
                    .append(formatCoefficients(m3SbpCoefficients)).append(", ")
                    .append(formatCoefficients(m3DbpCoefficients)).append(", ")
                    .append(formatValues(m3SbpTerms)).append(", ")
                    .append(formatValues(m3DbpTerms))
                    .append("\n");
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write(csvContent.toString());
            if (showToast) {
                ui.post(() ->
                        Toast.makeText(ctx, "学習用データ 保存完了", Toast.LENGTH_SHORT).show()
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (showToast) {
                ui.post(() ->
                        Toast.makeText(ctx, "学習用データ 保存失敗", Toast.LENGTH_SHORT).show()
                );
            }
        }
        
        // PCにも保存（mode-1の場合）
        saveToPCIfConnected(name, name + "_Training_Data.csv", csvContent.toString(), isMode1);
        }
    }

    private String formatCoefficients(double[] coefficients) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < coefficients.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.US, "%.10f", coefficients[i]));
        }
        return builder.toString();
    }

    private String formatValues(double[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.US, "%.10f", values[i]));
        }
        return builder.toString();
    }

    private void appendVariantHeader(StringBuilder csvContent, String prefix, String[] labels) {
        List<String> columns = new ArrayList<>();
        columns.add(prefix + "_SBP");
        columns.add(prefix + "_DBP");
        columns.add(prefix + "_SBP_raw");
        columns.add(prefix + "_DBP_raw");
        columns.add(prefix + "_SBP_base");
        columns.add(prefix + "_DBP_base");
        columns.add(prefix + "_SBP_correction");
        columns.add(prefix + "_DBP_correction");
        columns.add(prefix + "_A_used");
        columns.add(prefix + "_E_used");
        columns.add(prefix + "_Stiffness_used");
        columns.add(prefix + "_constraint_applied");
        columns.add(prefix + "_clamp_applied");
        columns.add(prefix + "_feature_clamp_applied");
        columns.add(prefix + "_output_valid");
        columns.add(prefix + "_feature_clamp_reason");
        columns.add(prefix + "_reject_reason");
        for (String label : labels) {
            columns.add(prefix + "_SBP_coef_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_DBP_coef_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_SBP_term_" + label);
        }
        for (String label : labels) {
            columns.add(prefix + "_DBP_term_" + label);
        }
        csvContent.append(String.join(", ", columns));
    }

    private void appendVariantValues(
            StringBuilder csvContent,
            SinBPDistortionComparison.VariantResult variant) {
        List<String> values = new ArrayList<>();
        values.add(String.format(Locale.getDefault(), "%.2f", variant.sbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.dbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.rawSbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.rawDbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.baseSbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.baseDbp));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.sbpCorrection));
        values.add(String.format(Locale.getDefault(), "%.2f", variant.dbpCorrection));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.amplitudeUsed));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.distortionUsed));
        values.add(String.format(Locale.getDefault(), "%.4f", variant.stiffnessUsed));
        values.add(String.valueOf(variant.constraintApplied));
        values.add(String.valueOf(variant.clampApplied));
        values.add(String.valueOf(variant.featureClampApplied));
        values.add(String.valueOf(variant.outputValid));
        values.add(sanitizeCsvText(normalizeRejectReason(variant.featureClampReason)));
        values.add(sanitizeCsvText(normalizeRejectReason(variant.rejectReason)));
        values.add(formatCoefficients(variant.sbpCoefficients));
        values.add(formatCoefficients(variant.dbpCoefficients));
        values.add(formatValues(variant.sbpTerms));
        values.add(formatValues(variant.dbpTerms));
        csvContent.append(String.join(", ", values));
    }

    private double[] computeLinearTerms(double intercept, double[] coefficients, double[] features) {
        double[] terms = new double[coefficients.length + 1];
        terms[0] = intercept;
        for (int i = 0; i < coefficients.length && i < features.length; i++) {
            terms[i + 1] = coefficients[i] * features[i];
        }
        return terms;
    }

    private String normalizeRejectReason(String reason) {
        if (reason == null) {
            return "missing";
        }
        String trimmed = reason.trim();
        return trimmed.isEmpty() ? "ok" : trimmed;
    }

    private String sanitizeCsvText(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replace(",", "_").replace("\n", "_").replace("\r", "_");
        return sanitized;
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
