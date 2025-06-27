// ─────────────────── GreenValueAnalyzer.java ───────────────────
package com.example.realtimehribicontrol;

import android.content.Context;
import android.graphics.ImageFormat;
import android.media.Image;
import android.os.*;
import android.hardware.camera2.CaptureRequest;
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

public class GreenValueAnalyzer implements LifecycleObserver {

    // ===== UI =====
    private final Context ctx;
    private final LineChart chart;
    private final TextView tvValue, tvIbi, tvMsg, tvSd, tvHr, tvSmIbi, tvSmHr;

    // ===== グラフデータ =====
    private final List<Entry> entries = new ArrayList<>();
    private final LineDataSet dataSet;
    private final LineData data;
    private String activeLogic = "Logic1";

    // ===== 記録 =====
    private final List<Double> recValue = new ArrayList<>(),
            recIbi   = new ArrayList<>(),
            recSd    = new ArrayList<>(),
            recSmIbi = new ArrayList<>(),
            recSmBpm = new ArrayList<>();
    private final List<Long>   recValTs = new ArrayList<>(),
            recIbiTs = new ArrayList<>();

    // ===== 状態 =====
    private boolean camOpen;
    private double  IBI;
    private boolean isRecordingActive = false;

    // ===== ロジック =====
    private final Map<String, LogicProcessor> logicMap = new HashMap<>();

    // ===== ハンドラ =====
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 外部から注入されるBP推定器
    private RealtimeBP bpEstimator;

    // MainActivity側の同じReatimeBPをセット
    public void setBpEstimator(RealtimeBP estimator) {
        this.bpEstimator = estimator;
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

        chart.getLegend().setEnabled(false);
        dataSet = new LineDataSet(entries, "");
        dataSet.setLineWidth(2);
        dataSet.setColor(Color.parseColor("#78CCCC"));
        dataSet.setDrawValues(false);  // 値ラベルを非表示

        data = new LineData(dataSet);
        chart.setData(data);
        chart.getDescription().setEnabled(false);

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
        logicMap.computeIfAbsent(n, k -> {
            switch (k) {
                default:       return new Logic1();
                case "Logic2": return new Logic2();
            }
        });
    }

    public double getLatestIbi() { return IBI; }

    // ===== カメラ起動 =====
    public void startCamera() {
        Logic1 l1 = (Logic1) logicMap.computeIfAbsent("Logic1", k -> new Logic1());
        if (bpEstimator != null) {
            l1.setBPFrameCallback(bpEstimator::update);
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
        // ImageAnalysis のビルダー設定
        ImageAnalysis.Builder builder = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(240, 180))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);

        // Camera2Interop を用いて FPS レンジを設定
        Camera2Interop.Extender ext = new Camera2Interop.Extender<>(builder);
        ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                new Range<>(30, 30));

        // ImageAnalysis のインスタンス生成
        ImageAnalysis imageAnalysis = builder.build();

        // シングルスレッドの Executor を作成
        Executor processingExecutor = Executors.newSingleThreadExecutor();

        // フロントカメラを選択
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        // Analyzer を設定して画像解析を行う
        imageAnalysis.setAnalyzer(processingExecutor, this::processImage);

        // ライフサイクルにバインド
        cameraProvider.bindToLifecycle(
                (LifecycleOwner) ctx,
                cameraSelector,
                imageAnalysis);
    }

    // ===== フレーム解析 =====
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void processImage(ImageProxy proxy) {
        Image img = proxy.getImage();
        if (img != null &&
                img.getFormat() == ImageFormat.YUV_420_888) {

            double g = getGreen(img);
            LogicProcessor lp = logicMap.get(activeLogic);

            if (lp != null) {
                LogicResult r = lp.processGreenValueData(g);
                lp.calculateSmoothedValueRealTime(r.getIbi(), r.getBpmSd());

                // ★ isRecordingActive フラグが true の場合のみデータを記録
                if (isRecordingActive) {
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
            data.notifyDataChanged();
            chart.notifyDataSetChanged();
            chart.setVisibleXRangeMaximum(200);
            chart.moveViewToX(data.getEntryCount());
            chart.invalidate();
        });
    }

    // ===== リセット ／ 記録制御 =====
    public void reset() {
        // 各 LogicProcessor 実装の reset() を呼び出す
        for (LogicProcessor lp : logicMap.values()) {
            if (lp instanceof Logic1)    ((Logic1)lp).reset();
            else if (lp instanceof Logic2)((Logic2)lp).reset();
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
            // ヘッダー行
            writer.append("IBI, bpmSD, Smoothed IBI, Smoothed BPM, SBP, DBP, SBP_Avg, DBP_Avg, Timestamp\n");

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
        saveCsv(name + "_Green",
                Arrays.asList("Green", "Timestamp"),
                recValue, recValTs);
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
}