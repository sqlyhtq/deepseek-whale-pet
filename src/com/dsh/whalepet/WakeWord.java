package com.dsh.whalepet;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 唤醒词监听：用离线 Vosk 持续听，只匹配关键词；离开桌面就停。
 * 唤醒词可在设置里自定义（第一个是主词，其余用 | 或、分隔当同音别名），只存本机。
 */
public class WakeWord {

    private static final String TAG = "WhalePet";

    /** 主唤醒词与别名（来自设置页，默认「大肥鱼」）。 */
    private static volatile String CUSTOM = "大肥鱼";
    private static volatile String[] ALIASES = new String[0];

    /** 「大肥鱼」的逐字同音容错：Vosk 小模型常把三个字都听成同音字。 */
    private static final java.util.regex.Pattern WAKE_DEFAULT = java.util.regex.Pattern.compile(
            "^(大|打|达|嗒)[肥非飞菲斐费][鱼于余予渝娱渔宇]");
    private static final java.util.regex.Pattern STRIP_DEFAULT = java.util.regex.Pattern.compile(
            "^.{0,3}?(大|打|达|嗒)[肥非飞菲斐费][鱼于余予渝娱渔宇][，,、。！？!?\\s]*");

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
        loadFromPrefs(this.ctx);
    }

    /** 从设置里读取唤醒词：`大肥鱼|打肥鱼|大非鱼`。 */
    public static void loadFromPrefs(Context ctx) {
        String raw = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE)
                .getString("wakeWord", "大肥鱼");
        if (raw == null || raw.trim().isEmpty()) raw = "大肥鱼";
        String[] parts = raw.split("[|｜,，、;；\\s]+");
        List<String> words = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) words.add(p.trim());
        }
        if (words.isEmpty()) words.add("大肥鱼");
        CUSTOM = words.get(0);
        ALIASES = words.subList(1, words.size()).toArray(new String[0]);
    }

    public static String currentWord() {
        return CUSTOM;
    }

    private static boolean isDefaultWord() {
        return "大肥鱼".equals(CUSTOM);
    }

    private static String[] candidates() {
        String[] all = new String[ALIASES.length + 1];
        all[0] = CUSTOM;
        System.arraycopy(ALIASES, 0, all, 1, ALIASES.length);
        return all;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(Listener l) {
        if (running) return;
        loadFromPrefs(ctx);
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
        Log.i(TAG, "wake listening started (vosk) word=" + CUSTOM);
    }

    /** 去掉空白与标点：ASR 结果常带空格/逗号，直接匹配会漏。 */
    private static String normalize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。、！？；：“”‘’（）《》…～]+", "");
    }

    /** 等长串的编辑距离（这里只需要判断 ≤1）。 */
    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    /** 句首 ≤3 字内、允许错 1 个字的模糊命中（「大飞鱼」也算「大肥鱼」）。 */
    private static boolean fuzzyWithin(String t, String k, int maxOffset) {
        if (k.length() < 3) return false;
        for (int i = 0; i <= maxOffset && i + k.length() <= t.length(); i++) {
            if (lev(t.substring(i, i + k.length()), k) <= 1) return true;
        }
        return false;
    }

    /** 关键词必须出现在句子最前面（≤3 个字），避免她说长句时被自己的回声误唤醒。 */
    public static boolean matchesKeyword(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;
        for (String k : candidates()) {
            String kk = normalize(k);
            if (kk.isEmpty()) continue;
            int i = t.indexOf(kk);
            if (i >= 0 && i <= 3) return true;
            if (fuzzyWithin(t, kk, 3)) return true;
        }
        // 只有默认词才做同音正则，自定义词靠用户填的别名 + 上面的模糊匹配
        return isDefaultWord() && WAKE_DEFAULT.matcher(t).find();
    }

    /** 把开头的唤醒词剥掉，剩下的才是要说给模型的话；允许前面有「嗯…」这类语气词。 */
    public static String stripWake(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (isDefaultWord()) {
            String stripped = STRIP_DEFAULT.matcher(t).replaceFirst("");
            if (stripped.length() != t.length()) return stripped.trim();
        }
        String n = normalize(t);
        for (String k : candidates()) {
            String kk = normalize(k);
            if (kk.isEmpty()) continue;
            int len = kk.length();
            int idx = n.indexOf(kk);
            if (idx >= 0 && idx <= 3) return n.substring(idx + len).trim();
            if (len >= 3) {
                for (int i = 0; i <= 3 && i + len <= n.length(); i++) {
                    if (lev(n.substring(i, i + len), kk) <= 1) return n.substring(i + len).trim();
                }
            }
        }
        return n.trim();
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
