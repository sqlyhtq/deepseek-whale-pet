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

    /** 召回：最近确认的事实 + 最近一份摘要。 */
    public static String recall(Context c, String query) {
        if (!enabled(c)) return "";
        try {
            StringBuilder sb = new StringBuilder();
            JSONArray facts = getJson(c, "/facts?scope=" + enc(scope(c)) + "&k=8");
            if (facts != null && facts.length() > 0) {
                sb.append("\n\n你从以往对话里记住的事（自然使用，别逐条复述）：\n");
                for (int i = 0; i < facts.length(); i++) {
                    JSONObject o = facts.optJSONObject(i);
                    String t = o == null ? "" : o.optString("text", "");
                    if (!t.isEmpty()) sb.append("- ").append(t).append('\n');
                }
            }
            String summary = getText(c, "/summary?scope=" + enc(scope(c)) + "&n=1");
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("\n之前聊过（摘要）：\n").append(summary.trim()).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "mem recall failed: " + t);
            return "";
        }
    }

    /** 写回一条对话。 */
    public static void add(Context c, String role, String text) {
        if (!enabled(c) || text == null || text.isEmpty()) return;
        if ("assistant".equals(role)) role = "user";  // 服务端只有 user/assistant 之外的约束由它自己处理
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

    private static JSONArray getJson(Context c, String path) {
        String s = getText(c, path);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            if (o.has("facts")) return o.getJSONArray("facts");
            for (String k : new String[]{"items", "data", "entries"}) {
                if (o.has(k)) return o.getJSONArray(k);
            }
            return null;
        } catch (Throwable t) {
            try {
                return new JSONArray(s);
            } catch (Throwable ignored) {
                return null;
            }
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
            if (conn.getResponseCode() >= 400) Log.w(TAG, "mem http " + conn.getResponseCode());
        } catch (Throwable t) {
            Log.w(TAG, "mem post failed: " + t);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
