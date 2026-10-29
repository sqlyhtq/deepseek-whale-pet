package com.dsh.whalepet;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 离线中文语音输入（Vosk）。这台机器没有系统 SpeechRecognizer，
 * 所以自带 small-cn 模型，16k 单声道录音，部分结果 + 静音断句。
 */
public class VoiceInput {

    private int silenceMs = 1200;

    /** 说完后多久没声音就收尾（唤醒后的连续对话用 3.5s）。 */
    public void setSilenceMs(int ms) {
        silenceMs = Math.max(500, Math.min(8000, ms));
    }


    private static final String TAG = "WhalePet";
    private static final int RATE = 16000;
    private static Model model;

    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioRecord record;
    private Recognizer rec;
    private volatile boolean running;
    private Thread thread;

    /** 首次运行把 assets 里的模型拷到 filesDir（约 40MB，只需一次）。 */
    /** 有下载好的大模型就优先用（准确率高），没有回退内置小模型。 */
    private static Model bigModel(Context ctx) {
        try {
            if (!BigModel.isReady(ctx)) return null;
            File d = BigModel.dir(ctx);
            Log.i("WhalePet", "loading big model from " + d);
            return new Model(d.getAbsolutePath());
        } catch (OutOfMemoryError oom) {
            Log.w("WhalePet", "big model OOM, fallback to small");
            return null;
        } catch (Throwable t) {
            Log.w("WhalePet", "big model load failed: " + t);
            return null;
        }
    }

    /** 换模型后调用，让下次识别重新加载。 */
    public static synchronized void reset() {
        model = null;
    }

    public static synchronized Model model(Context ctx) throws Exception {
        if (model != null) return model;
        boolean wantBig = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)
                .getString("asrModel", "big").equals("big");
        Model big = wantBig ? bigModel(ctx) : null;
        if (big != null) {
            model = big;
            return model;
        }
        File dir = new File(ctx.getFilesDir(), "vosk-cn");
        File ok = new File(dir, ".ready");
        if (!ok.exists()) {
            copyAssetDir(ctx, "vosk", dir);
            //noinspection ResultOfMethodCallIgnored
            ok.createNewFile();
        }
        model = new Model(dir.getAbsolutePath());
        return model;
    }

    private static void copyAssetDir(Context ctx, String assetPath, File dst) throws Exception {
        String[] children = ctx.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            dst.getParentFile().mkdirs();
            try (InputStream in = ctx.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        dst.mkdirs();
        for (String c : children) copyAssetDir(ctx, assetPath + "/" + c, new File(dst, c));
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * @param stopOnSilence true = 说完静音就结束（对话用）；false = 一直听（唤醒用）
     */
    public void start(final Context ctx, final Listener listener, final boolean stopOnSilence) {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            try {
                Model m = model(ctx);
                rec = new Recognizer(m, RATE);
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(min, RATE / 5 * 2);
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                record.startRecording();

                short[] buf = new short[1600];
                long tStart = System.currentTimeMillis();
                long totalBytes = 0;
                double maxRms = 0;
                int partials = 0;
                int aliveTicks = 0;
                long lastVoice = 0;
                boolean heard = false;
                long started = System.currentTimeMillis();
                while (running) {
                    int n = record.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    long now = System.currentTimeMillis();
                    totalBytes += n;
                    double rms = rms(buf, n);
                    if (rms > maxRms) maxRms = rms;
                    if (++aliveTicks % 30 == 0) {
                        // 每 ~3 秒报一次电平，用来判断麦克风到底有没有进数据
                        Log.i(TAG, "voice alive bytes=" + totalBytes + " maxRms=" + (int) maxRms
                                + " partials=" + partials);
                        maxRms = 0;
                    }
                    if (rms > 900) {
                        heard = true;
                        lastVoice = now;
                    }
                    if (rec.acceptWaveForm(buf, n)) {
                        String text = textOf(rec.getResult(), "text");
                        if (!text.isEmpty()) main.post(() -> listener.onFinal(text));
                        if (stopOnSilence) break;
                    } else {
                        String part = textOf(rec.getPartialResult(), "partial");
                        if (!part.isEmpty()) {
                            partials++;
                            main.post(() -> listener.onPartial(part));
                        }
                    }
                    if (stopOnSilence) {
                        if (heard && now - lastVoice > silenceMs) break;
                        if (now - started > 30000) break;
                    }
                }
                if (stopOnSilence) {
                    String text = textOf(rec.getFinalResult(), "text");
                    final String t = text.isEmpty() ? "" : text;
                    main.post(() -> listener.onFinal(t));
                }
            } catch (Throwable t) {
                Log.w(TAG, "voice input failed: " + t);
                final String msg = String.valueOf(t.getMessage());
                main.post(() -> listener.onError(msg));
            } finally {
                running = false;
                release();
            }
        }, "whale-voice");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(800);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
        release();
    }

    private void release() {
        try {
            if (record != null) {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
                record.release();
            }
        } catch (Throwable ignored) {
        }
        record = null;
        try {
            if (rec != null) rec.close();
        } catch (Throwable ignored) {
        }
        rec = null;
    }

    private static double rms(short[] b, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += (long) b[i] * b[i];
        return Math.sqrt((double) sum / Math.max(1, n));
    }

    private static String textOf(String json, String key) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
