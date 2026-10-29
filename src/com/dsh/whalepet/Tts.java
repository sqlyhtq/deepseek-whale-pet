package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** 统一的声音出口：系统 TTS 或在线神经语音（Edge TTS），失败自动回落。 */
public class Tts {

    public static final String PREF_VOICE = "ttsVoice";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private TextToSpeech sys;
    private File lastFile;
    private static long breezeDownUntil;
    private static long lastSpeakAt;   // 熔断：Breeze 失败后一段时间直接走 Edge
    private Runnable pendingDone;

    public Tts(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 文本+音色的缓存文件，同一句话只合成一次。 */
    private File cacheFile(String voice, String text) {
        String key = (voice + "|" + text).hashCode() + "_" + text.length();
        return new File(ctx.getCacheDir(), "tts_" + Math.abs(key.hashCode()) + ".mp3");
    }

    public void speak(final String text) {
        speak(text, null);
    }

    public void speak(final String text, final Runnable onDone) {
        pendingDone = onDone;
        final String voice = prefs().getString(PREF_VOICE, "dafeiyu-clever");
        Log.i("WhalePet", "speak() voice=" + voice + " text=" + text);

        // 同一句话缓存过就直接播，省掉网络往返
        final long t0 = System.currentTimeMillis();
        lastSpeakAt = t0;
        final File cached = cacheFile(voice, text);
        if (cached.exists() && cached.length() > 1024) {
            Log.i("WhalePet", "tts cache hit " + cached.length() + "B");
            main.post(() -> play(cached));
            return;
        }
        if ("system".equals(voice)) {
            speakSystem(text);
            return;
        }
        if (voice.startsWith("dafeiyu")) {
            final String key = prefs().getString("breezyKey", "").trim();
            if (key.isEmpty()) {
                Log.w("WhalePet", "breeze key missing, fallback to edge");
                new Thread(() -> synthEdge(text, "xiaoyi")).start();
                return;
            }
            if (System.currentTimeMillis() < breezeDownUntil) {
                Log.w("WhalePet", "breeze circuit open, use edge");
                new Thread(() -> synthEdge(text, "xiaoyi")).start();
                return;
            }
            final String vid = "dafeiyu-dreamy".equals(voice) ? BreezeTts.DREAMY : BreezeTts.CLEVER;
            new Thread(() -> {
                try {
                    byte[] mp3 = BreezeTts.synth(text, key, vid);
                    Log.i("WhalePet", "breeze tts ok: " + voice + " bytes=" + mp3.length);
                    final File f = cacheFile(voice, text);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        fo.write(mp3);
                    }
                    main.post(() -> play(f));
                } catch (Exception e) {
                    breezeDownUntil = System.currentTimeMillis() + 10 * 60 * 1000;
                    Log.w("WhalePet", "breeze-tts failed, circuit open 10min: " + e);
                    synthEdge(text, "xiaoyi");
                }
            }).start();
            return;
        }
        synthEdge(text, voice);
    }

    private void synthEdge(final String text, final String voice) {
        new Thread(() -> {
            try {
                byte[] mp3 = EdgeTts.synth(text, EdgeTts.voiceName(voice));
                Log.i("WhalePet", "edge tts ok: " + voice + " bytes=" + mp3.length);
                final File f = new File(ctx.getCacheDir(), "tts_" + System.nanoTime() + ".mp3");
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(mp3);
                }
                main.post(() -> play(f));
            } catch (Exception e) {
                Log.w("WhalePet", "edge-tts failed: " + e);
                main.post(() -> speakSystem(text));
            }
        }).start();
    }

    private void play(File f) {
        stop();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                m.release();
                if (player == m) player = null;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                fireDone();
            });
            mp.prepare();
            mp.start();
            lastFile = f;
        } catch (Exception e) {
            Log.w("WhalePet", "play failed: " + e);
            speakSystem("");
        }
    }

    private void fireDone() {
        final Runnable cb = pendingDone;
        pendingDone = null;
        if (cb != null) main.post(cb);
    }

    private void speakSystem(String text) {
        if (text == null || text.isEmpty()) return;
        if (sys == null) {
            sys = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    sys.setLanguage(Locale.CHINA);
                    sys.setSpeechRate(1.02f);
                }
            });
        }
        try {
            sys.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    fireDone();
                }

                @Override
                public void onError(String utteranceId) {
                    fireDone();
                }
            });
            sys.speak(text, TextToSpeech.QUEUE_FLUSH, null, "whale");
        } catch (Throwable ignored) {
        }
    }

    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (Throwable ignored) {
            }
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        if (sys != null) {
            try {
                sys.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    public void release() {
        stop();
        if (sys != null) {
            sys.shutdown();
            sys = null;
        }
        if (lastFile != null) {
            //noinspection ResultOfMethodCallIgnored
            lastFile.delete();
            lastFile = null;
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("pet", Context.MODE_PRIVATE);
    }
}
