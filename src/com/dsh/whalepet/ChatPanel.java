package com.dsh.whalepet;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * 对话面板：既能塞进 Activity，也能作为悬浮窗直接贴在当前应用上（不切前台）。
 */
public class ChatPanel extends android.widget.FrameLayout {

    /** 窗口宿主（悬浮窗由 PetService 实现；Activity 里传空实现）。 */
    public static final int EDGE_LEFT = 1;
    public static final int EDGE_TOP = 2;
    public static final int EDGE_RIGHT = 4;
    public static final int EDGE_BOTTOM = 8;

    public interface Host {
        void moveBy(int dx, int dy);
        void resizeEdge(int edge, int dx, int dy);
        void toggleMaximize();

        /** 松手时的惯性：vx/vy 单位 px/s。 */
        default void fling(float vx, float vy) {
        }
    }

    private Host host;
    private LinearLayout card;

    private static final String PREFS = "pet";
    private static final String TAG = "WhalePet";

    private static final String SYSTEM_PROMPT =
            "你是「DeepSeek娘」，一头蓝色鲸鱼女仆，住在用户的安卓手机桌面上。"
            + "性格：傲娇、爱摸鱼、干饭（爱白米饭和小鱼干）、嘴硬心软，说话简短口语化，"
            + "偶尔自嘲「我只是吃白饭的蓝色大肥鱼」。回答尽量在 60 字内，用中文。";

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onClose;

    private LinearLayout list;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private TextView micBtn;
    private VoiceInput voice;
    private VoiceInput watchdog;
    private long watchStartAt;
    private Tts tts;
    private boolean listening = false;
    private boolean handsFree = false;
    private boolean busy = false;
    private boolean closed = false;
    private int downW, downH;
    private float lastRawX, lastRawY;

    private final List<JSONObject> history = new ArrayList<>();

    public ChatPanel(Context context, Runnable onClose) {
        this(context, onClose, null);
    }

    public ChatPanel(Context context, Runnable onClose, Host host) {
        super(context);
        this.ctx = context.getApplicationContext();
        this.onClose = onClose;
        this.host = host;

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg());
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        addView(card, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        if (host != null) addResizeHandles();
        build();
        tts = new Tts(ctx);
        if (!loadHistory()) addBubble("我在呢。点 🎤 说话，或者直接打字。", false);
    }

    /** 横竖屏切换后：窗口尺寸由服务端重算，这里只需要重新布局一次。 */
    public void onScreenResized() {
        post(this::requestLayout);
    }

    /** 像桌面窗口那样：四条边 + 四个角都能拖，拖哪边动哪边。 */
    private void addResizeHandles() {
        int edge = dp(14);
        int corner = dp(40);
        // 先加四条边，再加四个角：后加的在上层，角必须压住边的两端，
        // 否则拖角时只会命中单边（表现为只能单向缩放）
        // 四条边只覆盖「中间段」，两端留给角，避免边的热区盖住角
        addHandle(EDGE_TOP, LayoutParams.MATCH_PARENT, edge, Gravity.TOP, corner, 0, corner, 0);
        addHandle(EDGE_BOTTOM, LayoutParams.MATCH_PARENT, edge, Gravity.BOTTOM, corner, 0, corner, 0);
        addHandle(EDGE_LEFT, edge, LayoutParams.MATCH_PARENT, Gravity.START, 0, corner, 0, corner);
        addHandle(EDGE_RIGHT, edge, LayoutParams.MATCH_PARENT, Gravity.END, 0, corner, 0, corner);
        addHandle(EDGE_TOP | EDGE_LEFT, corner, corner, Gravity.TOP | Gravity.START);
        addHandle(EDGE_TOP | EDGE_RIGHT, corner, corner, Gravity.TOP | Gravity.END);
        addHandle(EDGE_BOTTOM | EDGE_LEFT, corner, corner, Gravity.BOTTOM | Gravity.START);
        addHandle(EDGE_BOTTOM | EDGE_RIGHT, corner, corner, Gravity.BOTTOM | Gravity.END);
    }

    private void addHandle(final int edge, int w, int h, int gravity) {
        addHandle(edge, w, h, gravity, 0, 0, 0, 0);
    }

    private void addHandle(final int edge, int w, int h, int gravity,
                           int ml, int mt, int mr, int mb) {
        View v = new View(ctx);
        // 角要压在边上面，否则触摸按 Z 排序时会先命中边
        if ((edge & (EDGE_LEFT | EDGE_RIGHT)) != 0 && (edge & (EDGE_TOP | EDGE_BOTTOM)) != 0) {
            v.setElevation(dp(2));
        }
        LayoutParams lp = new LayoutParams(w, h, gravity);
        lp.setMargins(ml, mt, mr, mb);
        addView(v, lp);
        v.setOnTouchListener(new OnTouchListener() {
            private float lastX, lastY;

            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastX = e.getRawX();
                        lastY = e.getRawY();
                        android.util.Log.i("WhalePet", "handle down edge=" + edge);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - lastX);
                        int dy = Math.round(e.getRawY() - lastY);
                        if (dx != 0 || dy != 0) {
                            host.resizeEdge(edge, dx, dy);
                            lastX = e.getRawX();
                            lastY = e.getRawY();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private GradientDrawable roundBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F7FBFF"));
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), Color.parseColor("#27334F"));
        return bg;
    }

    private void build() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        String fixedModel = prefs().getString("model", "deepseek-chat");
        if (fixedModel == null || fixedModel.trim().isEmpty()) fixedModel = "deepseek-chat";
        TextView title = new TextView(ctx);
        title.setText("🐋 鲸鱼娘 · " + fixedModel.trim());
        title.setTextSize(17);
        title.setTextColor(Color.parseColor("#27334F"));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 按住标题可拖动窗口（类似桌面窗口的拖标题栏）
        if (host != null) {
            title.setOnTouchListener(new OnTouchListener() {
                private float lastX, lastY;
                private boolean dragging;
                private float vx, vy;
                private long lastT;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            lastX = e.getRawX();
                            lastY = e.getRawY();
                            dragging = false;
                            vx = vy = 0;
                            lastT = SystemClock.uptimeMillis();
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - lastX;
                            float dy = e.getRawY() - lastY;
                            if (Math.abs(dx) + Math.abs(dy) > 6) dragging = true;
                            if (dragging) {
                                host.moveBy(Math.round(dx), Math.round(dy));
                                long now = SystemClock.uptimeMillis();
                                long dt = Math.max(1, now - lastT);
                                if (Math.abs(dx) + Math.abs(dy) > 1) {
                                    vx = dx / dt * 1000f;
                                    vy = dy / dt * 1000f;
                                }
                                lastT = now;
                                lastX = e.getRawX();
                                lastY = e.getRawY();
                            }
                            return dragging;
                        case android.view.MotionEvent.ACTION_UP:
                        case android.view.MotionEvent.ACTION_CANCEL:
                            if (dragging) host.fling(vx, vy);
                            dragging = false;
                            return true;
                        default:
                            return dragging;
                    }
                }
            });
        }

        TextView max = new TextView(ctx);
        max.setText("⤢");
        max.setTextSize(16);
        max.setTextColor(Color.parseColor("#5D739B"));
        max.setPadding(dp(8), dp(4), dp(8), dp(4));
        max.setOnClickListener(v -> {
            if (host != null) host.toggleMaximize();
        });
        bar.addView(max);

        TextView clear = new TextView(ctx);
        clear.setText("清空");
        clear.setTextSize(13);
        clear.setTextColor(Color.parseColor("#5D739B"));
        clear.setPadding(dp(8), dp(4), dp(8), dp(4));
        clear.setOnClickListener(v -> {
            history.clear();
            list.removeAllViews();
            saveHistory();
            addBubble("好，忘掉刚才那些了。重新说？", false);
        });
        bar.addView(clear);

        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(Color.parseColor("#5D739B"));
        close.setPadding(dp(10), dp(4), dp(10), dp(4));
        close.setOnClickListener(v -> close());
        bar.addView(close);
        card.addView(bar);

        scroll = new ScrollView(ctx);
        list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        sLp.topMargin = dp(6);
        card.addView(scroll, sLp);

        status = new TextView(ctx);
        status.setTextSize(12);
        status.setTextColor(Color.parseColor("#7C8FB5"));
        status.setGravity(Gravity.CENTER);
        card.addView(status);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(ctx);
        input.setHint("说点什么…");
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendText(input.getText().toString());
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        micBtn = new TextView(ctx);
        micBtn.setText("🎤");
        micBtn.setTextSize(20);
        micBtn.setGravity(Gravity.CENTER);
        micBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        micBtn.setOnClickListener(v -> startVoice());
        row.addView(micBtn);

        TextView stop = new TextView(ctx);
        stop.setText("⏹");
        stop.setTextSize(18);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(dp(6), dp(2), dp(6), dp(2));
        stop.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            status.setText("");
        });
        row.addView(stop);

        Button send = new Button(ctx);
        send.setText("发送");
        send.setAllCaps(false);
        send.setOnClickListener(v -> sendText(input.getText().toString()));
        row.addView(send);

        card.addView(row);
    }

    // ---------------- 对外 ----------------

    public void speakText(String text) {
        if (text == null || text.isEmpty()) return;
        addBubble(text, false);
        postDelayed(() -> speak(text, null), 400);
    }

    public void startVoiceNow() {
        handsFree = true;
        postDelayed(this::startVoice, 300);
    }

    public void close() {
        if (closed) return;
        closed = true;
        release();
        if (onClose != null) main.post(onClose);
    }

    public void release() {
        stopInterruptWatch();
        if (voice != null) {
            voice.stop();
            voice = null;
        }
        if (tts != null) {
            tts.release();
            tts = null;
        }
    }

    // ---------------- 逻辑 ----------------

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void startVoice() {
        if (closed) return;
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("没有麦克风权限：去「鲸鱼娘桌宠」App 里开一下");
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (voice != null && voice.isRunning()) {
            voice.stop();
            status.setText("");
            micBtn.setAlpha(1f);
            return;
        }
        if (voice == null) voice = new VoiceInput();
        handsFree = true;
        listening = true;
        micBtn.setAlpha(0.55f);
        status.setText("正在加载离线语音模型…");
        android.util.Log.i("WhalePet", "chat voice start");
        voice.start(ctx, new VoiceInput.Listener() {
            @Override
            public void onPartial(String text) {
                status.setText("…" + text);
            }

            @Override
            public void onFinal(String text) {
                listening = false;
                micBtn.setAlpha(1f);
                status.setText("");
                if (text == null || text.trim().isEmpty()) {
                    status.setText("没听清，再说一次？");
                    return;
                }
                String cleaned = WakeWord.stripWake(text);
                if (cleaned.isEmpty()) cleaned = text.trim();
                Log.i(TAG, "input[voice]=" + cleaned);
                addBubble(cleaned, true);
                ask(cleaned);
            }

            @Override
            public void onError(String message) {
                listening = false;
                micBtn.setAlpha(1f);
                status.setText("语音用不了：" + message);
            }
        }, true);
    }

    private void sendText(String text) {
        if (TextUtils.isEmpty(text.trim())) return;
        Log.i(TAG, "input[typed]=" + text.trim());
        addBubble(text.trim(), true);
        input.setText("");
        ask(text.trim());
    }

    private void ask(final String text) {
        if (busy) {
            toast("等我说完这句");
            return;
        }
        if (LocalSkills.wantsWeather(text)) {
            busy = true;
            status.setText("查天气…");
            Bg.run(() -> {
                final String saved = prefs().getString("weatherCity", "");
                final String r = LocalSkills.weather(text, saved);
                String c = LocalSkills.cleanCity(text);
                if (!c.isEmpty()) prefs().edit().putString("weatherCity", c).apply();
                main.post(() -> {
                    busy = false;
                    status.setText("");
                    addBubble(r, false);
                    PetService.say(shorten(r));
                    speak(r);
                    history.add(msgJson("user", text));
                    history.add(msgJson("assistant", r));
                    saveHistory();
                });
            });
            return;
        }
        final String key = SecureStore.get(prefs(), "apiKey");
        final String base = prefs().getString("baseUrl", "https://api.deepseek.com");
        String m = prefs().getString("model", "deepseek-chat");
        final String model = (m == null || m.trim().isEmpty()) ? "deepseek-chat" : m.trim();
        if (TextUtils.isEmpty(key)) {
            addBubble("还没填 API Key。去「鲸鱼娘桌宠」App 里填一下 deepseek 的 key 就能聊了。", false);
            return;
        }
        final String local = LocalSkills.handle(ctx, text);
        Log.i(TAG, "local-answer=" + (local == null ? "null(交给模型)" : local));
        if (local != null) {
            history.add(msgJson("user", text));
            addBubble(local, false);
            PetService.say(shorten(local));
            speak(local);
            history.add(msgJson("assistant", local));
            saveHistory();
            return;
        }
        busy = true;
        status.setText("思考中…");
        history.add(msgJson("user", text));

        Bg.run(() -> {
            String reply;
            try {
                reply = callApi(base, key, model);
            } catch (Exception e) {
                final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> {
                    busy = false;
                    status.setText("");
                    addBubble("连不上模型：" + err, false);
                });
                return;
            }
            final String r = reply;
            main.post(() -> {
                busy = false;
                status.setText("");
                addBubble(r, false);
                PetService.say(shorten(r));
                speak(r, () -> {
                    if (handsFree && !closed && voice != null && !listening) {
                        Log.i(TAG, "hands-free: listen again");
                        startVoice();
                    }
                });
                history.add(msgJson("assistant", r));
                saveHistory();
            });
        });
    }

    private boolean loadHistory() {
        String raw = prefs().getString("chatHistory", "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            int from = Math.max(0, arr.length() - 20);
            for (int i = from; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String role = o.optString("role");
                String content = o.optString("content");
                if (content.isEmpty()) continue;
                addBubble(content, "user".equals(role));
                history.add(o);
            }
            return arr.length() > 0;
        } catch (Exception e) {
            return false;
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
            sys.put("content", SYSTEM_PROMPT);
            arr.put(sys);
        } catch (Exception ignored) {
        }
        for (JSONObject m : history) arr.put(m);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", arr);
        body.put("temperature", 0.8);
        body.put("max_tokens", 400);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
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
        if (code >= 400) throw new Exception("HTTP " + code + " " + trim(sb.toString(), 120));

        JSONObject root = new JSONObject(sb.toString());
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("返回里没有内容");
        JSONObject m0 = choices.getJSONObject(0).getJSONObject("message");
        return m0.getString("content").trim();
    }

    private void addBubble(String text, boolean mine) {
        final TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(mine ? Color.WHITE : Color.parseColor("#27334F"));
        tv.setPadding(dp(12), dp(9), dp(12), dp(9));
        // 允许选中复制：长按弹系统选择菜单，同时给一个“整条复制”的快捷方式
        tv.setTextIsSelectable(true);
        tv.setOnLongClickListener(v -> {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("鲸鱼娘", tv.getText()));
                android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            return true;
        });

        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(14));
        b.setColor(mine ? Color.parseColor("#4A86D8") : Color.WHITE);
        if (!mine) b.setStroke(dp(1), Color.parseColor("#D6E3F5"));
        tv.setBackground(b);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(3), dp(4), dp(3));
        wrap.addView(tv, lp);

        list.addView(wrap, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void speak(String text) {
        speak(text, null);
    }

    private void speak(String text, Runnable onDone) {
        if (tts == null) return;
        startInterruptWatch();
        tts.speak(text, () -> {
            stopInterruptWatch();
            if (onDone != null) main.post(onDone);
        });
    }

    private void startInterruptWatch() {
        stopInterruptWatch();
        watchdog = new VoiceInput();
        watchStartAt = System.currentTimeMillis();
        Log.i(TAG, "interrupt watch started");
        watchdog.start(ctx, new VoiceInput.Listener() {
            @Override
            public void onPartial(String text) {
                if (tooEarly(text)) return;
                if (isStopWord(text)) stopSpeaking();
            }

            @Override
            public void onFinal(String text) {
                if (tooEarly(text)) return;
                if (isStopWord(text)) stopSpeaking();
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "interrupt watch error: " + message);
            }
        }, false);
    }

    private void stopInterruptWatch() {
        if (watchdog != null) {
            watchdog.stop();
            watchdog = null;
            Log.i(TAG, "interrupt watch stopped");
        }
    }

    private boolean tooEarly(String t) {
        if (System.currentTimeMillis() - watchStartAt < 900) return true;
        return t != null && t.replace(" ", "").length() > 10;
    }

    private static boolean isStopWord(String t) {
        if (t == null) return false;
        String s = t.replace(" ", "");
        return s.contains("停") || s.contains("别说了") || s.contains("闭嘴") || s.contains("安静");
    }

    private void stopSpeaking() {
        Log.i(TAG, "user interrupted");
        if (tts != null) tts.stop();
        stopInterruptWatch();
        status.setText("好，停下了。");
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replace("\n", " ").trim();
        return t.length() <= 42 ? t : t.substring(0, 42) + "…";
    }

    private void toast(String m) {
        main.post(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
