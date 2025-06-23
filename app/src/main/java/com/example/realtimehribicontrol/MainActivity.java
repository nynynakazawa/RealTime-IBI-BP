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

public class MainActivity extends AppCompatActivity
        implements ModeSelectionFragment.OnModeSelectedListener {

    // ===== 定数 =====
    private static final int REQUEST_WRITE_STORAGE = 112, CAMERA_PERMISSION_REQUEST_CODE = 101;
    private static final int MODE_1 = 1, MODE_2 = 2, MODE_3 = 3, MODE_4 = 4, MODE_5 = 5, MODE_6 = 6, MODE_7=7, MODE_8=8, MODE_9 = 9, MODE_10 = 10, REQ_BP = 201;

    // ===== UI =====
    private Button startButton, resetButton, modeBtn, bpMeasureButton;
    private EditText editTextName; private Spinner spinnerLogic;
    private TextView tvBPMax, tvBPMin,tvSBPRealtime,tvDBPRealtime, tvSBPAvg, tvDBPAvg;

    // ===== 解析と状態 =====
    private GreenValueAnalyzer analyzer; private RandomStimuliGeneration stimuliGen;
    private int mode = -1; private boolean isRecording; private Handler handler; private Runnable recordTask;

    // ===== ランチャー =====
    private ActivityResultLauncher<Intent> bpLauncher;
    private MidiHaptic midiHapticPlayer;

    private RealtimeBP bpEstimator;

    // ===== onCreate =====
    @Override protected void onCreate(Bundle s){
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        requestCameraPermission();
        initUi();
        initAnalyzer();
        initRealtimeBP();
        analyzer.setBpEstimator(bpEstimator);
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

        startButton.setOnClickListener(v -> startRecording());

        resetButton.setOnClickListener(v -> analyzer.reset());
        bpMeasureButton.setOnClickListener(v ->
                bpLauncher.launch(new Intent(this, PressureAnalyze.class))
        );
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


    // ===== BP表示更新 =====
    private void updateBPRange(double max, double min) {
        runOnUiThread(() -> {
            tvBPMax.setText(String.format(Locale.getDefault(),
                    "BP Max : %.1f", max));
            tvBPMin.setText(String.format(Locale.getDefault(),
                    "BP Min : %.1f", min));
        });
    }


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
        if (midiHapticPlayer != null) {
            midiHapticPlayer.stop();
            midiHapticPlayer = null;
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

}