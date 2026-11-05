package com.dsh.whalepet;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 独立记忆服务（memserver）客户端：
 *  - recall：把「用户确认过的事实 + 前情摘要」拼进提示词
 *  - add：每轮对话写回一条
 * 服务不可用时静默跳过，不影响聊天。
 */
public final class MemClient {

    private static final String TAG = "WhalePet";

    private MemClient() {
    }

    private static String url(Context c) {
        String u = c.getSharedPreferences("pet", Context.MODE_PRIVATE)
                .getString("memServerUrl", "");
        return u == null ? "" : u.trim().replaceAll("/+$", "");
    }

    private static String token(Context c) {
        String t = SecureStore.get(c.getSharedPreferences("pet", Context.MODE_PRIVATE), "memToken");
        return t == null ? "" : t.trim();
    }

    private static String scope(Context c) {
        String s = c.getSharedPreferences("pet", Context.MODE_PRIVATE).getString("memScope", "pet");
        return (s == null || s.trim().isEmpty()) ? "pet" : s.trim();
    }

    public static boolean enabled(Context c) {
        return !url(c).isEmpty();
    }

    /** 召回：相关的历史片段 + 已确认事实 + 最近摘要。 */
    public static String recall(Context c, String query) {
        if (!enabled(c)) return "";
        String scope = enc(scope(c));
        StringBuilder sb = new StringBuilder();

        // 1) 语义相关片段
        try {
            String rec = getText(c, "/recall?scope=" + scope + "&query=" + enc(query == null ? "" : query) + "&k=4");
            JSONArray lines = jsonArray(rec, "lines");
            if (lines != null && lines.length() > 0) {
                sb.append("\n\n你和用户以前聊过的相关片段：\n");
                for (int i = 0; i < lines.length(); i++) {
                    String t = lines.optString(i, "").trim();
                    if (!t.isEmpty()) sb.append("- ").append(t).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }

        // 2) 已确认的事实
        try {
            JSONArray facts = jsonArray(getText(c, "/facts?scope=" + scope + "&k=8"), "facts");
            if (facts != null && facts.length() > 0) {
                sb.append("\n\n你记得关于用户的事（自然使用，不要逐条复述）：\n");
                for (int i = 0; i < facts.length(); i++) {
                    JSONObject o = facts.optJSONObject(i);
                    String t = o == null ? facts.optString(i, "") : o.optString("text", "");
                    if (!t.isEmpty()) sb.append("- ").append(t).append('\n');
                }
            }
        } catch (Throwable ignored) {
        }

        // 3) 最近一份摘要
        try {
            String raw = getText(c, "/summary?scope=" + scope + "&n=1");
            String summary = jsonString(raw, "summary");
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("\n\n之前聊过（摘要）：\n").append(summary.trim()).append('\n');
            }
        } catch (Throwable ignored) {
        }

        return sb.toString();
    }

    private static JSONArray jsonArray(String raw, String key) {
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (o.has(key)) return o.optJSONArray(key);
            if (o.has("items")) return o.optJSONArray("items");
            return null;
        } catch (Throwable t) {
            try {
                return new JSONArray(raw);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static String jsonString(String raw, String key) {
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return o.optString(key, "");
        } catch (Throwable t) {
            return raw.startsWith("{") ? null : raw;
        }
    }

    /** 写回一条对话。 */
    /** 写入一条已确认的事实（会出现在 /facts 里，固定注入）。 */
    public static void addFact(Context c, String text) {
        add(c, "fact", text);
    }

    public static void add(Context c, String role, String text) {
        if (!enabled(c) || text == null || text.isEmpty()) return;
        // 服务端用中文角色名区分事实/摘要；普通对话保持 user/assistant
        if ("fact".equals(role)) role = "事实";
        else if ("summary".equals(role)) role = "摘要";
        try {
            JSONObject body = new JSONObject();
            body.put("scope", scope(c));
            body.put("role", role);
            body.put("text", text);
            post(c, "/add", body);
        } catch (Throwable t) {
            Log.w(TAG, "mem add failed: " + t);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String getText(Context c, String path) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url(c) + path).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            String tk = token(c);
            if (!tk.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + tk);
            if (conn.getResponseCode() >= 400) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void post(Context c, String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url(c) + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            String tk = token(c);
            if (!tk.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + tk);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            StringBuilder rb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) rb.append(line);
                }
            }
            Log.i(TAG, "mem post " + path + " -> " + code + " " + rb);
        } catch (Throwable t) {
            Log.w(TAG, "mem post failed: " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
