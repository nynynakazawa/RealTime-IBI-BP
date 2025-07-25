// ─────────────────── MainActivity.java ───────────────────
package com.example.realtimehribicontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.github.mikephil.charting.charts.LineChart;
import java.text.SimpleDateFormat;
import java.util.*;
import android.os.Handler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Looper;

public class MainActivity extends AppCompatActivity
        implements ModeSelectionFragment.OnModeSelectedListener {

    // ===== 定数 =====
    private static final int REQUEST_WRITE_STORAGE = 112, CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MODE_1 = 1, MODE_2 = 2, MODE_3 = 3, MODE_4 = 4, MODE_5 = 5, MODE_6 = 6, MODE_7=7, MODE_8=8, MODE_9 = 9, MODE_10 = 10, REQ_BP = 201;

    // ===== UI =====
    private Button startButton, resetButton, modeBtn/*, bpMeasureButton*/;
    private EditText editTextName; private Spinner spinnerLogic;
    private TextView /*tvBPMax, tvBPMin,*/tvSBPRealtime,tvDBPRealtime, tvSBPAvg, tvDBPAvg;
    private TextView tvFNumber, tvISO, tvExposureTime, tvColorTemperature, tvWhiteBalance, tvFocusDistance, tvAperture, tvSensorSensitivity;

    // ===== 解析と状態 =====
    private GreenValueAnalyzer analyzer; private RandomStimuliGeneration stimuliGen;
    private int mode = -1; private boolean isRecording; private Handler handler; private Runnable recordTask;

    // ===== ランチャー =====
    private ActivityResultLauncher<Intent> bpLauncher;
    private MidiHaptic midiHapticPlayer;

    private RealtimeBP bpEstimator;

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

    // ===== onCreate =====
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        requestCameraPermission();
        initUi();
        initAnalyzer();
        initRealtimeBP();
        analyzer.setBpEstimator(bpEstimator);
        
        // Camera X API 色温度関連情報のコールバックを設定
        analyzer.setCameraInfoCallback((fNumber, iso, exposureTime, colorTemperature, whiteBalanceMode, focusDistance, aperture, sensorSensitivity) -> {
            runOnUiThread(() -> {
                tvFNumber.setText(String.format(Locale.getDefault(), "F-Number: %.1f", fNumber));
                tvISO.setText(String.format(Locale.getDefault(), "ISO: %d", iso));
                tvExposureTime.setText(String.format(Locale.getDefault(), "Exposure: %d", exposureTime));
                tvColorTemperature.setText(String.format(Locale.getDefault(), "Color Temp: %.0f", colorTemperature));
                tvWhiteBalance.setText(String.format(Locale.getDefault(), "WB Mode: %d", whiteBalanceMode));
                tvFocusDistance.setText(String.format(Locale.getDefault(), "Focus: %.2f", focusDistance));
                tvAperture.setText(String.format(Locale.getDefault(), "Aperture: %.1f", aperture));
                tvSensorSensitivity.setText(String.format(Locale.getDefault(), "Sensor: %.0f", sensorSensitivity));
            });
        });
        
        handler = new Handler();
        analyzer.startRecording();
        analyzer.stopRecording();
    }

    // ===== UI初期化 =====
    private void initUi() {
        modeBtn = findViewById(R.id.show_mode_select_fragment_button);
        startButton = findViewById(R.id.start_button);
        resetButton = findViewById(R.id.reset_button);
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

        resetButton.setOnClickListener(v -> analyzer.reset());
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
    }

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
        isRecording = true;
        analyzer.startRecording();
        Toast.makeText(this, "5分間の記録を開始しました", Toast.LENGTH_SHORT).show();

        // 5分間のカウントダウンタイマー＋ポップアップ表示
        new CountDownTimer(5 * 60 * 1000, 1000) {
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

                // 停止＆CSV保存
                stopRecording();
                saveIbiToCsv();
                saveGreenValuesToCsv();
                Toast.makeText(MainActivity.this,
                        "記録を終了しました", Toast.LENGTH_SHORT).show();
            }
        }.start();
    }

    private void stopRecording() {
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