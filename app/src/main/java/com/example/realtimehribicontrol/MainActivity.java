package com.example.realtimehribicontrol;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.github.mikephil.charting.charts.LineChart;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity implements ModeSelectionFragment.OnModeSelectedListener {

    // フィールド変数の宣言
    private RandomStimuliGeneration RandomStimuli;
    private int mode = -1;
    private Button startButton;
    private Button resetButton;
    private Button ModeSelectFragmentButton;
    private EditText editTextName;
    private Spinner spinnerLogicSelection; // ドロップダウン用Spinner
    private static final int REQUEST_WRITE_STORAGE = 112;
    private static final int MODE_1 = 1;
    private static final int MODE_2 = 2;
    private static final int MODE_3 = 3;
    private static final int MODE_4 = 4;
    // MODE_5～MODE_8 削除
    private static final int MODE_9 = 9;
    private static final int MODE_10 = 10;
    double nowIbi;
    boolean StartProcess = false;
    private GreenValueAnalyzer greenValueAnalyzer;
    private List<String> stimuliList = new ArrayList<>();
    private boolean isRecording = false;
    private Handler recordingHandler;
    private Runnable recordingRunnable;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;
    private Button bpMeasureButton;
    private static final int REQ_BP = 201;
    private TextView tvBPMax;
    private TextView tvBPMin;

    // Activity Result API ランチャー
    private ActivityResultLauncher<Intent> bpLauncher;

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                if (!shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                    showPermissionSettingsDialog();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Camera Permission Needed")
                            .setMessage("This app requires camera access for proper operation. Please allow it.")
                            .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                                }
                            })
                            .setCancelable(false)
                            .show();
                }
            }
        }
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Camera Permission Required")
                .setMessage("Camera permission is required for this app to function properly. Please enable it in the app settings.")
                .setPositiveButton("Open Settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        Uri uri = Uri.fromParts("package", getPackageName(), null);
                        intent.setData(uri);
                        startActivity(intent);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Toast.makeText(MainActivity.this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Camera permission granted");
            } else {
                showPermissionSettingsDialog();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestCameraPermission();
        bpMeasureButton = findViewById(R.id.btn_bp_measure);
        tvBPMax = findViewById(R.id.tvBPMax);
        tvBPMin = findViewById(R.id.tvBPMin);

        int cameraPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA);
        Log.d("MainActivity", "Camera permission status: " + cameraPermission);

        // ActivityResultLauncher 登録
        bpLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        double max = data.getDoubleExtra("BP_MAX", 0);
                        double min = data.getDoubleExtra("BP_MIN", 0);
                        updateBPRange(max, min);
                        // PPG測定を確実に再開
                        startRecording();
                    }
                }
        );

        // UIコンポーネントの取得
        ModeSelectFragmentButton = findViewById(R.id.show_mode_select_fragment_button);
        startButton = findViewById(R.id.start_button);
        resetButton = findViewById(R.id.reset_button);
        editTextName = findViewById(R.id.editTextName);
        spinnerLogicSelection = findViewById(R.id.spinnerLogicSelection);

        // Spinnerの初期化
        String[] logicOptions = {"Logic1", "Logic2", "Logic3", "Logic4", "Logic5", "Logic6"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, logicOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLogicSelection.setAdapter(adapter);
        spinnerLogicSelection.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String selectedLogic = (String) parent.getItemAtPosition(position);
                greenValueAnalyzer.setActiveLogic(selectedLogic);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                greenValueAnalyzer.setActiveLogic("Logic1");
            }
        });

        requestWritePermission();

        if (Settings.System.canWrite(this)) {
            setBrightness(255);
        } else {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            startActivity(intent);
        }

        LineChart lineChart = findViewById(R.id.lineChart);
        TextView greenValueTextView = findViewById(R.id.greenValueTextView);
        TextView nowTextMessage = findViewById(R.id.NowTextMessage);
        TextView tvBpmSd = findViewById(R.id.BPMSD);
        TextView ibiTextView = findViewById(R.id.ibiTextView);
        TextView tvHeartRate = findViewById(R.id.HRTextView);
        TextView smoothedIbiTextView = findViewById(R.id.SmoothedIbiTextView);
        TextView smoothedHRTextView = findViewById(R.id.SmoothedHRTextView);

        greenValueAnalyzer = new GreenValueAnalyzer(this, lineChart, greenValueTextView,
                ibiTextView, nowTextMessage, tvBpmSd, tvHeartRate,
                smoothedIbiTextView, smoothedHRTextView);

        recordingHandler = new Handler();
        initializeUI();
    }

    private void updateBPRange(double max, double min) {
        runOnUiThread(() -> {
            tvBPMax.setText(String.format(Locale.getDefault(), "BP Max : %.1f", max));
            tvBPMin.setText(String.format(Locale.getDefault(), "BP Min : %.1f", min));
        });
    }

    private void initializeUI() {
        ModeSelectFragmentButton.setOnClickListener(v -> {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mode_select_fragment_container, new ModeSelectionFragment())
                    .commit();
            findViewById(R.id.mode_select_fragment_container).setVisibility(View.VISIBLE);
            v.setVisibility(View.GONE);
        });

        startButton.setOnClickListener(v -> {
            if (mode != -1) {
                initializeMode();
                StartProcess = true;
                startRecording();
            } else {
                Toast.makeText(MainActivity.this, "Please select a mode", Toast.LENGTH_SHORT).show();
            }
        });

        resetButton.setOnClickListener(v -> greenValueAnalyzer.reset());

        bpMeasureButton.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, PressureAnalyze.class);
            bpLauncher.launch(intent);
        });
    }

    private void requestWritePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_WRITE_STORAGE);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        /* 書き込み権限の結果 */
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this,
                        "Permission denied. Please allow storage access.",
                        Toast.LENGTH_SHORT).show();
            }
            return;                       // ここで終了
        }

        /* 血圧測定結果の受信 */
        if (requestCode == REQ_BP && resultCode == Activity.RESULT_OK && data != null) {
            double max = data.getDoubleExtra("BP_MAX", 0);
            double min = data.getDoubleExtra("BP_MIN", 0);
            updateBPRange(max, min);

            // PPG測定を自動的に再開
            if (!isRecording) {
                startRecording();
            }
        }
    }

    private void setBrightness(int brightness) {
        ContentResolver cResolver = getContentResolver();
        Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.screenBrightness = brightness / (float) 255;
        getWindow().setAttributes(layoutParams);
    }


    private void initializeMode() {
        switch (mode) {
            case MODE_1:
            case MODE_2:
            case MODE_9:
                break;
            case MODE_3:
            case MODE_4:
                RandomStimuli = new RandomStimuliGeneration(this);
                break;
        }
    }

    private void startRecording() {
        isRecording = true;
        Toast.makeText(this, "Start recording", Toast.LENGTH_SHORT).show();
        greenValueAnalyzer.startRecording();
        if (mode == MODE_9 || mode == MODE_10) {
            startTrainingLoop();
        } else {
            recordingRunnable = new Runnable() {
                @Override
                public void run() {
                    startTrainingLoop();
                }
            };
            recordingHandler.postDelayed(recordingRunnable, 60000);
        }
    }

    private void startTrainingLoop() {
        isRecording = true;
        Toast.makeText(this, "Start your assignment", Toast.LENGTH_SHORT).show();
        new Thread(new TrainingLoop()).start();
    }

    private void stopRecording() {
        isRecording = false;
        Toast.makeText(this, "Stop recording", Toast.LENGTH_SHORT).show();
        greenValueAnalyzer.stopRecording();
    }

    @Override
    public void onModeSelected(int selectedMode) {
        mode = selectedMode;
        setMode(mode);
        Toast.makeText(this, "Selected Mode: " + mode, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.mode_select_fragment_container);
        if (fragment instanceof ModeSelectionFragment) {
            getSupportFragmentManager().beginTransaction().remove(fragment).commit();
            findViewById(R.id.mode_select_fragment_container).setVisibility(View.GONE);
            findViewById(R.id.show_mode_select_fragment_button).setVisibility(View.VISIBLE);
        } else {
            super.onBackPressed();
        }
    }

    private void setMode(int mode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView textView = findViewById(R.id.tvMode);
                textView.setText("mode : " + mode);
            }
        });
    }

    private class TrainingLoop implements Runnable {
        private int modeTcount = 0;
        @Override
        public void run() {
            Log.d("mode", "start");
            try {
                switch (mode) {
                    case MODE_1:
                    case MODE_2:
                        long startTime = System.currentTimeMillis();
                        while (true) {
                            if (System.currentTimeMillis() - startTime >= 1800000) {
                                break;
                            }
                        }
                        break;
                    case MODE_3:
                    case MODE_4:
                        while (true) {
                            nowIbi = greenValueAnalyzer.getLatestIbi();
                            RandomStimuliGeneration.RandomResult info = RandomStimuli.randomDec2Bin(nowIbi);
                            Log.d("stimuli", info.stimuli);
                            stimuliList.add(info.stimuli);
                            if (info.done) {
                                break;
                            }
                        }
                        break;
                    case MODE_9:
                        startTime = System.currentTimeMillis();
                        while (true) {
                            if (System.currentTimeMillis() - startTime >= 60000) {
                                break;
                            }
                        }
                        break;
                    case MODE_10:
                        while (modeTcount < 20) {
                            Log.d("MODE_10", "Iteration start: modeTcount = " + modeTcount);
                            float reductionFactor = Math.min(modeTcount * 0.05f, 1.0f);
                            greenValueAnalyzer.setReductionFactor(reductionFactor);
                            greenValueAnalyzer.startRecording();
                            try {
                                Thread.sleep(20000);
                            } catch (InterruptedException e) {
                                Log.e("MODE_10", "Sleep interrupted during recording", e);
                            }
                            greenValueAnalyzer.stopRecording();
                            saveIbiToCsv(modeTcount);
                            greenValueAnalyzer.clearRecordedData();
                            modeTcount++;
                            Log.d("MODE_10", "Iteration end: modeTcount = " + modeTcount);
                            if (modeTcount >= 20) {
                                break;
                            }
                            try {
                                Thread.sleep(10000);
                            } catch (InterruptedException e) {
                                Log.e("MODE_10", "Sleep interrupted during rest", e);
                            }
                        }
                        break;
                }
            } catch (Exception e) {
                Log.e("TrainingLoop", "Error during training loop", e);
            } finally {
                Log.d("TrainingLoop", "Finally block executed");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        greenValueAnalyzer.stopRecording();
                        if (mode != MODE_10) {
                            saveIbiToCsv();
                        }
                        saveGreenValuesToCsv();
                        setMode(-1);
                    }
                });
            }
        }
    }

    public double getLatestIbiValue() {
        return greenValueAnalyzer.getLatestIbi();
    }

    public void saveIbiToCsv() {
        SimpleDateFormat sdf = new SimpleDateFormat("_HH_mm_ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String fileName = editTextName.getText().toString() + mode + timestamp;
        greenValueAnalyzer.saveIbiToCsv(fileName);
    }

    public void saveIbiToCsv(int modeTcount) {
        SimpleDateFormat sdf = new SimpleDateFormat("_HH_mm_ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String fileName = modeTcount + "_" + editTextName.getText().toString() + mode + timestamp;
        greenValueAnalyzer.saveIbiToCsv(fileName);
    }

    public void saveGreenValuesToCsv() {
        SimpleDateFormat sdf = new SimpleDateFormat("_HH_mm_ss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String fileName = editTextName.getText().toString() + mode + timestamp;
        greenValueAnalyzer.saveGreenValuesToCsv(fileName);
    }
}