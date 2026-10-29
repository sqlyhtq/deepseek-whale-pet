package com.dsh.whalepet;

import android.content.Context;
import android.util.Log;

/** 「小鲸小鲸」唤醒：用离线 Vosk 持续听，只匹配关键词；离开桌面就停。 */
public class WakeWord {

    private static final String TAG = "WhalePet";
    private static final String[] KEYS = {"小鲸小鲸", "小鲸", "鲸鱼娘"};

    public interface Listener {
        void onWake(String heard);
    }

    private final Context ctx;
    private VoiceInput vi;
    private volatile boolean running;
    private Listener listener;
    private long lastHit;
    private String lastHeard = "";

    public WakeWord(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Listener l) {
        if (running) return;
        listener = l;
        running = true;
        vi = new VoiceInput();
        vi.start(ctx, new VoiceInput.Listener() {
            @Override
            public void onPartial(String text) {
                if (text != null && text.length() >= 2 && !text.equals(lastHeard)) {
                    lastHeard = text;
                    Log.i(TAG, "wake heard: [" + text + "]");
                }
                check(text);
            }

            @Override
            public void onFinal(String text) {
                check(text);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "wake voice error: " + message);
                stop();
            }
        }, false);
        Log.i(TAG, "wake listening started (vosk)");
    }

    /**
     * 关键词必须出现在句子最前面（≤3 个字），避免她说长句时被自己的回声、或聊天里
     * 顺带提到「小鲸」而误唤醒。
     */
    // 语音识别常把「小鲸」听成这些同音字
    private static final java.util.regex.Pattern WAKE = java.util.regex.Pattern.compile(
            "^(小[鲸经静景晶京惊]|鲸鱼娘|小鲸鱼)");

    public static boolean matchesKeyword(String text) {
        if (text == null) return false;
        String t = text.replace(" ", "");
        for (String k : KEYS) {
            int i = t.indexOf(k);
            if (i >= 0 && i <= 3) return true;
        }
        // 同音容错：只认句首出现的「小X」
        return WAKE.matcher(t).find();
    }

    /** 把识别文本开头的唤醒词剥掉，剩下的话再送给模型。 */
    public static String stripWake(String text) {
        if (text == null) return "";
        String t = text.trim();
        t = t.replaceFirst("^(小[鲸经静景晶京惊]小[鲸经静景晶京惊]|小鲸鱼|鲸鱼娘|小[鲸经静景晶京惊])[，,\\s]*", "");
        return t.trim();
    }

    private void check(String text) {
        if (!running || text == null) return;
        if (System.currentTimeMillis() - lastHit < 4000) return;
        if (!matchesKeyword(text)) return;
        Log.i(TAG, "wake hit: " + text);
        lastHit = System.currentTimeMillis();
        running = false;
        Listener l = listener;
        stop();
        if (l != null) l.onWake(text);
    }

    public void stop() {
        running = false;
        if (vi != null) {
            vi.stop();
            vi = null;
        }
    }
}
