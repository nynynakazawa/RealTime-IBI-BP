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
    
    // 拍ごとのデータ範囲を追跡（rPPGとSIN近似を1対1で対応させるため）
    private long lastIdealEndTime = 0;  // 前回の理想曲線の終了時刻（新しい拍の検出用）
    private float lastIdealValue = Float.NaN;  // 直近の理想曲線値（NaN維持防止用）
    private final List<Float> lastIdealWaveform = new ArrayList<>();  // 直近の理想曲線波形
    private int lastIdealWaveformCursor = 0;
    private boolean idealCurveNeedsRefresh = false;  // 理想曲線のデータセットを再設定する必要があるか
    
    // Sin波の位相オフセット（遅延補正用、-1.0～1.0の範囲、正の値で前方シフト）
    private double sinPhaseOffset = 0.14;  // デフォルトは0.12（周期の12%前方シフトで遅延補正）

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
    // Method2 (SinBP) 特徴量
    private final List<Double> recM2_A = new ArrayList<>();
    private final List<Double> recM2_HR = new ArrayList<>();
    private final List<Double> recM2_V2P_relTTP = new ArrayList<>();
    private final List<Double> recM2_P2V_relTTP = new ArrayList<>();
    private final List<Double> recM2_Stiffness = new ArrayList<>();
    private final List<Double> recM2_E = new ArrayList<>();
    private final List<Double> recM2_SBP = new ArrayList<>();
    private final List<Double> recM2_DBP = new ArrayList<>();
    // Method3 (SinBP_M) 特徴量
    private final List<Double> recM3_A = new ArrayList<>();
    private final List<Double> recM3_HR = new ArrayList<>();
    private final List<Double> recM3_Mean = new ArrayList<>();
    private final List<Double> recM3_Phi = new ArrayList<>();
    private final List<Double> recM3_SBP = new ArrayList<>();
    private final List<Double> recM3_DBP = new ArrayList<>();

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
                            
                            // Method1 (RealtimeBP) 特徴量
                            if (bpEstimator != null) {
                                recM1_A.add(bpEstimator.getLastAmplitude());
                                recM1_HR.add(bpEstimator.getLastValidHr());
                                recM1_V2P_relTTP.add(bpEstimator.getLastValleyToPeakRelTTP());
                                recM1_P2V_relTTP.add(bpEstimator.getLastPeakToValleyRelTTP());
                                recM1_SBP.add(bpEstimator.getLastSbp());
                                recM1_DBP.add(bpEstimator.getLastDbp());
                            } else {
                                recM1_A.add(0.0);
                                recM1_HR.add(0.0);
                                recM1_V2P_relTTP.add(0.0);
                                recM1_P2V_relTTP.add(0.0);
                                recM1_SBP.add(0.0);
                                recM1_DBP.add(0.0);
                            }
                            
                            // Method2 (SinBP_D) 特徴量
                            if (sinBPDistortion != null) {
                                recM2_A.add(sinBPDistortion.getCurrentAmplitude());
                                recM2_HR.add(sinBPDistortion.getCurrentHR());
                                recM2_V2P_relTTP.add(sinBPDistortion.getCurrentValleyToPeakRelTTP());
                                recM2_P2V_relTTP.add(sinBPDistortion.getCurrentPeakToValleyRelTTP());
                                recM2_Stiffness.add(sinBPDistortion.getCurrentStiffness());
                                recM2_E.add(sinBPDistortion.getCurrentDistortion());
                                recM2_SBP.add(sinBPDistortion.getLastSinSBP());
                                recM2_DBP.add(sinBPDistortion.getLastSinDBP());
                            } else {
                                recM2_A.add(0.0);
                                recM2_HR.add(0.0);
                                recM2_V2P_relTTP.add(0.0);
                                recM2_P2V_relTTP.add(0.0);
                                recM2_Stiffness.add(0.0);
                                recM2_E.add(0.0);
                                recM2_SBP.add(0.0);
                                recM2_DBP.add(0.0);
                            }
                            
                            // Method3 (SinBP_M) 特徴量
                            if (sinBPModel != null) {
                                recM3_A.add(sinBPModel.getCurrentAmplitude());
                                recM3_HR.add(sinBPModel.getCurrentHR());
                                recM3_Mean.add(sinBPModel.getCurrentMean());
                                recM3_Phi.add(sinBPModel.getCurrentPhase());
                                recM3_SBP.add(sinBPModel.getLastSinSBP());
                                recM3_DBP.add(sinBPModel.getLastSinDBP());
                            } else {
                                recM3_A.add(0.0);
                                recM3_HR.add(0.0);
                                recM3_Mean.add(0.0);
                                recM3_Phi.add(0.0);
                                recM3_SBP.add(0.0);
                                recM3_DBP.add(0.0);
                            }
                        }

                        lp.calculateSmoothedValueRealTime(
                                r.getIbi(), r.getBpmSd());

                        double smI = lp.getLastSmoothedIbi();
                        double smB = (60_000) / smI;

                        recSmIbi.add(smI);
                        recSmBpm.add(smB);
                        
                        // Sin波データの記録
                        long currentTime = System.currentTimeMillis();
                        if (sinBPDistortion != null && sinBPDistortion.hasIdealCurve()) {
                            double sinWaveValue = sinBPDistortion.getIdealCurveValue(currentTime);
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
        sinPhaseOffset = 0.14;  // 位相オフセットをデフォルト値にリセット
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
        
        // 学習用データのクリア
        recTrainingTs.clear();
        recM1_A.clear();
        recM1_HR.clear();
        recM1_V2P_relTTP.clear();
        recM1_P2V_relTTP.clear();
        recM1_SBP.clear();
        recM1_DBP.clear();
        recM2_A.clear();
        recM2_HR.clear();
        recM2_V2P_relTTP.clear();
        recM2_P2V_relTTP.clear();
        recM2_Stiffness.clear();
        recM2_E.clear();
        recM2_SBP.clear();
        recM2_DBP.clear();
        recM3_A.clear();
        recM3_HR.clear();
        recM3_Mean.clear();
        recM3_Phi.clear();
        recM3_SBP.clear();
        recM3_DBP.clear();
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

    // ===== 学習用CSV保存 =====
    public void saveTrainingDataToCsv(String name) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, name + "_Training_Data.csv");

        // 学習用データが空の場合はトースト表示して終了
        if (recTrainingTs.isEmpty()) {
            ui.post(() ->
                    Toast.makeText(ctx, "記録された学習用データがありません", Toast.LENGTH_SHORT).show()
            );
            return;
        }

        try (FileWriter writer = new FileWriter(csvFile)) {
            // ヘッダー行
            writer.append("timestamp, subject_id, ref_SBP, ref_DBP, ")
                    .append("M1_A, M1_HR, M1_V2P_relTTP, M1_P2V_relTTP, M1_SBP, M1_DBP, ")
                    .append("M2_A, M2_HR, M2_V2P_relTTP, M2_P2V_relTTP, M2_Stiffness, M2_E, M2_SBP, M2_DBP, ")
                    .append("M3_A, M3_HR, M3_Mean, M3_Phi, M3_SBP, M3_DBP, ")
                    .append("Timestamp_Formatted\n");

            // 記録データを CSV に書き出し
            int maxSize = recTrainingTs.size();
            for (int i = 0; i < maxSize; i++) {
                String ts = sdf.format(new Date(recTrainingTs.get(i)));
                
                writer.append(String.format(Locale.getDefault(), "%d", recTrainingTs.get(i))).append(", ")
                        .append("subject_placeholder").append(", ")  // subject_id (後で追加)
                        .append("").append(", ")  // ref_SBP (連続血圧計の参照値、後で追加)
                        .append("").append(", ")  // ref_DBP (連続血圧計の参照値、後で追加)
                        // Method1 (RealTimeBP)
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM1_A.size() ? recM1_A.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM1_HR.size() ? recM1_HR.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM1_V2P_relTTP.size() ? recM1_V2P_relTTP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM1_P2V_relTTP.size() ? recM1_P2V_relTTP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM1_SBP.size() ? recM1_SBP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM1_DBP.size() ? recM1_DBP.get(i) : 0.0)).append(", ")
                        // Method2
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_A.size() ? recM2_A.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_HR.size() ? recM2_HR.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_V2P_relTTP.size() ? recM2_V2P_relTTP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_P2V_relTTP.size() ? recM2_P2V_relTTP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_Stiffness.size() ? recM2_Stiffness.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM2_E.size() ? recM2_E.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM2_SBP.size() ? recM2_SBP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM2_DBP.size() ? recM2_DBP.get(i) : 0.0)).append(", ")
                        // Method3 (SinBP_M)
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM3_A.size() ? recM3_A.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM3_HR.size() ? recM3_HR.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM3_Mean.size() ? recM3_Mean.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.4f", i < recM3_Phi.size() ? recM3_Phi.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM3_SBP.size() ? recM3_SBP.get(i) : 0.0)).append(", ")
                        .append(String.format(Locale.getDefault(), "%.2f", i < recM3_DBP.size() ? recM3_DBP.get(i) : 0.0)).append(", ")
                        .append(ts)
                        .append("\n");
            }

            ui.post(() ->
                    Toast.makeText(ctx, "学習用データ 保存完了", Toast.LENGTH_SHORT).show()
            );
        } catch (IOException e) {
            e.printStackTrace();
            ui.post(() ->
                    Toast.makeText(ctx, "学習用データ 保存失敗", Toast.LENGTH_SHORT).show()
            );
        }
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











