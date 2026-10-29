package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话内核：天气 / 本机指令 / 模型调用 + 历史持久化。
 * 不碰 UI，聊天窗和「无窗语音」都共用它，避免两套逻辑。
 */
public class ChatEngine {

    public interface Callback {
        void onUser(String text);

        void onReply(String text);

        void onError(String message);
    }

    private static final String PREFS = "pet";
    private static final String TAG = "WhalePet";

    private static final String SYSTEM_PROMPT =
            "你是「DeepSeek娘」，一头蓝色鲸鱼女仆，住在用户的安卓手机桌面上。"
            + "性格：傲娇、爱摸鱼、干饭（爱白米饭和小鱼干）、嘴硬心软，说话简短口语化，"
            + "偶尔自嘲「我只是吃白饭的蓝色大肥鱼」。回答尽量在 60 字内，用中文。";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> history = new ArrayList<>();

    public ChatEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        loadHistory();
    }

    public void ask(final String text, final Callback cb) {
        main.post(() -> cb.onUser(text));

        if (LocalSkills.wantsWeather(text)) {
            new Thread(() -> {
                final String saved = prefs().getString("weatherCity", "");
                final String r = LocalSkills.weather(text, saved);
                String c = LocalSkills.cleanCity(text);
                if (!c.isEmpty()) prefs().edit().putString("weatherCity", c).apply();
                finishTurn(text, r, cb);
            }).start();
            return;
        }

        final String local = LocalSkills.handle(ctx, text);
        if (local != null) {
            finishTurn(text, local, cb);
            return;
        }

        final String key = prefs().getString("apiKey", "");
        final String base = prefs().getString("baseUrl", "https://api.deepseek.com");
        String m = prefs().getString("model", "deepseek-chat");
        final String model = (m == null || m.trim().isEmpty()) ? "deepseek-chat" : m.trim();
        if (TextUtils.isEmpty(key)) {
            finishTurn(text, "还没填 API Key。去「鲸鱼娘桌宠」App 里填一下 deepseek 的 key 就能聊了。", cb);
            return;
        }

        history.add(msgJson("user", text));
        new Thread(() -> {
            try {
                String reply = callApi(base, key, model);
                finishTurn(null, reply, cb);
            } catch (Exception e) {
                final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> cb.onError(err));
                history.remove(history.size() - 1);
            }
        }).start();
    }

    private void finishTurn(final String userText, final String reply, final Callback cb) {
        try {
            if (userText != null) {
                history.add(msgJson("user", userText));
                main.post(() -> cb.onUser(userText));
            }
            history.add(msgJson("assistant", reply));
            saveHistory();
        } catch (Exception ignored) {
        }
        main.post(() -> cb.onReply(reply));
    }

    public boolean isEmpty() {
        return history.isEmpty();
    }

    public void clear() {
        history.clear();
        saveHistory();
    }

    private String callApi(String base, String key, String model) throws Exception {
        String urlStr = base.replaceAll("/+$", "") + "/chat/completions";
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);

        JSONArray arr = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            String extra = PetService.currentIcon();
            sys.put("content", SYSTEM_PROMPT
                    + (extra == null || extra.isEmpty() ? ""
                       : "（她此刻正站在桌面图标「" + extra + "」旁边，可以自然地顺带提一句。）"));
            arr.put(sys);
        } catch (Exception ignored) {
        }
        for (JSONObject m : history) arr.put(m);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", arr);
        body.put("temperature", 0.8);
        body.put("max_tokens", 400);

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        JSONObject root = new JSONObject(sb.toString());
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("返回里没有内容");
        return choices.getJSONObject(0).getJSONObject("message").getString("content").trim();
    }

    private JSONObject msgJson(String role, String content) {
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("content", content);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void loadHistory() {
        try {
            JSONArray arr = new JSONArray(prefs().getString("chatHistory", "[]"));
            int from = Math.max(0, arr.length() - 30);
            for (int i = from; i < arr.length(); i++) history.add(arr.getJSONObject(i));
        } catch (Exception ignored) {
        }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            int from = Math.max(0, history.size() - 30);
            for (int i = from; i < history.size(); i++) arr.put(history.get(i));
            prefs().edit().putString("chatHistory", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
