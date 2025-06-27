package com.example.realtimehribicontrol;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import com.leff.midi.MidiFile;
import com.leff.midi.MidiTrack;
import com.leff.midi.event.MidiEvent;
import com.leff.midi.event.NoteOn;
import com.leff.midi.event.meta.Tempo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

/**
 * MidiHaptic
 * ────────────────────────────────────────
 * • android-midi-lib でMIDIファイルを解析
 * • MediaPlayer で音声を再生し、解析結果と同期してハプティクスを生成
 * • API 31+ では VibrationEffect.Composition を用いて多彩な表現
 * • API 31未満では createOneShot にフォールバック
 * • 最新3回の Smoothed BPM 平均＋オフセットでテンポを動的調整
 */
public class MidiHaptic {
    private static final String TAG = "MidiHaptic";

    // 内部データクラス (変更なし)
    private static class MidiEventData {
        long timestampMs;
        int noteNumber;
        int velocity;
        int channel;

        MidiEventData(long timestampMs, int noteNumber, int velocity, int channel) {
            this.timestampMs = timestampMs;
            this.noteNumber = noteNumber;
            this.velocity = velocity;
            this.channel = channel;
        }
    }

    private final Context ctx;
    private final GreenValueAnalyzer analyzer;
    private final double tempoOffsetRatio;

    private final MediaPlayer player;
    private final Vibrator vibrator;
    private final boolean useComposition;

    private final Handler bpmPollHandler = new Handler(Looper.getMainLooper());
    private final Handler hapticHandler = new Handler(Looper.getMainLooper());
    private Runnable bpmPollTask;
    private Runnable hapticPlaybackTask;

    private List<MidiEventData> midiEvents;
    private int nextEventIndex = 0;
    private long lastPositionMs = 0;
    private boolean isPlaying = false;

    // 【修正①】MIDIファイルのURIを保持するフィールドを追加
    private final Uri midiUri;
    private double midiFileBaseTempo = 120.0; // ※既存のまま

    private final Deque<Double> recentBpm = new ArrayDeque<>(3);
    private static final int BPM_HISTORY = 3;
    private static final int HAPTIC_ADVANCE_MS = 70;

    public MidiHaptic(Context context, GreenValueAnalyzer analyzer, Uri midiUri, double tempoOffsetRatio) {
        this.ctx = context;
        this.analyzer = analyzer;
        this.tempoOffsetRatio = tempoOffsetRatio;
        this.player = new MediaPlayer();
        this.midiUri = midiUri; // 【修正③】コンストラクタで受け取ったURIを保持

        // 1. Vibrator (or VibratorManager) の初期化 (変更なし)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vibratorManager = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            this.vibrator = vibratorManager.getDefaultVibrator();
        } else {
            this.vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
        this.useComposition = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;

        // 【修正④】コンストラクタではMediaPlayerの準備のみ行い、MIDI解析は行わない
        try {
            if (midiUri != null) {
                player.setDataSource(ctx, midiUri);
            } else {
                AssetFileDescriptor afd = ctx.getResources().openRawResourceFd(R.raw.drum);
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
            player.prepare();
        } catch (IOException e) {
            Log.e(TAG, "MediaPlayerの準備に失敗", e);
            throw new RuntimeException("MediaPlayerの準備に失敗", e);
        }

        // 3. タスクの定義 (変更なし)
        initializeTasks();
    }

    // setupMediaPlayerAndParseMidiは不要になったので削除

    private void parseMidiFile(InputStream inputStream) throws IOException {
        MidiFile midi = new MidiFile(inputStream);

        // 基本テンポを読み取り、midiFileBaseTempoに保存
        for(MidiTrack track : midi.getTracks()) {
            for(MidiEvent event : track.getEvents()) {
                if(event instanceof Tempo) {
                    this.midiFileBaseTempo = ((Tempo) event).getBpm();
                    break;
                }
            }
        }
        Log.d(TAG, "Base tempo from MIDI: " + this.midiFileBaseTempo);

        this.midiEvents = new ArrayList<>();
        int resolution = midi.getResolution();

        for (int i = 0; i < midi.getTrackCount(); i++) {
            MidiTrack track = midi.getTracks().get(i);
            Iterator<MidiEvent> it = track.getEvents().iterator();
            while (it.hasNext()) {
                MidiEvent event = it.next();
                if (event instanceof NoteOn) {
                    NoteOn note = (NoteOn) event;
                    if (note.getVelocity() > 0) {
                        // タイムスタンプの計算にはMIDIファイル本来のテンポ(midiFileBaseTempo)を使用
                        long timestampMs = (long) (note.getTick() * 60000.0 / (resolution * this.midiFileBaseTempo));
                        midiEvents.add(new MidiEventData(timestampMs, note.getNoteValue(), note.getVelocity(), note.getChannel()));
                    }
                }
            }
        }
        Collections.sort(midiEvents, Comparator.comparingLong(e -> e.timestampMs));
        Log.d(TAG, "Parsed " + midiEvents.size() + " NoteOn events.");
    }

    private void initializeTasks() {
        // BPMポーリング＋テンポ更新タスク
        bpmPollTask = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying) return;

                double bpm = analyzer.getLatestSmoothedBpm();
                // Log.d(TAG, "BPM Poll: analyzer.getLatestSmoothedBpm() returned " + bpm);

                if (bpm <= 0) {
                    double ibi = analyzer.getLatestIbi();            // ← GreenValueAnalyzer に既存
                    if (ibi > 0) bpm = 60_000.0 / ibi;               // ms → bpm
                }

                if (bpm > 0) {
                    // Log.d(TAG, "BPM > 0. Updating tempo with offset: " + tempoOffsetRatio);
                    if (recentBpm.size() >= BPM_HISTORY) {
                        recentBpm.poll();
                    }
                    recentBpm.offer(bpm);

                    double sum = 0;
                    for (double b : recentBpm) sum += b;
                    double avg = sum / recentBpm.size();
                    double targetTempo = avg * (1.0 + tempoOffsetRatio);
                    updateTempo(targetTempo);
                }
                bpmPollHandler.postDelayed(this, 1000);
            }
        };

        // ハプティック再生タスク (変更なし)
        hapticPlaybackTask = new Runnable() {
            @Override
            public void run() {
                if (!isPlaying || player == null || !player.isPlaying()) return;
                try {
                    /* ──────────── ここから既存＋追加 ──────────── */
                    long currentPos = player.getCurrentPosition();

                    // ★追加【ループ検出】
                    if (currentPos < lastPositionMs) {   // 0 に戻った＝ループ開始
                        nextEventIndex = 0;              // イベントを先頭から再送
                    }
                    lastPositionMs = currentPos;         // ★追加 直近位置を保持

                    long hapticCheckPos = currentPos + HAPTIC_ADVANCE_MS;
                    /* ──────────── ここまで ──────────── */

                    while (midiEvents != null
                            && nextEventIndex < midiEvents.size()
                            && midiEvents.get(nextEventIndex).timestampMs <= hapticCheckPos) {

                        triggerHaptic(midiEvents.get(nextEventIndex));
                        nextEventIndex++;
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayerが不正な状態です", e);
                }
                hapticHandler.postDelayed(this, 20);
            }
        };
    }

    private void triggerHaptic(MidiEventData event) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        boolean isDrum = event.channel == 9;

        if (useComposition) {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            float scale = event.velocity / 127.0f;
            if (isDrum) {
                // 【修正⑤】VibrationEffect.PRIMITIVE_CLICK が正しい定数名
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 30);
            } else {
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, scale, 50);
            }
            vibrator.vibrate(composition.compose());
        } else {
            int amplitude = Math.max(1, Math.min(255, (int)(event.velocity / 127.0 * 255)));
            long duration = isDrum ? 50 : 35;
            VibrationEffect effect = VibrationEffect.createOneShot(duration, amplitude);
            vibrator.vibrate(effect);
        }
    }

    public void start() {
        if (isPlaying) return;

        // 【修正⑥】副作用を避けるため、再生開始直前にMIDI解析を実行
        try {
            InputStream inputStream;
            if (this.midiUri != null) {
                inputStream = ctx.getContentResolver().openInputStream(this.midiUri);
            } else {
                inputStream = ctx.getResources().openRawResource(R.raw.drum);
            }
            if (inputStream != null) {
                parseMidiFile(inputStream);
                inputStream.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "start()内でのMIDI解析に失敗", e);
            // 解析に失敗した場合でも、ハプティクスなしで再生は継続する
        }

        isPlaying = true;

        player.setLooping(true);
        player.start();

        nextEventIndex = 0;
        lastPositionMs = 0;
        hapticHandler.post(hapticPlaybackTask);
        bpmPollHandler.post(bpmPollTask);
        Log.d(TAG, "MidiHaptic started.");
    }

    public void stop() {
        // isPlayingのセットを最初に行う
        if (!isPlaying) return;
        isPlaying = false;

        bpmPollHandler.removeCallbacks(bpmPollTask);
        hapticHandler.removeCallbacks(hapticPlaybackTask);

        if (vibrator != null) {
            vibrator.cancel();
        }

        // playerの状態をチェックしてから操作
        if (player != null) {
            if (player.isPlaying()) {
                player.stop();
            }
            player.release();
        }
        Log.d(TAG, "MidiHaptic stopped.");
    }

    private void updateTempo(double targetTempo) {
        if (player == null || !isPlaying) return;

        // 【修正⑦】元のプログラムと同様に、常に固定値(120.0)を基準として再生速度を計算
        float baseTempo = (midiFileBaseTempo > 0.0) ? (float) midiFileBaseTempo : 120f;
        float speed = (float) (targetTempo / baseTempo);

        speed = Math.max(0.5f, Math.min(4.0f, speed));

        try {
            PlaybackParams params = player.getPlaybackParams();
            params.setSpeed(speed);
            player.setPlaybackParams(params);
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.w(TAG, "テンポ更新失敗 speed=" + speed, e);
        }
    }
}