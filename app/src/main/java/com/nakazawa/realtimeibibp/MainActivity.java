// ─────────────────── MainActivity.java ───────────────────
package com.nakazawa.realtimeibibp;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import com.github.mikephil.charting.charts.LineChart;
import java.text.SimpleDateFormat;
import java.util.*;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Looper;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity
        implements ModeSelectionFragment.OnModeSelectedListener {
    public static final String ACTION_START_AUTOMATED_SESSION =
            "com.nakazawa.realtimeibibp.action.START_AUTOMATED_SESSION";
    public static final String ACTION_STOP_AUTOMATED_SESSION =
            "com.nakazawa.realtimeibibp.action.STOP_AUTOMATED_SESSION";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_SUBJECT_ID = "subject_id";
    public static final String EXTRA_SESSION_NUMBER = "session_number";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_AUTOMATED = "automated";
    public static final String EXTRA_TEST_FORCE_PHASES = "test_force_phases";
    public static final String EXTRA_TEST_PLACEMENT_MS = "test_placement_ms";
    public static final String EXTRA_TEST_REST_MS = "test_rest_ms";
    public static final String EXTRA_TEST_PRESS_MS = "test_press_ms";
    public static final String EXTRA_TEST_RELEASE_MS = "test_release_ms";
    public static final String EXTRA_TEST_AUTO_STOP = "test_auto_stop";
    private static final String APP_VERSION = "1.0";
    private static final String COEFFICIENT_VERSION = "2026-04-10-smartphone-baseline-4session";
    private static final String AUTOMATION_LOG_TAG = "RealtimeAutomation";
    private static final String MEASUREMENT_LOCK_TAG = "MeasurementLock";
    private static final float CUTOUT_FALLBACK_TOP_DP = 14f;
    private static final float STATUS_BAR_LANDING_MARGIN_DP = 2f;
    private static final float LANDING_ZONE_BOTTOM_PADDING_DP = 12f;
    private static final long IMU_LOW_RATE_WARNING_DELAY_MS = 3000L;
    private static final double IMU_LOW_RATE_WARNING_THRESHOLD_HZ = 250.0;

    // ===== 定数 =====
    private static final int REQUEST_WRITE_STORAGE = 112, CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MODE_1 = 1, MODE_2 = 2, MODE_3 = 3, MODE_4 = 4, MODE_5 = 5, MODE_6 = 6, MODE_7=7, MODE_8=8, MODE_9 = 9, MODE_10 = 10, REQ_BP = 201;

    // ===== UI =====
    private Button startButton, resetButton, modeBtn/*, bpMeasureButton*/;
    private Button testModeButton; // TEMP: テスト用 後で削除
    private EditText editTextName; private Spinner spinnerLogic;
    private TextView /*tvBPMax, tvBPMin,*/tvSBPRealtime,tvDBPRealtime, tvSBPAvg, tvDBPAvg;
    private TextView tvSinSBP, tvSinDBP, tvSinSBPAvg, tvSinDBPAvg; // SinBP(D)用
    private TextView tvSinSBPM, tvSinDBPM, tvSinSBPAvgM, tvSinDBPAvgM; // SinBP(M)用
    private TextView tvFNumber, tvISO, tvExposureTime, tvColorTemperature, tvWhiteBalance, tvFocusDistance, tvAperture, tvSensorSensitivity, tvFps;
    private TextView tvYmean, tvUmean, tvVmean, tvContactArea, tvTouchPressure, tvTouchCenter, tvTouchAxis, tvTouchValid, tvPhaseInfo, tvPressTarget, tvImuHz, tvGreenInfo, tvImuVib;
    private FrameLayout touchCaptureOverlay;
    private ScrollView mainScrollView;
    private View landingZoneView, illuminationRingView, signalQualityIndicator, cameraHoleMarker, statusBarTouchBlocker;
    private TextView tvPhaseStatus, tvQualityStatus, tvTargetProgressLabel, tvCurrentProgressLabel;
    private TextView landingZoneLabel, landingInstructionLabel;
    private ProgressBar pressTargetBar, pressCurrentBar;

    // ===== 解析と状態 =====
    private GreenValueAnalyzer analyzer; private RandomStimuliGeneration stimuliGen;
    private int mode = -1; private boolean isRecording; private Handler handler; private Runnable recordTask;
    private long imuWarningPhaseStartMs = -1L;
    private boolean imuLowRateWarningShown = false;
    private boolean testModeActive = false; // TEMP: テスト用 後で削除

    // ===== ランチャー =====
    private ActivityResultLauncher<Intent> bpLauncher;
    private MidiHaptic midiHapticPlayer;

    private RealtimeBP bpEstimator;
    private SinBPDistortion sinBPDistortion; // SinBP(D)推定器
    private SinBPModel sinBPModel; // SinBP(M)推定器

    // ===== 強化学習関連 =====
    private IBIControlEnv environment;
    private DQNAgent agent1, agent2, agent3, agent4;
    private boolean isRLMode = false;

    // ===== ランダム刺激関連 =====
    private RandomStimuliGeneration randomStimuliGen;
    private Handler randomStimuliHandler = new Handler();
    private Runnable randomStimuliRunnable;
    private boolean isRandomStimuliMode = false;

    private ExecutorService rlExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean persistInProgress = new AtomicBoolean(false);
    private CountDownTimer activeCountDownTimer;
    private String currentOutputBaseName = "";
    private String currentSessionId = "";
    private String currentSubjectId = "";
    private int currentSessionNumber = 0;
    private boolean isAutomatedSession = false;
    private boolean measurementLockActive = false;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private float lastTouchMajor = 0f;
    private float lastTouchMinor = 0f;
    private float lastTouchPressure = 0f;
    
    // ===== 画面輝度制御 =====
    private void setMaxBrightness() {
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = 1.0f; // 最大輝度（0.0-1.0）
        getWindow().setAttributes(layoutParams);
    }

    // ===== onCreate =====
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);
        
        // 画面の輝度を最大に設定
        setMaxBrightness();
        
        requestCameraPermission();
        initUi();
        initAnalyzer();
        initRealtimeBP();
        initSinBP(); // SinBP(D)初期化を追加
        initSinBPModel(); // SinBP(M)初期化を追加
        analyzer.setBpEstimator(bpEstimator);
        analyzer.setSinBPDistortion(sinBPDistortion); // SinBP(D)もAnalyzerに渡す
        analyzer.setSinBPModel(sinBPModel); // SinBP(M)もAnalyzerに渡す
        
        // Camera X API 色温度関連情報のコールバックを設定
        analyzer.setCameraInfoCallback((fNumber, iso, exposureTime, colorTemperature, whiteBalanceMode, focusDistance, aperture, sensorSensitivity, awbGainText, fps) -> {
            runOnUiThread(() -> {
                tvFNumber.setText(String.format(Locale.getDefault(), "F-Number: %s", formatFloatOrMissing(fNumber, "%.1f")));
                tvISO.setText(String.format(Locale.getDefault(), "ISO: %s", formatIntOrMissing(iso)));
                tvExposureTime.setText(String.format(Locale.getDefault(), "Exposure(ns): %s", formatLongOrMissing(exposureTime)));
                tvColorTemperature.setText(String.format(Locale.getDefault(), "AWB R/B gain: %s", awbGainText));
                tvWhiteBalance.setText(String.format(Locale.getDefault(), "WB Mode: %s", formatIntOrMissing(whiteBalanceMode)));
                tvFocusDistance.setText(String.format(Locale.getDefault(), "Focus: %s", formatFloatOrMissing(focusDistance, "%.2f")));
                tvAperture.setText(String.format(Locale.getDefault(), "Aperture: %s", formatFloatOrMissing(aperture, "%.1f")));
                tvSensorSensitivity.setText(String.format(Locale.getDefault(), "Sensor sensitivity: %s", formatFloatOrMissing(sensorSensitivity, "%.0f")));
                tvFps.setText(String.format(Locale.getDefault(), "FPS: %.1f", fps));
                
                // ISO値をGreenValueAnalyzerに渡してCSV記録を制御
                if (iso >= 0) {
                    analyzer.updateISO(iso);
                }
                
                // Logic1とLogic2にもISO値を渡す
                LogicProcessor logic1 = analyzer.getLogicProcessor("Logic1");
                LogicProcessor logic2 = analyzer.getLogicProcessor("Logic2");
                if (iso >= 0 && logic1 instanceof BaseLogic) {
                    ((BaseLogic) logic1).updateISO(iso);
                }
                if (iso >= 0 && logic2 instanceof BaseLogic) {
                    ((BaseLogic) logic2).updateISO(iso);
                }
                
                // SinBPDistortionにもISO値を渡す
                if (iso >= 0 && sinBPDistortion != null) {
                    sinBPDistortion.updateISO(iso);
                }
                if (iso >= 0 && sinBPModel != null) {
                    sinBPModel.updateISO(iso);
                }
            });
        });
        
        handler = new Handler();
        analyzer.startRecording();
        analyzer.stopRecording();
        handleAutomationIntent(getIntent());
    }

    private void configureEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.green_color));
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(layoutParams);
        }
    }

    private void onMeasurementLockRequested() {
        runOnUiThread(this::startMeasurementLockIfNeeded);
    }

    private void onMeasurementLockReleased() {
        runOnUiThread(this::stopMeasurementLockIfNeeded);
    }

    private void startMeasurementLockIfNeeded() {
        if (measurementLockActive) {
            return;
        }
        try {
            startLockTask();
            measurementLockActive = true;
        } catch (Exception e) {
            Log.w(MEASUREMENT_LOCK_TAG, "startLockTask failed", e);
        }
        applyMeasurementImmersiveMode();
    }

    private void stopMeasurementLockIfNeeded() {
        if (measurementLockActive) {
            try {
                stopLockTask();
            } catch (Exception e) {
                Log.w(MEASUREMENT_LOCK_TAG, "stopLockTask failed", e);
            } finally {
                measurementLockActive = false;
            }
        }
        clearMeasurementImmersiveMode();
    }

    private void applyMeasurementImmersiveMode() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        // WHY: 本計測中だけ通知/ナビ領域の誤操作を抑え、終了時は通常表示へ戻す。
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void clearMeasurementImmersiveMode() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) {
            return;
        }
        controller.show(WindowInsetsCompat.Type.systemBars());
    }

    // ===== UI初期化 =====
    private void initUi() {
        modeBtn = findViewById(R.id.show_mode_select_fragment_button);
        startButton = findViewById(R.id.start_button);
        resetButton = findViewById(R.id.reset_button);
        testModeButton = findViewById(R.id.test_mode_button); // TEMP: テスト用 後で削除
        editTextName = findViewById(R.id.editTextName);
        spinnerLogic = findViewById(R.id.spinnerLogicSelection);
//        bpMeasureButton = findViewById(R.id.btn_bp_measure);
//        tvBPMax = findViewById(R.id.tvBPMax);
//        tvBPMin = findViewById(R.id.tvBPMin);
        
        // Camera X API 色温度関連情報のTextViewを初期化
        tvFNumber = findViewById(R.id.tvFNumber);
        tvISO = findViewById(R.id.tvISO);
        tvExposureTime = findViewById(R.id.tvExposureTime);
        tvColorTemperature = findViewById(R.id.tvColorTemperature);
        tvWhiteBalance = findViewById(R.id.tvWhiteBalance);
        tvFocusDistance = findViewById(R.id.tvFocusDistance);
        tvAperture = findViewById(R.id.tvAperture);
        tvSensorSensitivity = findViewById(R.id.tvSensorSensitivity);
        tvFps = findViewById(R.id.tvFps);
        tvYmean = findViewById(R.id.tvYmean);
        tvUmean = findViewById(R.id.tvUmean);
        tvVmean = findViewById(R.id.tvVmean);
        tvContactArea = findViewById(R.id.tvContactArea);
        tvTouchPressure = findViewById(R.id.tvTouchPressure);
        tvTouchCenter = findViewById(R.id.tvTouchCenter);
        tvTouchAxis = findViewById(R.id.tvTouchAxis);
        tvTouchValid = findViewById(R.id.tvTouchValid);
        tvPhaseInfo = findViewById(R.id.tvPhaseInfo);
        tvPressTarget = findViewById(R.id.tvPressTarget);
        tvImuHz = findViewById(R.id.tvImuHz);
        tvGreenInfo = findViewById(R.id.tvGreenInfo);
        tvImuVib = findViewById(R.id.tvImuVib);
        mainScrollView = findViewById(R.id.mainScrollView);
        touchCaptureOverlay = findViewById(R.id.touchCaptureOverlay);
        statusBarTouchBlocker = findViewById(R.id.statusBarTouchBlocker);
        landingZoneView = findViewById(R.id.landingZoneView);
        illuminationRingView = findViewById(R.id.illuminationRingView);
        signalQualityIndicator = findViewById(R.id.signalQualityIndicator);
        cameraHoleMarker = findViewById(R.id.cameraHoleMarker);
        landingZoneLabel = findViewById(R.id.landingZoneLabel);
        landingInstructionLabel = findViewById(R.id.landingInstructionLabel);
        tvPhaseStatus = findViewById(R.id.tvPhaseStatus);
        tvQualityStatus = findViewById(R.id.tvQualityStatus);
        tvTargetProgressLabel = findViewById(R.id.tvTargetProgressLabel);
        tvCurrentProgressLabel = findViewById(R.id.tvCurrentProgressLabel);
        pressTargetBar = findViewById(R.id.pressTargetBar);
        pressCurrentBar = findViewById(R.id.pressCurrentBar);

        String[] logics = {"Logic1","Logic2"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                R.layout.spinner_item,               // 選択中アイテム用
                logics
        );
        adapter.setDropDownViewResource(
                R.layout.spinner_dropdown_item       // ドロップダウンリスト用
        );
        spinnerLogic.setAdapter(adapter);

        spinnerLogic.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                analyzer.setActiveLogic(parent.getItemAtPosition(position).toString());
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                analyzer.setActiveLogic("Logic1");
            }
        });

        /*bpLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> {
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                        Intent d = r.getData();
                        updateBPRange(d.getDoubleExtra("BP_MAX", 0),
                                d.getDoubleExtra("BP_MIN", 0));
                        analyzer.restartCamera();
                    }
                });*/

        modeBtn.setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mode_select_fragment_container,
                            new ModeSelectionFragment())
                    .commit();
            FrameLayout container = findViewById(R.id.mode_select_fragment_container);
            container.setVisibility(View.VISIBLE);
            container.bringToFront();
            container.requestLayout();
            v.setVisibility(View.GONE);
        });

        startButton.setOnClickListener(v -> startRecording());

        resetButton.setOnClickListener(v -> {
            analyzer.reset();
            // 血圧表示もクリア
            tvSBPRealtime.setText("SBP : 0.0");
            tvDBPRealtime.setText("DBP : 0.0");
            tvSBPAvg.setText("SBP(Average) : 0.0");
            tvDBPAvg.setText("DBP(Average) : 0.0");
            tvPhaseStatus.setText("指でカメラを軽く覆ってください（押さない）");
            tvQualityStatus.setText("品質ゲート待機中");
            pressTargetBar.setProgress(0);
            pressCurrentBar.setProgress(0);
        });
        testModeButton.setOnClickListener(v -> toggleTestMode()); // TEMP: テスト用 後で削除
        initOscillometricGuidanceUi();
        /*bpMeasureButton.setOnClickListener(v ->
                bpLauncher.launch(new Intent(this, PressureAnalyze.class))
        );*/
    }

    // ===== Analyzer初期化 =====
    private void initAnalyzer() {
        LineChart c = findViewById(R.id.lineChart);
        analyzer = new GreenValueAnalyzer(
                this, c,
                findViewById(R.id.greenValueTextView),
                findViewById(R.id.ibiTextView),
                findViewById(R.id.NowTextMessage),
                findViewById(R.id.BPMSD),
                findViewById(R.id.HRTextView),
                findViewById(R.id.SmoothedIbiTextView),
                findViewById(R.id.SmoothedHRTextView));
        // WHY: 接触面積px^2は密度で変わるため、Pixel 8基準px^2へ換算してしきい値を端末非依存にする。
        analyzer.setDeviceDensity(getResources().getDisplayMetrics().density);
        analyzer.setOscillometricUiCallback(this::renderOscillometricUiState);
        analyzer.setForcedPhaseCompletionListener(new GreenValueAnalyzer.ForcedPhaseCompletionListener() {
            @Override
            public void onForcedPhaseTimelineCompleted() {
                runOnUiThread(() -> {
                    if (isRecording) {
                        Log.i(AUTOMATION_LOG_TAG, "phase timeline completed; stopping sessionId=" + currentSessionId);
                        stopRecordingAndPersist();
                    }
                });
            }

            @Override
            public void onMeasurementLockRequested() {
                MainActivity.this.onMeasurementLockRequested();
            }

            @Override
            public void onMeasurementLockReleased() {
                MainActivity.this.onMeasurementLockReleased();
            }
        });
    }

    private void initOscillometricGuidanceUi() {
        applyIlluminationGuideColor(Color.parseColor("#2EE6A6"));
        pressTargetBar.setMax(1000);
        pressCurrentBar.setMax(1000);
        pressTargetBar.setProgress(0);
        pressCurrentBar.setProgress(0);
        // WHY: 寒冷昇圧プロトコルでは押し込み誘導を出さず、記録列だけ互換性のため残す。
        tvTargetProgressLabel.setVisibility(View.GONE);
        tvCurrentProgressLabel.setVisibility(View.GONE);
        pressTargetBar.setVisibility(View.GONE);
        pressCurrentBar.setVisibility(View.GONE);
        tvPressTarget.setVisibility(View.GONE);
        tvPhaseStatus.setText("指でカメラを軽く覆ってください（押さない）");
        tvQualityStatus.setText("品質ゲート待機中");

        touchCaptureOverlay.setClickable(false);
        touchCaptureOverlay.setFocusable(false);
        statusBarTouchBlocker.setOnTouchListener((v, event) -> true);
        touchCaptureOverlay.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                positionLandingZoneFromCutout());
        touchCaptureOverlay.setOnApplyWindowInsetsListener((v, insets) -> {
            applySystemBarInsets(insets);
            positionLandingZoneFromCutout();
            return insets;
        });
        touchCaptureOverlay.requestApplyInsets();
        touchCaptureOverlay.post(this::positionLandingZoneFromCutout);
    }

    private void positionLandingZoneFromCutout() {
        if (touchCaptureOverlay == null || landingZoneView == null || illuminationRingView == null
                || cameraHoleMarker == null || statusBarTouchBlocker == null || touchCaptureOverlay.getWidth() <= 0) {
            return;
        }

        WindowInsets insets = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? touchCaptureOverlay.getRootWindowInsets()
                : null;
        Rect cutoutRect = getFrontCameraCutoutRect(insets);
        int[] overlayLocation = new int[2];
        touchCaptureOverlay.getLocationOnScreen(overlayLocation);

        float holeCenterX = touchCaptureOverlay.getWidth() / 2f;
        float holeCenterY = dpToPx(CUTOUT_FALLBACK_TOP_DP);
        if (cutoutRect != null) {
            holeCenterX = cutoutRect.centerX() - overlayLocation[0];
            holeCenterY = cutoutRect.centerY() - overlayLocation[1];
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets != null && insets.getDisplayCutout() != null) {
            holeCenterY = insets.getDisplayCutout().getSafeInsetTop() - overlayLocation[1];
        }

        holeCenterX = clamp(holeCenterX, cameraHoleMarker.getWidth() / 2f,
                touchCaptureOverlay.getWidth() - cameraHoleMarker.getWidth() / 2f);
        int statusTopInset = insets != null ? getStatusTopInset(insets) : statusBarTouchBlocker.getHeight();
        float minTouchableTop = statusTopInset + dpToPx(STATUS_BAR_LANDING_MARGIN_DP);
        float maxTouchableTop = Math.max(minTouchableTop,
                touchCaptureOverlay.getHeight() - landingZoneView.getHeight() - dpToPx(LANDING_ZONE_BOTTOM_PADDING_DP));
        float targetTop = clamp(minTouchableTop, minTouchableTop, maxTouchableTop);
        float targetCenterX = clamp(holeCenterX, landingZoneView.getWidth() / 2f,
                touchCaptureOverlay.getWidth() - landingZoneView.getWidth() / 2f);

        // WHY: ステータスバー内はOSがtouchを握り接触面積を読めないため、計測ゾーンは必ずバー直下のtouch感知域に置く。
        // WHY: 横位置はパンチホールに追従し、指先でカメラを覆いながら指の腹が下側ゾーンへ自然に乗る配置にする。
        placeChild(cameraHoleMarker, holeCenterX - cameraHoleMarker.getWidth() / 2f,
                Math.max(0f, holeCenterY - cameraHoleMarker.getHeight() / 2f));
        placeChild(landingZoneView, targetCenterX - landingZoneView.getWidth() / 2f, targetTop);
        placeChild(illuminationRingView, targetCenterX - illuminationRingView.getWidth() / 2f,
                targetTop);
        placeChild(landingZoneLabel, targetCenterX - landingZoneLabel.getWidth() / 2f,
                targetTop + (landingZoneView.getHeight() - landingZoneLabel.getHeight()) / 2f);
        placeChild(landingInstructionLabel, targetCenterX - landingInstructionLabel.getWidth() / 2f,
                targetTop + landingZoneView.getHeight() + dpToPx(6f));

        updateTouchTargetGeometry();
    }

    private void applySystemBarInsets(WindowInsets insets) {
        if (insets == null) {
            return;
        }
        int statusTopInset = getStatusTopInset(insets);
        int navigationBottomInset = getNavigationBottomInset(insets);

        if (statusBarTouchBlocker != null) {
            ViewGroup.LayoutParams params = statusBarTouchBlocker.getLayoutParams();
            if (params != null && params.height != statusTopInset) {
                params.height = statusTopInset;
                statusBarTouchBlocker.setLayoutParams(params);
            }
        }
        if (mainScrollView != null) {
            // WHY: edge-to-edge時に最下部の情報パネルがジェスチャーバーの下へ潜らないよう、システム領域分だけ余白を足す。
            mainScrollView.setPadding(
                    mainScrollView.getPaddingLeft(),
                    mainScrollView.getPaddingTop(),
                    mainScrollView.getPaddingRight(),
                    navigationBottomInset);
        }
    }

    private int getStatusTopInset(WindowInsets insets) {
        int topInset = insets.getSystemWindowInsetTop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets.getDisplayCutout() != null) {
            topInset = Math.max(topInset, insets.getDisplayCutout().getSafeInsetTop());
        }
        return topInset;
    }

    private int getNavigationBottomInset(WindowInsets insets) {
        return insets.getSystemWindowInsetBottom();
    }

    private Rect getFrontCameraCutoutRect(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || insets == null || insets.getDisplayCutout() == null) {
            return null;
        }
        DisplayCutout cutout = insets.getDisplayCutout();
        Rect bestRect = null;
        int displayCenterX = getResources().getDisplayMetrics().widthPixels / 2;
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect == null || rect.isEmpty()) {
                continue;
            }
            if (bestRect == null
                    || Math.abs(rect.centerX() - displayCenterX) < Math.abs(bestRect.centerX() - displayCenterX)) {
                bestRect = rect;
            }
        }
        return bestRect;
    }

    private void placeChild(View child, float left, float top) {
        if (child == null) {
            return;
        }
        child.setX(left);
        child.setY(top);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateTouchTargetGeometry() {
        if (analyzer == null || touchCaptureOverlay == null || landingZoneView == null || cameraHoleMarker == null) {
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float cameraHoleCenterX = cameraHoleMarker.getX() + cameraHoleMarker.getWidth() / 2f;
        float cameraHoleCenterY = cameraHoleMarker.getY() + cameraHoleMarker.getHeight() / 2f;
        int[] overlayLocation = new int[2];
        int[] landingZoneLocation = new int[2];
        touchCaptureOverlay.getLocationOnScreen(overlayLocation);
        landingZoneView.getLocationOnScreen(landingZoneLocation);
        // WHY: タッチ座標は raw 座標から overlay 原点を引いているため、ゾーン楕円も同じ画面座標基準から変換する。
        float touchTargetCenterX = landingZoneLocation[0] - overlayLocation[0] + landingZoneView.getWidth() / 2f;
        float touchTargetTopY = landingZoneLocation[1] - overlayLocation[1];
        float touchTargetHalfWidth = landingZoneView.getWidth() / 2f;
        float touchTargetFullHeight = landingZoneView.getHeight();
        analyzer.setTouchTargetGeometry(
                metrics.widthPixels,
                metrics.heightPixels,
                touchCaptureOverlay.getWidth(),
                touchCaptureOverlay.getHeight(),
                cameraHoleCenterX,
                cameraHoleCenterY,
                touchTargetCenterX,
                touchTargetTopY,
                touchTargetHalfWidth,
                touchTargetFullHeight,
                (touchTargetTopY + touchTargetFullHeight / 2f) - cameraHoleCenterY);
    }

    private boolean handleTouchOverlayEvent(MotionEvent event) {
        if (analyzer == null || event == null) {
            return false;
        }
        if (isTouchInsideStatusBarBlocker(event)) {
            // WHY: 緑帯はキャプチャ対象外なので、通知シェード誤操作を抑えつつ接触計測にも混ぜない。
            return false;
        }
        int pointerIndex = event.getActionIndex();
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            pointerIndex = 0;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                int activePointerCount = event.getPointerCount();
                boolean pointerUp = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP;
                int measurementCount = pointerUp ? Math.max(0, activePointerCount - 1) : activePointerCount;
                float[] touchXs = new float[measurementCount];
                float[] touchYs = new float[measurementCount];
                float[] touchMajors = new float[measurementCount];
                float[] touchMinors = new float[measurementCount];
                float[] touchSizes = new float[measurementCount];
                float[] touchPressures = new float[measurementCount];
                if (touchCaptureOverlay != null) {
                    int[] overlayLocation = new int[2];
                    touchCaptureOverlay.getLocationOnScreen(overlayLocation);
                    int outIndex = 0;
                    for (int i = 0; i < activePointerCount; i++) {
                        if (pointerUp && i == pointerIndex) {
                            continue;
                        }
                        // WHY: オーバレイを全画面化したため、どの子Viewがタッチ対象でも同じ座標系で接触面積を記録する。
                        touchXs[outIndex] = event.getRawX(i) - overlayLocation[0];
                        touchYs[outIndex] = event.getRawY(i) - overlayLocation[1];
                        touchMajors[outIndex] = event.getTouchMajor(i);
                        touchMinors[outIndex] = event.getTouchMinor(i);
                        touchSizes[outIndex] = event.getSize(i);
                        touchPressures[outIndex] = event.getPressure(i);
                        outIndex++;
                    }
                } else {
                    int outIndex = 0;
                    for (int i = 0; i < activePointerCount; i++) {
                        if (pointerUp && i == pointerIndex) {
                            continue;
                        }
                        touchXs[outIndex] = event.getX(i);
                        touchYs[outIndex] = event.getY(i);
                        touchMajors[outIndex] = event.getTouchMajor(i);
                        touchMinors[outIndex] = event.getTouchMinor(i);
                        touchSizes[outIndex] = event.getSize(i);
                        touchPressures[outIndex] = event.getPressure(i);
                        outIndex++;
                    }
                }
                if (measurementCount > 0) {
                    int representativeIndex = Math.min(pointerIndex, measurementCount - 1);
                    lastTouchX = touchXs[representativeIndex];
                    lastTouchY = touchYs[representativeIndex];
                    lastTouchMajor = touchMajors[representativeIndex];
                    lastTouchMinor = touchMinors[representativeIndex];
                    lastTouchPressure = touchPressures[representativeIndex];
                }
                analyzer.updateTouchMeasurements(
                        touchXs,
                        touchYs,
                        touchMajors,
                        touchMinors,
                        touchSizes,
                        touchPressures,
                        measurementCount,
                        true,
                        event.getEventTime());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                analyzer.updateTouchMeasurements(
                        lastTouchX,
                        lastTouchY,
                        lastTouchMajor,
                        lastTouchMinor,
                        event.getSize(pointerIndex),
                        lastTouchPressure,
                        false,
                        event.getEventTime());
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (isTouchInsideStatusBarBlocker(event)) {
            return true;
        }
        handleTouchOverlayEvent(event);
        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchInsideStatusBarBlocker(MotionEvent event) {
        if (statusBarTouchBlocker == null || statusBarTouchBlocker.getHeight() <= 0) {
            return false;
        }
        int pointerIndex = event.getActionIndex();
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            pointerIndex = 0;
        }
        int[] blockerLocation = new int[2];
        statusBarTouchBlocker.getLocationOnScreen(blockerLocation);
        float rawX = event.getRawX(pointerIndex);
        float rawY = event.getRawY(pointerIndex);
        return rawX >= blockerLocation[0]
                && rawX <= blockerLocation[0] + statusBarTouchBlocker.getWidth()
                && rawY >= blockerLocation[1]
                && rawY <= blockerLocation[1] + statusBarTouchBlocker.getHeight();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    private void renderOscillometricUiState(GreenValueAnalyzer.OscillometricUiState state) {
        runOnUiThread(() -> {
            tvPhaseStatus.setText(formatPhaseInstruction(state));
            tvQualityStatus.setText(formatQualityText(state));
            signalQualityIndicator.setBackgroundTintList(ColorStateList.valueOf(
                    state.qualityPassed ? Color.parseColor("#2EE6A6") : Color.parseColor("#FF7A7A")));
            pressTargetBar.setProgress((int) Math.round(state.pressTarget * 1000.0));
            pressCurrentBar.setProgress((int) Math.round(state.currentPressProgress * 1000.0));
            tvYmean.setText(String.format(Locale.getDefault(), "Y_mean: %.2f", state.yMean));
            tvUmean.setText(String.format(Locale.getDefault(), "U_mean: %.2f", state.uMean));
            tvVmean.setText(String.format(Locale.getDefault(), "V_mean: %.2f", state.vMean));
            // WHY: 1行が長いとSensor Infoの列が崩れるため、P8換算in-zoneとrawを改行で短く分ける。
            tvContactArea.setText(String.format(Locale.getDefault(),
                    "contact_area(P8 px^2)\n  in-zone: %.0f (基準16000/100%%=18000)\n  raw: %.0f px^2",
                    state.inZoneContactArea,
                    state.rawContactArea));
            tvTouchPressure.setText(String.format(Locale.getDefault(), "touch_pressure: %.3f", state.touchPressure));
            tvTouchCenter.setText(String.format(Locale.getDefault(), "touch_cx/cy: %.1f, %.1f", state.touchCx, state.touchCy));
            tvTouchAxis.setText(String.format(Locale.getDefault(), "touch_major/minor: %.1f, %.1f", state.touchMajor, state.touchMinor));
            tvTouchValid.setText(String.format(Locale.getDefault(), "touch_valid: %d", state.touchValid ? 1 : 0));
            tvPhaseInfo.setText(String.format(Locale.getDefault(), "phase: %s (%d)", getPhaseName(state.phase), state.phase));
            tvImuHz.setText(String.format(Locale.getDefault(), "IMU: %.0f Hz", state.imuHz));
            tvGreenInfo.setText(String.format(Locale.getDefault(), "green/dcGreen: %.2f / %.2f", state.greenValue, state.dcGreenValue));
            tvImuVib.setText(String.format(Locale.getDefault(), "IMU振動: %.3f", state.imuVibRms));
            maybeShowImuLowRateWarning(state);
        });
    }

    private void maybeShowImuLowRateWarning(GreenValueAnalyzer.OscillometricUiState state) {
        long nowMs = System.currentTimeMillis();
        if (state.phase < 1) {
            imuWarningPhaseStartMs = -1L;
            imuLowRateWarningShown = false;
            return;
        }
        if (imuWarningPhaseStartMs < 0L) {
            imuWarningPhaseStartMs = nowMs;
        }
        // WHY: Android 12+ではマイクアクセスOFF時にIMUが200Hzへ制限され、VFE確認を実機中に見逃しやすい。
        if (!imuLowRateWarningShown
                && nowMs - imuWarningPhaseStartMs >= IMU_LOW_RATE_WARNING_DELAY_MS
                && state.imuHz > 0.0
                && state.imuHz < IMU_LOW_RATE_WARNING_THRESHOLD_HZ) {
            imuLowRateWarningShown = true;
            Toast.makeText(this, "IMUレート低下: マイクアクセスONと高サンプリング権限を確認", Toast.LENGTH_LONG).show();
        }
    }

    private String formatFloatOrMissing(float value, String pattern) {
        if (value < 0f || Float.isNaN(value)) {
            return "--";
        }
        return String.format(Locale.getDefault(), pattern, value);
    }

    private String formatIntOrMissing(int value) {
        return value < 0 ? "--" : String.format(Locale.getDefault(), "%d", value);
    }

    private String formatLongOrMissing(long value) {
        return value < 0L ? "--" : String.format(Locale.getDefault(), "%d", value);
    }

    private String getPhaseName(int phase) {
        switch (phase) {
            case 0:
                return "placement";
            case 1:
                return "rest";
            case 2:
                return "cold";
            case 3:
                return "recovery";
            default:
                return "unknown";
        }
    }

    private String formatPhaseInstruction(GreenValueAnalyzer.OscillometricUiState state) {
        String remaining = formatRemainingText(state);
        switch (state.phase) {
            case 0:
                return "指でカメラを軽く覆ってください（押さない）\n" + remaining;
            case 1:
                return "安静にしてください（" + remaining + "）";
            case 2:
                return "氷水に手を入れてください（測定する指はそのまま）\n" + remaining;
            case 3:
                return "手を出して安静に（" + remaining + "）";
            default:
                return "指でカメラを軽く覆ってください（押さない）";
        }
    }

    private String formatRemainingText(GreenValueAnalyzer.OscillometricUiState state) {
        if (state.phase == 0) {
            return "品質OKを保ってください（あと" + Math.max(1L, (long) Math.ceil(state.phaseRemainingMs / 1000.0)) + "秒キープ）";
        }
        return "あと" + Math.max(0L, (long) Math.ceil(state.phaseRemainingMs / 1000.0)) + "秒";
    }

    private String formatQualityText(GreenValueAnalyzer.OscillometricUiState state) {
        return String.format(
                Locale.getDefault(),
                "A:%s Exp:%s Pos:%s Touch:%s 面積:%s\n%s",
                formatAmplitudeLabel(state),
                state.exposureStatus,
                formatPositionLabel(state),
                formatTouchLabel(state),
                state.areaStatus,
                formatQualityReasonSummary(state));
    }

    private String formatAmplitudeLabel(GreenValueAnalyzer.OscillometricUiState state) {
        return state.amplitudePassed ? "OK" : "弱い";
    }

    private String formatPositionLabel(GreenValueAnalyzer.OscillometricUiState state) {
        return state.positionPassed ? "OK" : "ずれ";
    }

    private String formatTouchLabel(GreenValueAnalyzer.OscillometricUiState state) {
        return state.touchValid ? "ON" : "OFF";
    }

    private String formatQualityReasonSummary(GreenValueAnalyzer.OscillometricUiState state) {
        if (state.qualityPassed) {
            return "品質OK";
        }
        List<String> reasons = new ArrayList<>();
        if (!state.amplitudePassed) {
            reasons.add("振幅弱い");
        }
        if (!state.exposurePassed) {
            reasons.add(state.exposureStatus);
        }
        if (!state.positionPassed) {
            reasons.add("位置ずれ");
        }
        if (!state.touchValid) {
            reasons.add("Touch OFF");
        }
        if (!state.areaPassed) {
            reasons.add("面積" + state.areaStatus);
        }
        return "NG理由: " + joinReasons(reasons);
    }

    private String joinReasons(List<String> reasons) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                joined.append("・");
            }
            joined.append(reasons.get(i));
        }
        return joined.toString();
    }

    private String formatQualityHint(GreenValueAnalyzer.OscillometricUiState state) {
        if (!state.touchValid) {
            return "下の枠に指の腹を置いてください";
        }
        if (!state.positionPassed) {
            return "下の枠の中心に合わせてください";
        }
        if (!state.areaPassed) {
            return "大きすぎ".equals(state.areaStatus) ? "接触を少し弱めてください" : "もう少し覆ってください";
        }
        if (!state.exposurePassed) {
            return "明るすぎ(指浮き)".equals(state.exposureStatus)
                    ? "カメラに指の腹を乗せてください"
                    : "明るさが安定するよう覆ってください";
        }
        if (!state.amplitudePassed) {
            return "指を動かさず脈波を待ってください";
        }
        return "そのまま保持";
    }

    private void applyIlluminationGuideColor(int color) {
        // 将来の画面駆動マルチ波長はこの1箇所から切り替える。
        illuminationRingView.setBackgroundTintList(ColorStateList.valueOf(color));
        landingZoneView.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2EE6A6")));
    }

    private void toggleTestMode() { // TEMP: テスト用 後で削除
        testModeActive = !testModeActive; // TEMP: テスト用 後で削除
        analyzer.setTestModeActive(testModeActive); // TEMP: テスト用 後で削除
        testModeButton.setText(testModeActive ? "TEST ON" : "TEST"); // TEMP: テスト用 後で削除
        testModeButton.setTextColor(testModeActive ? Color.parseColor("#101F1C") : Color.parseColor("#78CCCC")); // TEMP: テスト用 後で削除
        testModeButton.setBackgroundTintList(ColorStateList.valueOf(testModeActive ? Color.parseColor("#2EE6A6") : Color.TRANSPARENT)); // TEMP: テスト用 後で削除
        Toast.makeText(this, testModeActive ? "TEST: 録画なしでphase確認" : "TEST終了", Toast.LENGTH_SHORT).show(); // TEMP: テスト用 後で削除
    } // TEMP: テスト用 後で削除

    // ===== カメラ許可 =====
    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {

            if (!shouldShowRequestPermissionRationale(
                    android.Manifest.permission.CAMERA)) {
                showPermissionSettingsDialog();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("カメラの許可が必要です")
                        .setMessage("カメラのパーミッションを許可してください")
                        .setPositiveButton("OK",
                                (d, w) ->
                                        requestPermissions(
                                                new String[]{android.Manifest.permission.CAMERA},
                                                CAMERA_PERMISSION_REQUEST_CODE))
                        .setCancelable(false).show();
            }
        }
    }

    private void initRealtimeBP() {
        // 1. インスタンス作成＆View のバインド
        bpEstimator   = new RealtimeBP();
        tvSBPRealtime = findViewById(R.id.tvSBPRealtime);
        tvDBPRealtime = findViewById(R.id.tvDBPRealtime);
        tvSBPAvg      = findViewById(R.id.tvSBPAvg);
        tvDBPAvg      = findViewById(R.id.tvDBPAvg);

        // 2. UI 更新リスナ登録
        bpEstimator.setListener((sbp, dbp, sbpAvg, dbpAvg) -> runOnUiThread(() -> {
            tvSBPRealtime.setText(String.format(Locale.getDefault(),
                    "SBP : %.1f", sbp));
            tvDBPRealtime.setText(String.format(Locale.getDefault(),
                    "DBP : %.1f", dbp));
            tvSBPAvg.setText(String.format(Locale.getDefault(),
                    "SBP(Average) : %.1f", sbpAvg));
            tvDBPAvg.setText(String.format(Locale.getDefault(),
                    "DBP(Average) : %.1f", dbpAvg));
        }));

        // 3. Logic1 に波形コールバックを設定（既に analyzer 初期化済みの前提）
        LogicProcessor lp = analyzer.getLogicProcessor("Logic1");
        if (lp instanceof Logic1) {
            ((Logic1) lp).setBPFrameCallback(bpEstimator::update);
            // ここで必ずsetLogicRefを呼ぶ！
            bpEstimator.setLogicRef((Logic1) lp);
        }
    }

    private void initSinBP() {
        // 1. インスタンス作成＆View のバインド（SinBP(D)）
        sinBPDistortion = new SinBPDistortion();
        tvSinSBP = findViewById(R.id.tvSinSBP);
        tvSinDBP = findViewById(R.id.tvSinDBP);
        tvSinSBPAvg = findViewById(R.id.tvSinSBPAvg);
        tvSinDBPAvg = findViewById(R.id.tvSinDBPAvg);

        // 2. UI 更新リスナ登録（SinBP(D)）
        sinBPDistortion.setListener((sinSbp, sinDbp, sinSbpAvg, sinDbpAvg) -> runOnUiThread(() -> {
            tvSinSBP.setText(String.format(Locale.getDefault(),
                    "SinSBP(D) : %.1f", sinSbp));
            tvSinDBP.setText(String.format(Locale.getDefault(),
                    "SinDBP(D) : %.1f", sinDbp));
            tvSinSBPAvg.setText(String.format(Locale.getDefault(),
                    "SinSBP(D)(Avg) : %.1f", sinSbpAvg));
            tvSinDBPAvg.setText(String.format(Locale.getDefault(),
                    "SinDBP(D)(Avg) : %.1f", sinDbpAvg));
        }));

        // 3. Logic1への参照を設定とコールバック設定
        LogicProcessor lp = analyzer.getLogicProcessor("Logic1");
        if (lp instanceof Logic1) {
            sinBPDistortion.setLogicRef((Logic1) lp);
            ((Logic1) lp).setSinBPCallback(sinBPDistortion::update);
        }
    }
    
    private void initSinBPModel() {
        // 1. インスタンス作成＆View のバインド（SinBP(M)）
        sinBPModel = new SinBPModel();
        tvSinSBPM = findViewById(R.id.tvSinSBPM);
        tvSinDBPM = findViewById(R.id.tvSinDBPM);
        tvSinSBPAvgM = findViewById(R.id.tvSinSBPAvgM);
        tvSinDBPAvgM = findViewById(R.id.tvSinDBPAvgM);

        // 2. UI 更新リスナ登録（SinBP(M)）
        sinBPModel.setListener((sinSbp, sinDbp, sinSbpAvg, sinDbpAvg) -> runOnUiThread(() -> {
            tvSinSBPM.setText(String.format(Locale.getDefault(),
                    "SinSBP(M) : %.1f", sinSbp));
            tvSinDBPM.setText(String.format(Locale.getDefault(),
                    "SinDBP(M) : %.1f", sinDbp));
            tvSinSBPAvgM.setText(String.format(Locale.getDefault(),
                    "SinSBP(M)(Avg) : %.1f", sinSbpAvg));
            tvSinDBPAvgM.setText(String.format(Locale.getDefault(),
                    "SinDBP(M)(Avg) : %.1f", sinDbpAvg));
        }));

        // 3. Logic1への参照を設定とコールバック設定
        LogicProcessor lp = analyzer.getLogicProcessor("Logic1");
        if (lp instanceof Logic1) {
            sinBPModel.setLogicRef((Logic1) lp);
            ((Logic1) lp).setSinBPModelCallback(sinBPModel::update);
        }
    }


    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("カメラの許可が要求されています")
                .setMessage("カメラの許可が要求されています")
                .setPositiveButton("Open Settings", (d, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                })
                .setNegativeButton("Cancel",
                        (d, w) ->
                                Toast.makeText(this, "Camera permission is required.",
                                        Toast.LENGTH_SHORT).show())
                .setCancelable(false).show();
    }

    @Override public void onRequestPermissionsResult(
            int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == CAMERA_PERMISSION_REQUEST_CODE &&
                (r.length == 0 || r[0] != PackageManager.PERMISSION_GRANTED))
            showPermissionSettingsDialog();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // アプリが再開されたときに輝度を最大に設定
        setMaxBrightness();
    }

    @Override
    protected void onPause() {
        // WHY: Activityを離れた後に画面固定が残ると端末操作を妨げるため、保険で解除する。
        stopMeasurementLockIfNeeded();
        super.onPause();
    }

    @Override
    protected void onStop() {
        // WHY: pauseを経由しない異常系でも画面固定/immersiveを残さない。
        stopMeasurementLockIfNeeded();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAutomationIntent(intent);
    }

    // ===== onActivityResult =====
//    @Override protected void onActivityResult(int req, int res, Intent data) {
//        super.onActivityResult(req, res, data);
//        if (req == REQUEST_WRITE_STORAGE &&
//                !Environment.isExternalStorageManager())
//            Toast.makeText(this, "Permission denied.",
//                    Toast.LENGTH_SHORT).show();
//
//        if (req == REQ_BP && res == Activity.RESULT_OK && data != null) {
//            updateBPRange(data.getDoubleExtra("BP_MAX", 0),
//                    data.getDoubleExtra("BP_MIN", 0));
//            analyzer.startCamera();
//        }
//    }


    // ===== BP表示更新 =====
    /*private void updateBPRange(double max, double min) {
        runOnUiThread(() -> {
            tvBPMax.setText(String.format(Locale.getDefault(),
                    "BP Max : %.1f", max));
            tvBPMin.setText(String.format(Locale.getDefault(),
                    "BP Min : %.1f", min));
        });
    }*/


    private void setMode(int m) {
        runOnUiThread(() ->
                ((TextView) findViewById(R.id.tvMode)).setText("mode : " + m));
    }

    // ===== Recording / Training =====
    private void startRecording() {
        if (isRecording) {
            return;
        }
        if (testModeActive) { // TEMP: テスト用 後で削除
            toggleTestMode(); // TEMP: テスト用 後で削除
        } // TEMP: テスト用 後で削除
        String subject = editTextName.getText().toString().trim();
        String baseName = buildManualBaseName(subject);
        prepareSession(baseName, subject, 0, mode, false);
        isRecording = true;
        analyzer.startRecording();
        // mode-1または初期状態（mode = -1）の場合は1分、それ以外は5分
        int recordingMinutes = (mode == MODE_1 || mode == -1) ? 1 : 5;
        int recordingMillis = recordingMinutes * 60 * 1000;
        String message = recordingMinutes + "分間の記録を開始しました";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        // カウントダウンタイマー＋ポップアップ表示
        activeCountDownTimer = new CountDownTimer(recordingMillis, 1000) {
            AlertDialog timerDialog;

            @Override
            public void onTick(long millisUntilFinished) {
                String msg = "残り " + (millisUntilFinished / 1000) + " 秒";
                if (timerDialog == null) {
                    timerDialog = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("記録中…")
                            .setMessage(msg)
                            .setCancelable(false)
                            .create();
                    timerDialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    timerDialog.show();
                } else {
                    timerDialog.setMessage(msg);
                }
            }

            @Override
            public void onFinish() {
                if (timerDialog != null) timerDialog.dismiss();
                stopRecordingAndPersist();
                Toast.makeText(MainActivity.this,
                        "記録を終了しました", Toast.LENGTH_SHORT).show();
            }
        };
        activeCountDownTimer.start();
    }

    private void stopRecording() {
        if (activeCountDownTimer != null) {
            activeCountDownTimer.cancel();
            activeCountDownTimer = null;
        }
        if ((mode >= MODE_3 && mode <= MODE_8) && midiHapticPlayer != null) {
            midiHapticPlayer.stop();
        }
        
        // 強化学習モードの停止
        if ((mode == MODE_9 || mode == MODE_10) && isRLMode) {
            stopReinforcementLearning();
        }
        
        // ランダム刺激モードの停止
        if (mode == MODE_2 && isRandomStimuliMode) {
            stopRandomStimuli();
        }
        
        isRecording = false;
        Toast.makeText(this, "Stop recording", Toast.LENGTH_SHORT).show();
        analyzer.stopRecording();
    }

    private void stopRecordingAndPersist() {
        boolean hasRecordedData = analyzer != null && analyzer.hasRecordedTrainingData();
        if (!isRecording && !hasRecordedData) {
            Log.i(AUTOMATION_LOG_TAG, "stopRecordingAndPersist skipped: isRecording=false and no recorded data sessionId=" + currentSessionId);
            return;
        }
        if (!persistInProgress.compareAndSet(false, true)) {
            Log.i(AUTOMATION_LOG_TAG, "stopRecordingAndPersist skipped: persist already running sessionId=" + currentSessionId);
            return;
        }
        String subject = editTextName.getText().toString().trim();
        String outputBaseName = currentOutputBaseName.isEmpty() ? buildManualBaseName(subject) : currentOutputBaseName;
        boolean isMode1 = mode == MODE_1 || mode == -1;
        String sessionId = currentSessionId;
        Log.i(AUTOMATION_LOG_TAG, "stopRecordingAndPersist begin sessionId=" + sessionId + " hasRecordedData=" + hasRecordedData);
        if (isRecording) {
            stopRecording();
        }
        ExecutorService persistExecutor = ensureBackgroundExecutor();
        try {
            persistExecutor.execute(() -> {
                try {
                    // WHY: 5分CSV/IMU保存は巨大でUIを5秒以上止めるため、完了待ちはメインスレッドから逃がす。
                    analyzer.saveRawDataToCsv(outputBaseName, isMode1);
                    analyzer.saveRTBPToCsv(outputBaseName, isMode1);
                    analyzer.saveSinBPMToCsv(outputBaseName, isMode1);
                    analyzer.saveSinBPDToCsv(outputBaseName, isMode1);
                    analyzer.saveWaveDataToCsv(outputBaseName, isMode1);
                    analyzer.saveImuDataToCsv(outputBaseName, isMode1);
                    analyzer.saveTrainingDataToCsv(outputBaseName, isMode1);
                    analyzer.saveSessionMetaToJson(outputBaseName, isMode1);
                    Log.i("AUTOMATION_SESSION", "SESSION_PERSIST_COMPLETE sessionId=" + sessionId);
                    Log.i(AUTOMATION_LOG_TAG, "stopRecordingAndPersist completed sessionId=" + sessionId);
                } catch (Exception e) {
                    Log.e(AUTOMATION_LOG_TAG, "stopRecordingAndPersist failed sessionId=" + sessionId, e);
                } finally {
                    persistInProgress.set(false);
                    mainHandler.post(MainActivity.this::stopMeasurementLockIfNeeded);
                }
            });
        } catch (RejectedExecutionException e) {
            persistInProgress.set(false);
            Log.e(AUTOMATION_LOG_TAG, "stopRecordingAndPersist rejected sessionId=" + sessionId, e);
            stopMeasurementLockIfNeeded();
        }
    }

    private ExecutorService ensureBackgroundExecutor() {
        if (rlExecutor == null || rlExecutor.isShutdown() || rlExecutor.isTerminated()) {
            rlExecutor = Executors.newSingleThreadExecutor();
        }
        return rlExecutor;
    }

    private void handleAutomationIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(AUTOMATION_LOG_TAG, "handleAutomationIntent action=" + action + " sessionId=" + intent.getStringExtra(EXTRA_SESSION_ID));
        if (ACTION_START_AUTOMATED_SESSION.equals(action)) {
            String sessionId = intent.getStringExtra(EXTRA_SESSION_ID);
            String subjectId = intent.getStringExtra(EXTRA_SUBJECT_ID);
            int sessionNumber = intent.getIntExtra(EXTRA_SESSION_NUMBER, 0);
            int requestedMode = intent.getIntExtra(EXTRA_MODE, MODE_1);
            boolean testForcePhases = intent.getBooleanExtra(EXTRA_TEST_FORCE_PHASES, false);
            boolean testAutoStop = intent.getBooleanExtra(EXTRA_TEST_AUTO_STOP, true);
            int testPlacementMs = intent.getIntExtra(EXTRA_TEST_PLACEMENT_MS, 0);
            int testRestMs = intent.getIntExtra(EXTRA_TEST_REST_MS, 0);
            int testPressMs = intent.getIntExtra(EXTRA_TEST_PRESS_MS, 0);
            int testReleaseMs = intent.getIntExtra(EXTRA_TEST_RELEASE_MS, 0);
            startAutomatedSession(
                    sessionId,
                    subjectId,
                    sessionNumber,
                    requestedMode,
                    testForcePhases,
                    testAutoStop,
                    testPlacementMs,
                    testRestMs,
                    testPressMs,
                    testReleaseMs);
        } else if (ACTION_STOP_AUTOMATED_SESSION.equals(action)) {
            stopRecordingAndPersist();
        }
    }

    private void startAutomatedSession(
            String sessionId,
            String subjectId,
            int sessionNumber,
            int requestedMode,
            boolean testForcePhases,
            boolean testAutoStop,
            long testPlacementMs,
            long testRestMs,
            long testPressMs,
            long testReleaseMs) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        String normalizedSubjectId = (subjectId == null || subjectId.trim().isEmpty())
                ? normalizedSessionId
                : subjectId.trim();
        Log.i(AUTOMATION_LOG_TAG, "startAutomatedSession sessionId=" + normalizedSessionId
                + " mode=" + requestedMode + " test_force_phases=" + testForcePhases);
        if (isRecording) {
            stopRecordingAndPersist();
        }
        mode = requestedMode;
        setMode(requestedMode);
        isAutomatedSession = true;
        editTextName.setText(normalizedSubjectId);
        prepareSession(normalizedSessionId, normalizedSubjectId, sessionNumber, requestedMode, true);
        analyzer.configureForcedPhaseTimeline(
                testForcePhases,
                testAutoStop,
                testPlacementMs,
                testRestMs,
                testPressMs,
                testReleaseMs);
        analyzer.startRecording();
        isRecording = true;
        Toast.makeText(this, "自動計測を開始しました: " + normalizedSessionId, Toast.LENGTH_SHORT).show();
    }

    private void prepareSession(String baseName, String subjectId, int sessionNumber, int selectedMode, boolean automated) {
        currentOutputBaseName = baseName;
        currentSessionId = baseName;
        currentSubjectId = subjectId != null ? subjectId : "";
        currentSessionNumber = sessionNumber;
        isAutomatedSession = automated;
        if (!automated) {
            analyzer.configureForcedPhaseTimeline(false, true, 0L, 0L, 0L, 0L);
        }
        analyzer.configureSession(
                currentSessionId,
                currentSubjectId,
                currentSessionNumber,
                selectedMode,
                APP_VERSION,
                COEFFICIENT_VERSION,
                currentOutputBaseName
        );
    }

    private String buildManualBaseName(String subject) {
        String base = (subject == null || subject.isEmpty()) ? "session" : subject;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return base + "_m" + mode + "_" + ts;
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        }
        return sessionId.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // ===== Mode選択コールバック =====
    @Override
    public void onModeSelected(int m) {
        // ── 既に再生中なら停止 ──
        if (midiHapticPlayer != null) {
            midiHapticPlayer.stop();
            midiHapticPlayer = null;
        }
        if (isRLMode) {
            stopReinforcementLearning();
        }
        if (isRandomStimuliMode) {
            stopRandomStimuli();
        }

        // ── モード切替処理 ──
        mode = m;
        setMode(m);
        Toast.makeText(this, "Selected Mode: " + m, Toast.LENGTH_SHORT).show();

        // ── モードに応じて即再生開始 ──
        if (mode == MODE_3) {                                  // bass  +10%
            midiHapticPlayer = new MidiHaptic(this, analyzer, null, +0.10);
            midiHapticPlayer.start();

        } else if (mode == MODE_4) {                           // bass  -10%
            midiHapticPlayer = new MidiHaptic(this, analyzer, null, -0.10);
            midiHapticPlayer.start();

        } else if (mode == MODE_5) {                           // musica +10%
            Uri u = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.musica);
            midiHapticPlayer = new MidiHaptic(this, analyzer, u, +1.20);
            midiHapticPlayer.start();

        } else if (mode == MODE_6) {                           // musicb +10%
            Uri u = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.musicb);
            midiHapticPlayer = new MidiHaptic(this, analyzer, u, +1.20);
            midiHapticPlayer.start();

        } else if (mode == MODE_7) {                           // musicc -10%
            Uri u = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.musicc);
            midiHapticPlayer = new MidiHaptic(this, analyzer, u, -0.10);
            midiHapticPlayer.start();

        } else if (mode == MODE_8) {                           // musicd -10%
            Uri u = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.musicd);
            midiHapticPlayer = new MidiHaptic(this, analyzer, u, -0.10);
            midiHapticPlayer.start();

        } else if (mode == MODE_9) {                           // 交感神経優位モード（強化学習）
            isRLMode = true;
            initReinforcementLearning();
            Toast.makeText(this, "交感神経優位モード（強化学習）を開始しました", Toast.LENGTH_SHORT).show();

        } else if (mode == MODE_10) {                          // 副交感神経優位モード（強化学習）
            isRLMode = true;
            initReinforcementLearning();
            Toast.makeText(this, "副交感神経優位モード（強化学習）を開始しました", Toast.LENGTH_SHORT).show();

        } else if (mode == MODE_2) {                           // ランダム刺激
            isRandomStimuliMode = true;
            startRandomStimuli();
            Toast.makeText(this, "ランダム刺激モードを開始しました", Toast.LENGTH_SHORT).show();
        }
    }

    // ===== 戻るボタン処理 =====
    @Override public void onBackPressed() {
        Fragment f = getSupportFragmentManager()
                .findFragmentById(R.id.mode_select_fragment_container);
        if (f instanceof ModeSelectionFragment) {
            getSupportFragmentManager().beginTransaction().remove(f).commit();
            findViewById(R.id.mode_select_fragment_container)
                    .setVisibility(View.GONE);
            modeBtn.setVisibility(View.VISIBLE);
        } else super.onBackPressed();
    }


    // ===== CSV保存 =====
    public void saveRawDataToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveRawDataToCsv(name, mode == MODE_1 || mode == -1);
    }
    public void saveRTBPToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveRTBPToCsv(name, mode == MODE_1 || mode == -1);
    }
    public void saveSinBPMToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveSinBPMToCsv(name, mode == MODE_1 || mode == -1);
    }
    public void saveSinBPDToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveSinBPDToCsv(name, mode == MODE_1 || mode == -1);
    }
    public void saveWaveDataToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveWaveDataToCsv(name, mode == MODE_1 || mode == -1);
    }
    public void saveImuDataToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveImuDataToCsv(name, mode == MODE_1 || mode == -1);
    }
    
    // ===== 後方互換性のためのメソッド（必要に応じて使用） =====
    public void saveIbiToCsv() { saveIbiToCsv(-1); }
    public void saveIbiToCsv(int c) {
        String ts = new SimpleDateFormat("_HH_mm_ss",
                Locale.getDefault()).format(new Date());
        String name = (c >= 0 ? c + "_" : "") +
                editTextName.getText().toString() + mode + ts;
        analyzer.saveIbiToCsv(name);
    }
    public void saveGreenValuesToCsv() {
        String ts = new SimpleDateFormat("_HH_mm_ss",
                Locale.getDefault()).format(new Date());
        analyzer.saveGreenValuesToCsv(editTextName.getText().toString()
                + mode + ts);
    }
    public void saveTrainingDataToCsv() {
        String name = currentOutputBaseName.isEmpty() ? buildManualBaseName(editTextName.getText().toString().trim()) : currentOutputBaseName;
        analyzer.saveTrainingDataToCsv(name, mode == MODE_1 || mode == -1);
    }

    // ===== 強化学習初期化 =====
    private void initReinforcementLearning() {
        // 環境とエージェントの初期化
        environment = new IBIControlEnv(mode, this);
        agent1 = new DQNAgent(3, 2);
        agent2 = new DQNAgent(3, 2);
        agent3 = new DQNAgent(3, 4);
        agent4 = new DQNAgent(3, 16);
        
        // 環境のリセット
        environment.reset();
        
        rlExecutor = Executors.newSingleThreadExecutor();
        startReinforcementLearning();
    }

    // ===== 強化学習ループ =====
    private void startReinforcementLearning() {
        if (!isRLMode) return;

        double currentIbi = analyzer.getCurrentIBI();
        if (currentIbi <= 0) {
            mainHandler.postDelayed(this::startReinforcementLearning, 500);
            return;
        }

        int[] state = environment.getState();
        int a1 = agent1.selectAction(state);
        int a2 = agent2.selectAction(state);
        int a3 = agent3.selectAction(state);
        int a4 = agent4.selectAction(state);

        rlExecutor.execute(() -> {
            // ── Heavy part on worker ──
            IBIControlEnv.getInfo stepInfo = environment.step(a1, a2, a3, a4);
            double newIbi = analyzer.getCurrentIBI();
            IBIControlEnv.StepResult r = environment.stepGetResult(
                    newIbi, stepInfo.checkFlag, stepInfo.stimuli, stepInfo.place);

            // store & train (heavy)  ─────
            agent1.storeExperience(state, a1, r.reward, r.state, r.done, 1.0);
            agent2.storeExperience(state, a2, r.reward, r.state, r.done, 1.0);
            agent3.storeExperience(state, a3, r.reward, r.state, r.done, 1.0);
            agent4.storeExperience(state, a4, r.reward, r.state, r.done, 1.0);

            if (stepInfo.counter % 4 == 0) {            // 学習頻度を間引く
                agent1.train(32);
                agent2.train(32);
                agent3.train(32);
                agent4.train(32);
            }

            if (r.done) environment.reset();

            // ── back to UI only forスケジューリング ──
            mainHandler.postDelayed(() -> {
                if (isRLMode) startReinforcementLearning();
            }, 100);   // 100 ms 程度で十分滑らか
        });
    }

    // ===== 強化学習停止 =====
    private void stopReinforcementLearning() {
        isRLMode = false;
        if (environment != null) environment.reset();
        if (rlExecutor != null) {
            rlExecutor.shutdown();
            rlExecutor = null;
        }
    }

    // ===== ランダム刺激開始 =====
    private void startRandomStimuli() {
        if (randomStimuliGen == null) {
            randomStimuliGen = new RandomStimuliGeneration(this);
        }
        randomStimuliRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRandomStimuliMode) return;
                double ibi = analyzer.getCurrentIBI();
                if (ibi > 0) {
                    randomStimuliGen.randomDec2Bin(ibi);
                }
                randomStimuliHandler.postDelayed(this, 2000); // 2秒ごとに刺激
            }
        };
        randomStimuliHandler.post(randomStimuliRunnable);
    }
    // ===== ランダム刺激停止 =====
    private void stopRandomStimuli() {
        isRandomStimuliMode = false;
        if (randomStimuliHandler != null && randomStimuliRunnable != null) {
            randomStimuliHandler.removeCallbacks(randomStimuliRunnable);
        }
    }
}
