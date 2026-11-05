package com.dsh.whalepet;

import android.content.Context;
import android.util.Log;

/** 「小鲸小鲸」唤醒：用离线 Vosk 持续听，只匹配关键词；离开桌面就停。 */
public class WakeWord {

    private static final String TAG = "WhalePet";
    private static final String DEFAULT_WAKE = "小鲸小鲸";
    private static final String[] DEFAULT_KEYS = {"小鲸小鲸", "小鲸", "鲸鱼娘"};

    /** 用户自定义的唤醒词（设置里可改）。 */
    public static String currentWord(Context ctx) {
        String w = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)
                .getString("wakeWord", DEFAULT_WAKE);
        if (w == null) return DEFAULT_WAKE;
        w = w.replace(" ", "").trim();
        return w.isEmpty() ? DEFAULT_WAKE : w;
    }

    private static String[] keys(Context ctx) {
        String custom = currentWord(ctx);
        if (DEFAULT_WAKE.equals(custom)) return DEFAULT_KEYS;
        return new String[]{custom, shortForm(custom), DEFAULT_WAKE};
    }

    /** 两字以上的唤醒词，取前两个字也认（「大肥鱼」→「大肥」）。 */
    private static String shortForm(String w) {
        return w.length() >= 3 ? w.substring(0, 2) : w;
    }

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

    private static boolean keysMatch(String text, String[] keys) {
        String t = text.replace(" ", "");
        for (String k : keys) {
            if (k.isEmpty()) continue;
            int i = t.indexOf(k);
            if (i >= 0 && i <= 3) return true;
        }
        return false;
    }

    public static boolean matchesKeyword(Context ctx, String text) {
        if (text == null) return false;
        String t = text.replace(" ", "");
        if (keysMatch(t, keys(ctx))) return true;
        // 默认唤醒词才启用「小X」同音容错；自定义词用编辑距离兜底
        String custom = currentWord(ctx);
        if (DEFAULT_WAKE.equals(custom)) {
            return WAKE.matcher(t).find();
        }
        return fuzzyHit(t, custom);
    }

    public static boolean matchesKeyword(String text) {
        return matchesKeyword(null, text);
    }

    /** 自定义词：句首 6 个字内，编辑距离 ≤1（长词允许 2）。 */
    private static boolean fuzzyHit(String text, String word) {
        int max = word.length() >= 4 ? 2 : 1;
        int head = Math.min(text.length(), word.length() + 3);
        String seg = text.substring(0, head);
        for (int i = 0; i + word.length() <= seg.length(); i++) {
            String win = seg.substring(i, i + word.length());
            if (lev(win, word) <= max) return true;
        }
        return false;
    }

    private static int lev(String a, String b) {
        int[][] d = new int[a.length()+1][b.length()+1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                d[i][j] = Math.min(Math.min(d[i-1][j]+1, d[i][j-1]+1),
                        d[i-1][j-1] + (a.charAt(i-1)==b.charAt(j-1) ? 0 : 1));
        return d[a.length()][b.length()];
    }

    /** 把识别文本开头的唤醒词剥掉，剩下的话再送给模型。 */
    /** 把识别文本开头的唤醒词剥掉，剩下的话再送给模型。 */
    public static String stripWake(Context ctx, String text) {
        if (text == null) return "";
        String t = text.trim();
        String w = currentWord(ctx);
        if (!w.isEmpty() && t.startsWith(w)) {
            return t.substring(w.length()).replaceFirst("^[，,\\s]+", "").trim();
        }
        t = t.replaceFirst("^(小[鲸经静景晶京惊]小[鲸经静景晶京惊]|小鲸鱼|鲸鱼娘|小[鲸经静景晶京惊])[，,\\s]*", "");
        return t.trim();
    }

    public static String stripWake(String text) {
        return stripWake(null, text);
    }

    private void check(String text) {
        if (!running || text == null) return;
        if (System.currentTimeMillis() - lastHit < 4000) return;
        if (!matchesKeyword(ctx, text)) return;
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
