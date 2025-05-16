// ─────────────────── MainActivity.java ───────────────────
package com.example.realtimehribicontrol;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
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

public class MainActivity extends AppCompatActivity
        implements ModeSelectionFragment.OnModeSelectedListener {

    // ===== 定数 =====
    private static final int REQUEST_WRITE_STORAGE = 112, CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MODE_1 = 1, MODE_2 = 2, MODE_3 = 3, MODE_4 = 4, MODE_5 = 5, MODE_9 = 9, MODE_10 = 10, REQ_BP = 201;

    // ===== UI =====
    private Button startButton, resetButton, modeBtn, bpMeasureButton;
    private EditText editTextName; private Spinner spinnerLogic;
    private TextView tvBPMax, tvBPMin;

    // ===== 解析と状態 =====
    private GreenValueAnalyzer analyzer; private RandomStimuliGeneration stimuliGen;
    private int mode = -1; private boolean isRecording; private Handler handler; private Runnable recordTask;
    private final List<String> stimuliList = new ArrayList<>();
    private double nowIbi;

    // ===== ランチャー =====
    private ActivityResultLauncher<Intent> bpLauncher;

    private awakeMIDI awakePlayer;
    private RealtimeBP bpEstimator;
    private TextView tvSBPRealtime, tvDBPRealtime;

    // ===== onCreate =====
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        requestCameraPermission();
        initUi();
        initAnalyzer();
        initRealtimeBP();
        handler = new Handler();
    }

    // ===== UI初期化 =====
    private void initUi() {
        modeBtn = findViewById(R.id.show_mode_select_fragment_button);
        startButton = findViewById(R.id.start_button);
        resetButton = findViewById(R.id.reset_button);
        editTextName = findViewById(R.id.editTextName);
        spinnerLogic = findViewById(R.id.spinnerLogicSelection);
        bpMeasureButton = findViewById(R.id.btn_bp_measure);
        tvBPMax = findViewById(R.id.tvBPMax);
        tvBPMin = findViewById(R.id.tvBPMin);

        String[] logics = {"Logic1","Logic2","Logic3","Logic4","Logic5","Logic6"};
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
                analyzer.setActiveLogic("Logic6");
            }
        });

        bpLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> {
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                        Intent d = r.getData();
                        updateBPRange(d.getDoubleExtra("BP_MAX", 0),
                                d.getDoubleExtra("BP_MIN", 0));
                        analyzer.restartCamera();
                    }
                });

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

        startButton.setOnClickListener(v -> {
            if (mode != -1) { initializeMode(); startRecording(); }
            else Toast.makeText(this, "Please select a mode",
                    Toast.LENGTH_SHORT).show();
        });
        resetButton.setOnClickListener(v -> analyzer.reset());
        bpMeasureButton.setOnClickListener(v ->
                bpLauncher.launch(new Intent(this, PressureAnalyze.class))
        );

        requestWritePermission();
        if (Settings.System.canWrite(this)) setBrightness(255);
        else startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS));
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

        // 2. UI 更新リスナ登録
        bpEstimator.setListener((sbp, dbp) ->
                runOnUiThread(() -> {
                    tvSBPRealtime.setText(
                            String.format(Locale.getDefault(), "SBP : %.1f", sbp));
                    tvDBPRealtime.setText(
                            String.format(Locale.getDefault(), "DBP : %.1f", dbp));
                })
        );

        // 3. Logic1 に波形コールバックを設定（既に analyzer 初期化済みの前提）
        LogicProcessor lp = analyzer.getLogicProcessor("Logic1");
        if (lp instanceof Logic1) {
            ((Logic1) lp).setBPFrameCallback(bpEstimator::update);
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
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQUEST_WRITE_STORAGE &&
                !Environment.isExternalStorageManager())
            Toast.makeText(this, "Permission denied.",
                    Toast.LENGTH_SHORT).show();

        if (req == REQ_BP && res == Activity.RESULT_OK && data != null) {
            updateBPRange(data.getDoubleExtra("BP_MAX", 0),
                    data.getDoubleExtra("BP_MIN", 0));
            analyzer.startCamera();
        }
    }

    // ===== 書き込み許可 =====
    private void requestWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent i = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQUEST_WRITE_STORAGE);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }
    }

    // ===== 画面輝度 =====
    private void setBrightness(int b) {
        ContentResolver c = getContentResolver();
        Settings.System.putInt(c, Settings.System.SCREEN_BRIGHTNESS, b);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = b / 255f; getWindow().setAttributes(lp);
    }

    // ===== BP表示更新 =====
    private void updateBPRange(double max, double min) {
        runOnUiThread(() -> {
            tvBPMax.setText(String.format(Locale.getDefault(),
                    "BP Max : %.1f", max));
            tvBPMin.setText(String.format(Locale.getDefault(),
                    "BP Min : %.1f", min));
        });
    }


    // ===== Mode初期化 =====
    private void initializeMode() {
        if (mode == MODE_5) {
            // raw フォルダに Rock16.mid を置いておく想定
            Uri midiUri = Uri.parse("android.resource://" + getPackageName() + "/raw/rock16");
            awakePlayer = new awakeMIDI(this, analyzer, null);
        }
        if (mode == MODE_3 || mode == MODE_4) {
            stimuliGen = new RandomStimuliGeneration(this);
        }
    }
    private void setMode(int m) {
        runOnUiThread(() ->
                ((TextView) findViewById(R.id.tvMode)).setText("mode : " + m));
    }

    // ===== Recording / Training =====
    private void startRecording() {
        isRecording = true;
        Toast.makeText(this, "Start recording", Toast.LENGTH_SHORT).show();
        analyzer.startRecording();

        if (mode == MODE_5) {
            awakePlayer.start();    // BPM に合わせて MIDI 再生＆ループ
        } else if (mode == MODE_9 || mode == MODE_10) {
            startTrainingLoop();
        } else {
            recordTask = this::startTrainingLoop;
            handler.postDelayed(recordTask, 60000);
        }
    }
    private void stopRecording() {
        if (mode == MODE_5 && awakePlayer != null) {
            awakePlayer.stop();
        }
        isRecording = false;
        Toast.makeText(this, "Stop recording", Toast.LENGTH_SHORT).show();
        analyzer.stopRecording();
    }
    private void startTrainingLoop() {
        Toast.makeText(this, "Start your assignment", Toast.LENGTH_SHORT).show();
        new Thread(new TrainingLoop()).start();
    }

    // ===== Mode選択コールバック =====
    @Override public void onModeSelected(int m) {
        mode = m; setMode(m);
        Toast.makeText(this, "Selected Mode: " + m, Toast.LENGTH_SHORT).show();
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

    // ===== TrainingLoop内部クラス =====
    private class TrainingLoop implements Runnable {
        public void run() {
            int cnt = 0;
            try {
                switch (mode) {
                    case MODE_1: case MODE_2: Thread.sleep(1_800_000); break;
                    case MODE_3: case MODE_4:
                        while (true) {
                            nowIbi = analyzer.getLatestIbi();
                            RandomStimuliGeneration.RandomResult info =
                                    stimuliGen.randomDec2Bin(nowIbi);
                            stimuliList.add(info.stimuli);
                            if (info.done) break;
                        } break;
                    case MODE_9: Thread.sleep(60_000); break;
                    case MODE_10:
                        while (cnt < 20) {
                            analyzer.startRecording(); Thread.sleep(20_000);
                            analyzer.stopRecording(); saveIbiToCsv(cnt);
                            analyzer.clearRecordedData(); cnt++;
                            if (cnt < 20) Thread.sleep(10_000);
                        } break;
                }
            } catch (Exception e) {
                Log.e("TrainingLoop", "err", e);
            } finally {
                runOnUiThread(() -> {
                    analyzer.stopRecording();
                    if (mode != MODE_10) saveIbiToCsv();
                    saveGreenValuesToCsv(); setMode(-1);
                });
            }
        }
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

    // ===== getter =====
    public double getLatestIbiValue() { return analyzer.getLatestIbi(); }

}