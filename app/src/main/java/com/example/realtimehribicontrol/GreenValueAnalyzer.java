package com.example.realtimehribicontrol;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.camera.camera2.interop.Camera2Interop;
import android.os.Environment;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.io.File;
import java.util.concurrent.Executors;
import android.util.Log;

/**
 * GreenValueAnalyzerクラス
 * カメラ画像からグリーンチャンネルの値を取得し、各種フィルタ処理、ピーク検出、心拍計算などを行うクラス
 */
public class GreenValueAnalyzer implements LifecycleObserver {
    private Context context;
    private LineChart lineChart;
    private TextView greenValueTextView;
    private TextView ibiTextView;
    private TextView NowTextMessage;
    private final TextView tvBpmSd;
    private final TextView tvHeartRate;
    private LineData lineData;
    private LineDataSet lineDataSet;
    private List<Entry> entries = new ArrayList<>();
    private HandlerThread handlerThread;
    private boolean isRecording = false;
    private ArrayList<Double> greenValues = new ArrayList<>();
    private ArrayList<Double> filteredValues = new ArrayList<>();
    private ArrayList<Double> recentGreenValues = new ArrayList<>(); // 直近のGreenValueを保持するリスト
    private ArrayList<Double> recentCorrectedGreenValues = new ArrayList<>(); // 直近のcorrectedGreenValueを保持するリスト
    private ArrayList<Double> smoothedIbi = new ArrayList<>();
    private ArrayList<Double> smoothedBpmSd = new ArrayList<>();
    private static final int WINDOW_SIZE = 240; // 窓サイズ
    private double[] window = new double[WINDOW_SIZE];
    private int windowIndex = 0;
    private double bpmValue = 0.0;
    private double IBI = 0.0;
    private long lastPeakTime = 0;
    private int updateCount = 0; // 更新回数をカウントする変数
    private ArrayList<Double> bpmHistory = new ArrayList<>();
    private double bpmSD = 0.0;
    int flag = 0;
    private ArrayList<Double> recordedValue = new ArrayList<>();
    private ArrayList<Double> recordedIbi = new ArrayList<>();
    private ArrayList<Long> recordedValueTimeStamp = new ArrayList<>();
    private ArrayList<Long> recordedIbiTimeStamp = new ArrayList<>();
    private boolean isCameraOpen = false;
    private ArrayList<Double> recordedBpmSd = new ArrayList<>();
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private float ambientLightLevel = -1; // 環境光レベルを保持
    private int ambientLightMeasurementCount = 0; // 測定回数をカウント
    private float ambientLightSum = 0f; // 環境光レベルの合計値
    private float reductionFactor = 0f;
    private float averageAmbientLight = -1f; // 128回の平均環境光レベル
    private static final int AMBIENT_LIGHT_MEASUREMENTS = 128; // 測定回数の上限
    private ArrayList<Double> smoothedCorrectedGreenValues = new ArrayList<>();
    private final TextView smoothedIbiTextView;
    private final TextView smoothedHRTextView;
    private ArrayList<Double> recordedSmoothedIbi = new ArrayList<>();
    private ArrayList<Double> recordedSmoothedBpmSd = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // 各ロジック用のフィールド
    private Logic1 logic1Processor;
    private Logic2 logic2Processor;
    private Logic3 logic3Processor;
    private Logic4 logic4Processor;
    private Logic5 logic5Processor;
    private Logic6 logic6Processor;


    private String activeLogic; // 現在選択中のロジックを示すフィールド

    public void setActiveLogic(String activeLogic) {
        this.activeLogic = activeLogic;
        // 選択に応じて、該当するロジックプロセッサのインスタンスを生成（まだ生成されていなければ）
        if ("Logic1".equals(activeLogic)) {
            if (logic1Processor == null) {
                logic1Processor = new Logic1();
            }
        } else if ("Logic2".equals(activeLogic)) {
            if (logic2Processor == null) {
                logic2Processor = new Logic2();
            }
        } else if ("Logic3".equals(activeLogic)) {
            if (logic3Processor == null) {
                logic3Processor = new Logic3();
            }
        }else if ("Logic4".equals(activeLogic)) {
            if (logic4Processor == null) {
                logic4Processor = new Logic4();
            }
        }else if ("Logic5".equals(activeLogic)) {
            if (logic5Processor == null) {
                logic5Processor = new Logic5();
            }
        }
        else if ("Logic6".equals(activeLogic)) {
            if (logic6Processor == null) {
                logic6Processor = new Logic6();
            }
        }
        // 他のロジックがあれば、ここに else if を追加
    }

    /**
     * コンストラクタ
     * 各UI要素、センサー、カメラの初期化を行う
     *
     * @param context             コンテキスト
     * @param lineChart           チャート表示用のLineChart
     * @param greenValueTextView  グリーン値を表示するTextView
     * @param ibiTextView         IBI値を表示するTextView
     * @param NowTextMessage      状態メッセージを表示するTextView
     * @param tvBpmSd             BPMの標準偏差を表示するTextView
     * @param tvHeartRate         心拍数を表示するTextView
     * @param smoothedIbiTextView 平滑化されたIBI値を表示するTextView
     * @param smoothedHRTextView  平滑化された心拍数を表示するTextView
     */
    public GreenValueAnalyzer(Context context, LineChart lineChart, TextView greenValueTextView,
                              TextView ibiTextView, TextView NowTextMessage,
                              TextView tvBpmSd, TextView tvHeartRate,
                              TextView smoothedIbiTextView, TextView smoothedHRTextView) {
        this.context = context;
        this.lineChart = lineChart;
        this.greenValueTextView = greenValueTextView;
        this.ibiTextView = ibiTextView;
        this.NowTextMessage = NowTextMessage;
        this.tvBpmSd = tvBpmSd;
        this.tvHeartRate = tvHeartRate;
        this.smoothedIbiTextView = smoothedIbiTextView;
        this.smoothedHRTextView = smoothedHRTextView;
        // ライフサイクルの監視を開始
        ((LifecycleOwner) context).getLifecycle().addObserver(this);
        // チャートのセットアップ
        setupChart();
        // カメラの起動
        startCamera();
        // SensorManagerの取得
    }

    // メインスレッドのHandler（UI更新用）
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 減衰係数（reductionFactor）を設定するメソッド
     *
     * @param reductionFactor 設定する減衰係数
     */
    public void setReductionFactor(float reductionFactor) {
        this.reductionFactor = reductionFactor;
    }

    /**
     * 記録データをクリアするメソッド
     */
    public void clearRecordedData() {
        recordedIbiTimeStamp.clear();
        recordedIbi.clear();
        recordedValue.clear();
        recordedValueTimeStamp.clear();
        recordedBpmSd.clear();
    }

    /**
     * チャートの初期設定を行うメソッド
     * 軸、色、範囲などを設定する
     */
    private void setupChart() {
        // データセットの作成
        lineDataSet = new LineDataSet(entries, "Value");
        lineDataSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        lineDataSet.setLineWidth(2f);
        lineDataSet.setColor(ContextCompat.getColor(context, R.color.green_color));
        lineData = new LineData(lineDataSet);
        lineChart.setData(lineData);
        lineChart.getDescription().setEnabled(false);

        // X軸の設定
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(ContextCompat.getColor(context, R.color.white)); // 軸の色を白に設定
        xAxis.setDrawGridLines(false); // グリッドラインを非表示に設定
        xAxis.setAxisLineColor(ContextCompat.getColor(context, R.color.white)); // 軸の色を白に設定

        // 左側Y軸の設定
        YAxis yAxisLeft = lineChart.getAxisLeft();
        yAxisLeft.setAxisMinimum(0);
        yAxisLeft.setAxisMaximum(255);
        yAxisLeft.setTextColor(ContextCompat.getColor(context, R.color.white)); // 軸の色を白に設定
        yAxisLeft.setDrawGridLines(false); // グリッドラインを非表示に設定
        yAxisLeft.setAxisLineColor(ContextCompat.getColor(context, R.color.white)); // 軸の色を白に設定

        // 右側Y軸の非表示
        YAxis yAxisRight = lineChart.getAxisRight();
        yAxisRight.setEnabled(false); // 右側の軸を非表示に設定
    }

    /**
     * カメラを起動するメソッド
     * すでにカメラが起動している場合はトーストで通知する
     */
    private void startCamera() {
        if (isCameraOpen) {
            Toast.makeText(context, "Camera is already open", Toast.LENGTH_SHORT).show();
            return;
        }

        // ProcessCameraProviderの非同期取得
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                // 画像解析用のバインディングを行う
                bindImageAnalysis(cameraProvider);
                isCameraOpen = true;
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * ImageAnalysisをカメラプロバイダーにバインドするメソッド
     *
     * @param cameraProvider ProcessCameraProviderのインスタンス
     */
    @OptIn(markerClass = androidx.camera.camera2.interop.ExperimentalCamera2Interop.class)
    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        // ImageAnalysisのビルダー設定
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(240, 180))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

        // Camera2Interopを用いてFPSレンジを設定
        Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(30, 30));

        // ImageAnalysisのインスタンス生成
        ImageAnalysis imageAnalysis = builder.build();

        // シングルスレッドのExecutorを作成
        Executor processingExecutor = Executors.newSingleThreadExecutor();

        // フロントカメラを選択
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        // Analyzerを設定して画像解析を行う
        imageAnalysis.setAnalyzer(processingExecutor, image -> processImage(image));

        // ライフサイクルにバインド
        cameraProvider.bindToLifecycle((LifecycleOwner) context, cameraSelector, imageAnalysis);
    }

    private LogicProcessor getActiveLogicProcessor() {
        switch (activeLogic) {
            case "Logic1":
                if (logic1Processor == null) logic1Processor = new Logic1();
                return logic1Processor;
            case "Logic2":
                if (logic2Processor == null) logic2Processor = new Logic2();
                return logic2Processor;
            case "Logic3":
                if (logic3Processor == null) logic3Processor = new Logic3();
                return logic3Processor;
            case "Logic4":
                if (logic4Processor == null) logic4Processor = new Logic4();
                return logic4Processor;
            case "Logic5":
                if (logic5Processor == null) logic5Processor = new Logic5();
                return logic5Processor;
            case "Logic6":
                if (logic6Processor == null) logic6Processor = new Logic6();
                return logic6Processor;
            default:
                return null;
        }
    }

    /**
     * Logicに処理を渡して、更新するメソッド
     */
    private void processImage(@NonNull ImageProxy image) {
        long currentTime = System.currentTimeMillis();
        recordedValueTimeStamp.add(currentTime);

        @OptIn(markerClass = ExperimentalGetImage.class)
        Image img = image.getImage();

        if (img != null && img.getFormat() == ImageFormat.YUV_420_888) {
            try {
                double avgG = getGreenValueFromImage(img);
                LogicProcessor processor = getActiveLogicProcessor();

                if (processor != null) {
                    avgG = processor.adjustImageBasedOnAmbientLight(img, avgG);
                    LogicResult result = processor.processGreenValueData(avgG);

                    // 記録
                    recordedValue.add(result.getCorrectedGreenValue());
                    recordedValueTimeStamp.add(System.currentTimeMillis());
                    recordedIbi.add(result.getIbi());
                    recordedIbiTimeStamp.add(System.currentTimeMillis());
                    recordedBpmSd.add(result.getBpmSd());

                    // UI更新
                    updateValueTextView(result.getCorrectedGreenValue());
                    updateChart(result.getCorrectedGreenValue());
                    updateIbiTextView(result.getIbi());
                    updateHRTextView(result.getHeartRate());
                    updateBpmSdTextView(result.getBpmSd());

                    // 平滑化と記録
                    processor.calculateSmoothedValueRealTime(result.getIbi(), result.getBpmSd());
                    double smoothedIbiValue = processor.getLastSmoothedIbi();
                    double smoothedBpmValue = (60 * 1000) / smoothedIbiValue;

                    recordedSmoothedIbi.add(smoothedIbiValue);
                    recordedSmoothedBpmSd.add(smoothedBpmValue);
                    updateSmoothedValues(smoothedIbiValue, smoothedBpmValue);
                } else {
                    Log.d("GreenValueAnalyzer", "No valid activeLogic. Skipping logic execution.");
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                image.close();
            }
        } else {
            image.close();
        }
    }

    /**
     * 画像からグリーン値を取得するメソッド
     */
    private double getGreenValueFromImage(Image img) {
        ByteBuffer uBuffer = img.getPlanes()[1].getBuffer();
        int width = img.getWidth();
        int height = img.getHeight();

        // 対象領域の設定（画像中央の1/2領域）
        int startX = width / 4;
        int startY = height / 4;
        int endX = 3 * width / 4;
        int endY = 3 * height / 4;

        int sumG = 0;
        int pixelCount = 0;

        // 画像全体を走査し、中央以外の部分でグリーン値を計算する
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // 中央以外の領域（エッジ部分）を対象とする
                if (y < startY || y >= endY || x < startX || x >= endX) {
                    int index = y * width + x;
                    if (index < uBuffer.capacity()) {
                        // バッファから値を取得（unsigned変換）
                        sumG += uBuffer.get(index) & 0xFF;
                        pixelCount++;
                    }
                }
            }
        }

        // ピクセル数が0より大きい場合に平均値を返す
        return pixelCount > 0 ? (double) sumG / pixelCount : 0;
    }


    /**
     * 状態をリセットするメソッド
     * 各リストや変数、UIを初期状態に戻す
     */
    public void reset() {
        // Logicクラス側にリセット情報を送る
        if (logic1Processor != null) {
            logic1Processor.reset();
        }
        if (logic2Processor != null) {
            logic2Processor.reset();
        }
        if (logic3Processor != null) {
            logic3Processor.reset();
        }
        if (logic4Processor != null) {
            logic4Processor.reset();
        }
        if (logic5Processor != null) {
            logic5Processor.reset();
        }
        if (logic6Processor != null) {
            logic6Processor.reset();
        }
        bpmSD = 0.0;
        flag = 0;
        updateBpmSdTextView(bpmSD);
        updateHRTextView(bpmValue);
        updateIbiTextView(IBI);
        updateSmoothedValues(0.0, 0.0);
        updateTextMessage(flag);
        isRecording = false;
        recordedIbiTimeStamp.clear();
        recordedIbi.clear();
        recordedValue.clear();
        recordedValueTimeStamp.clear();
        recordedBpmSd.clear();
    }


    /**
     * 記録モードを開始するメソッド
     */
    public void startRecording() {
        isRecording = true;
    }

    /**
     * 記録モードを停止するメソッド
     */
    public void stopRecording() {
        isRecording = false;
    }


    /**
     * 状態メッセージを更新するメソッド
     * flagの値に応じて「測定中」または「キャリブレーション中」を表示する
     *
     * @param flag 状態フラグ（1：測定中、0：キャリブレーション中）
     */
    private void updateTextMessage(int flag) {
        Handler mainHandler = new Handler(context.getMainLooper());

        if (flag == 1) {
            mainHandler.post(() -> NowTextMessage.setText("測定中"));
        } else if (flag == 0) {
            mainHandler.post(() -> NowTextMessage.setText("キャリブレーション中"));
        }
    }


    private void updateValueTextView(double value) {
        uiHandler.post(() -> {
            greenValueTextView.setText("Value : " + String.format(Locale.getDefault(), "%.2f", value));
            greenValueTextView.invalidate();
        });
    }

    private void updateChart(double value) {
        uiHandler.post(() -> {
            if (entries.size() > 100) {
                entries.remove(0);
                for (int i = 0; i < entries.size(); i++) {
                    entries.get(i).setX(i);
                }
            }
            entries.add(new Entry(entries.size(), (float) value));

            float minY = entries.stream().map(Entry::getY).min(Float::compare).orElse(0f);
            float maxY = entries.stream().map(Entry::getY).max(Float::compare).orElse(255f);
            float margin = 10f;
            YAxis yAxisLeft = lineChart.getAxisLeft();
            yAxisLeft.setAxisMinimum(minY - margin);
            yAxisLeft.setAxisMaximum(maxY + margin);

            lineDataSet.notifyDataSetChanged();
            lineData.notifyDataChanged();
            lineChart.notifyDataSetChanged();
            lineChart.setVisibleXRangeMaximum(200);
            lineChart.moveViewToX(lineData.getEntryCount());
            lineChart.invalidate();
        });
    }

    private void updateIbiTextView(double ibi) {
        uiHandler.post(() -> {
            ibiTextView.setText("IBI : " + String.format(Locale.getDefault(), "%.2f", ibi));
            ibiTextView.invalidate();
        });
    }

    private void updateHRTextView(double bpm) {
        uiHandler.post(() -> {
            tvHeartRate.setText("HeartRate : " + String.format(Locale.getDefault(), "%.2f", bpm));
            tvHeartRate.invalidate();
        });
    }

    private void updateSmoothedValues(double smoothedIbiValue, double smoothedBpmValue) {
        uiHandler.post(() -> {
            smoothedIbiTextView.setText("Smoothed IBI : " + String.format(Locale.getDefault(), "%.2f", smoothedIbiValue));
            smoothedHRTextView.setText("Smoothed HeartRate : " + String.format(Locale.getDefault(), "%.2f", smoothedBpmValue));
            smoothedIbiTextView.invalidate();
            smoothedHRTextView.invalidate();
        });
    }

    private void updateBpmSdTextView(double bpmSd) {
        // 既存の updateBpmSdTextView() は同様の手法で修正可能です
        uiHandler.post(() -> {
            tvBpmSd.setText("BPMSD : " + String.format(Locale.getDefault(), "%.2f", bpmSd));
            tvBpmSd.invalidate();
        });
    }

    /**
     * 最新のIBI値を返すメソッド
     *
     * @return 最新のIBI値
     */
    public double getLatestIbi() {
        if (IBI != 0) {
            return IBI;
        }
        return 0;
    }


    /**
     * IBIデータをCSVファイルに保存するメソッド
     *
     * @param fileName 保存するファイル名（接頭辞）
     */
    public void saveIbiToCsv(String fileName) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, fileName + "_IBI_data.csv");

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.append("IBI, bpmSD, Smoothed IBI, Smoothed BPM SD, Timestamp\n");
            // 各記録データをCSV形式で書き出す
            for (int i = 0; i < recordedIbi.size(); i++) {
                String timestamp = sdf.format(new Date(recordedIbiTimeStamp.get(i)));
                writer.append(String.format("%.2f", recordedIbi.get(i))).append(", ")
                        .append(String.format("%.2f", recordedBpmSd.get(i))).append(", ")
                        .append(String.format("%.2f", recordedSmoothedIbi.get(i))).append(", ")
                        .append(String.format("%.2f", recordedSmoothedBpmSd.get(i))).append(", ")
                        .append(timestamp).append("\n");
            }

            mainHandler.post(() -> Toast.makeText(context, "IBIデータを保存しました", Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            e.printStackTrace();
            mainHandler.post(() -> Toast.makeText(context, "IBIデータの保存に失敗しました", Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * グリーン値データをCSVファイルに保存するメソッド
     *
     * @param fileName 保存するファイル名（接頭辞）
     */
    public void saveGreenValuesToCsv(String fileName) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
        File downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File csvFile = new File(downloadFolder, fileName + "_GreenValues_data.csv");

        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.append("Green Value, Timestamp\n");
            // 各記録されたグリーン値をCSV形式で書き出す
            for (int i = 0; i < recordedValue.size(); i++) {
                String timestamp = sdf.format(new Date(recordedValueTimeStamp.get(i)));
                writer.append(String.format("%.2f", recordedValue.get(i))).append(", ").append(timestamp).append("\n");
            }
            Toast.makeText(context, "緑値データを保存しました", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(context, "緑値データの保存に失敗しました", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * リソースの解放・カメラ停止を行うメソッド
     */
    public void stop() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        isCameraOpen = false;
    }
}