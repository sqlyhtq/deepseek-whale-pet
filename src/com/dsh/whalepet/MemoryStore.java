package com.dsh.whalepet;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 长期记忆：一小撮“关于用户的事实”，本地 JSON 存储。 */
public final class MemoryStore {

    private static final String TAG = "WhalePet";
    private static final int MAX_FACTS = 40;
    private static final File FILE = null;   // 占位，实际用 context 的 filesDir

    public static final class Fact {
        public final String text;
        public final long ts;

        Fact(String text, long ts) {
            this.text = text;
            this.ts = ts;
        }
    }

    private MemoryStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "memory.json");
    }

    public static synchronized List<Fact> load(Context c) {
        List<Fact> out = new ArrayList<>();
        try {
            File f = file(c);
            if (!f.exists()) return out;
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(json).optJSONArray("facts");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Fact(o.optString("t"), o.optLong("ts")));
            }
        } catch (Throwable t) {
            Log.w(TAG, "memory load failed: " + t);
        }
        return out;
    }

    /** 用模型返回的新记忆整体覆盖（已经在调用方合并过）。 */
    public static synchronized void save(Context c, List<Fact> facts) {
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, facts.size() - MAX_FACTS);
            for (int i = from; i < facts.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("t", facts.get(i).text);
                o.put("ts", facts.get(i).ts);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("facts", arr);
            Files.write(file(c).toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(TAG, "memory save failed: " + t);
        }
    }

    public static synchronized void addFact(Context c, String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 80) return;
        List<Fact> facts = load(c);
        for (Fact f : facts) {
            if (f.text.equals(t)) return;
        }
        facts.add(new Fact(t, System.currentTimeMillis()));
        save(c, facts);
    }

    public static synchronized void clear(Context c) {
        try {
            file(c).delete();
        } catch (Throwable ignored) {
        }
    }

    /** 拼成给模型看的一段。 */
    public static String promptBlock(Context c) {
        List<Fact> facts = load(c);
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n你记得关于用户的事（自然使用，不要逐条复述）：\n");
        int from = Math.max(0, facts.size() - 20);
        for (int i = from; i < facts.size(); i++) {
            sb.append("- ").append(facts.get(i).text).append('\n');
        }
        return sb.toString();
    }
}
