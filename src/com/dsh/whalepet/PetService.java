package com.dsh.whalepet;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.net.Uri;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/**
 * 桌面鲸鱼娘：一个只贴在桌面（Launcher）上的悬浮窗。
 * 进入其它 App / 锁屏 / 息屏时收成 1x1 透明小窗（不用 GONE，
 * 否则进程没有可见窗口会被 ColorOS 冻结，轮询就再也醒不过来）。
 */
public class PetService extends Service {

    public static boolean isRunning = false;
    static PetService INSTANCE;

    /** 打开悬浮聊天窗（不切前台）。 */
    public static void openChatWindow(final boolean voice) {
        PetService svc = INSTANCE;
        if (svc == null) return;
        svc.ui.post(() -> svc.showChat(voice));
    }

    /** 气泡停留时长（5–15 秒，设置里可调）。 */
    private int bubbleMs() {
        int sec = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("bubbleSeconds", 7);
        return Math.max(5, Math.min(15, sec)) * 1000;
    }

    /** 带停留时长的气泡（毫秒）。 */
    private void sayFor(final String text, final int ms) {
        if (text == null) return;
        final String js = "window.petSay && window.petSay("
                + org.json.JSONObject.quote(text) + "," + ms + ")";
        ui.post(() -> {
            try {
                if (web != null) web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 让聊天页把回答同步显示在桌面气泡里。 */
    public static void say(final String text) {
        PetService svc = INSTANCE;
        if (svc == null || svc.web == null || text == null) return;
        final String js = "window.petSay && window.petSay(" + org.json.JSONObject.quote(text) + ")";
        svc.ui.post(() -> {
            try {
                if (svc.web != null) svc.web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 场景动作：listen / think / speak / happy / sad / wake / idle */
    public static void pose(String name) {
        PetService svc = INSTANCE;
        if (svc == null || svc.web == null) return;
        final String js = "window.petReact && window.petReact('" + name.replace("'", "") + "')";
        svc.ui.post(() -> {
            try {
                if (svc.web != null) svc.web.evaluateJavascript(js, null);
            } catch (Throwable ignored) {
            }
        });
    }
    private static final String PREFS = "pet";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL = "pet_overlay";
    private static final int NOTIF_ID = 1001;
    private static final String TAG = "WhalePet";

    private WindowManager wm;
    private DragLayout root;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WakeWord wake;
    private ChatPanel chat;
    private ChatEngine engine;
    private static String CURRENT_ICON = "";
    private long lastIconAt = 0;
    private long lastA11yTry = 0;
    private long lastPartialAt = 0;
    private String announcedIcon = "";

    public static String currentIcon() {
        return CURRENT_ICON == null ? "" : CURRENT_ICON;
    }

    /** 如果 files 目录里有 big.zip 且大模型还没装，就在后台装一次。 */
    private void autoInstallBigModel() {
        if (BigModel.isReady(this)) return;
        final java.io.File zip = new java.io.File(getFilesDir(), "big.zip");
        if (!zip.exists()) return;
        Log.i(TAG, "auto install big model from " + zip);
        BigModel.installFromZip(this, zip, new BigModel.Progress() {
            @Override
            public void onProgress(int percent, long done, long total) {
            }

            @Override
            public void onDone(boolean ok, String message) {
                Log.i(TAG, "auto install big model done=" + ok + " " + message);
            }
        });
    }

    /** 用 root/Shizuku 把无障碍服务打开（不会顶掉用户已有的其它服务）。 */
    private void ensureA11y() {
        final String me = getPackageName() + "/.PetA11yService";
        new Thread(() -> {
            String cur = ShellExec.sh("settings get secure enabled_accessibility_services");
            String list = cur == null ? "" : cur.trim();
            if (list.contains(getPackageName())) return;
            String cmd = "settings put secure enabled_accessibility_services "
                    + (list.isEmpty() || "null".equals(list) ? me : list + ":" + me)
                    + "; settings put secure accessibility_enabled 1";
            String out = ShellExec.sh(cmd);
            Log.i(TAG, "ensureA11y -> " + (out == null ? "no shell" : "ok"));
        }, "a11y-grant").start();
    }

    /** 每隔几秒看一下她脚下是哪个图标。 */
    private void tickIcon() {
        if (lp == null || !PetA11yService.isReady()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastIconAt < 6000) return;
        lastIconAt = now;
        final int cx = lp.x + lp.width / 2;
        final int cy = lp.y + (int) (lp.height * 0.9);
        new Thread(() -> {
            String label = PetA11yService.labelAt(cx, cy);
            if (label == null) label = "";
            final String found = label;
            ui.post(() -> {
                CURRENT_ICON = found;
                if (!found.isEmpty() && !found.equals(announcedIcon) && !found.equals(getPackageName())) {
                    announcedIcon = found;
                    if (System.currentTimeMillis() - lastSpoke > 20000) {
                        lastSpoke = System.currentTimeMillis();
                        sayFor(iconLine(found), 4200);
                    }
                }
            });
        }, "icon-probe").start();
    }

    private long lastSpoke;

    private String iconLine(String app) {
        String[] lines = {
                "「" + app + "」…又要摸鱼了？",
                "站在「" + app + "」旁边，要不要我帮你点开？",
                "这个是「" + app + "」呀，我记住了。",
                "「" + app + "」看起来挺忙的。"
        };
        return lines[(int) (Math.random() * lines.length)];
    }
    private VoiceInput voiceIn;
    private Tts voiceTts;
    private WindowManager.LayoutParams chatLp;
    private boolean wakeWanted = false;

    private String homePkg = "";
    private boolean shown = true;
    private int tickCount = 0;
    private long lastShellTry = 0;
    private volatile String lastShellTop = "";
    private volatile boolean escalating = false;
    private int fullW, fullH, fullFlags, lastX, lastY;

    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            updateVisibility();
            ui.postDelayed(this, 1200);
        }
    };

    public static boolean wasEnabled(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        autoInstallBigModel();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // 声明 microphone 类型，否则后台录音会被系统静音（收到全 0 数据）
                startForeground(NOTIF_ID, buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIF_ID, buildNotification());
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground typed failed: " + t);
            try {
                startForeground(NOTIF_ID, buildNotification());
            } catch (Throwable ignored) {
            }
        }

        density = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        root = new DragLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // ES module / fetch 要从 file:// 读 live2d 资源，必须放开这两个（已废弃但有效）
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Bridge(), "PetHost");
        // file:// 下 ES module / fetch 会被 CORS 拦死，改成内置虚拟域名 + 拦截请求直接读 assets
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Log.i(TAG, "console: " + msg.message() + " @" + msg.lineNumber());
                return true;
            }
        });
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Log.i(TAG, "nav: " + request.getUrl());
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null || !"pet.local".equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.length() < 2) return null;
                path = path.substring(1);
                String mime = "application/octet-stream";
                if (path.endsWith(".js")) mime = "text/javascript";
                else if (path.endsWith(".mjs")) mime = "text/javascript";
                else if (path.endsWith(".json")) mime = "application/json";
                else if (path.endsWith(".html")) mime = "text/html";
                else if (path.endsWith(".png")) mime = "image/png";
                else if (path.endsWith(".css")) mime = "text/css";
                if (path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".json")) {
                    Log.i(TAG, "asset hit: " + path);
                }
                try {
                    InputStream is = getAssets().open(path);
                    return new WebResourceResponse(mime, "utf-8", is);
                } catch (Throwable t) {
                    Log.w(TAG, "asset miss: " + path);
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "page finished: " + url);
                pushFps(view);
                // 页面脚本可能还没就绪，补两次
                ui.postDelayed(() -> pushFps(view), 800);
                ui.postDelayed(() -> pushFps(view), 2500);
            }

            private void pushFps(WebView view) {
                int f = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("petFps", 120);
                try {
                    view.evaluateJavascript("window.petSetFps && window.petSetFps(" + f + ")", null);
                    int sec = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("bubbleSeconds", 7);
                    view.evaluateJavascript("window.petSetBubble && window.petSetBubble(" + sec + ")", null);
                } catch (Throwable ignored) {
                }
            }
        });
        web.loadUrl("file:///android_asset/pet.html");
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dp(240);
        int h = dp(280);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        lp.x = sp.getInt("x", dm.widthPixels - w - dp(6));
        lp.y = sp.getInt("y", dm.heightPixels - h - dp(150));
        lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - w));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - h));

        fullW = w;
        fullH = h;
        fullFlags = lp.flags;
        lastX = lp.x;
        lastY = lp.y;

        try {
            wm.addView(root, lp);
            isRunning = true;
            INSTANCE = this;
            homePkg = resolveHomePackage();
            setEnabled(this, true);
            ui.post(watch);
        } catch (Throwable t) {
            Log.w(TAG, "addView failed", t);
            Toast.makeText(this, "悬浮窗没加上：" + t.getMessage(), Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    /** 拖动交给原生层做，手感稳；点击仍然落到 WebView。 */
    private class DragLayout extends FrameLayout {
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging = false;

        DragLayout(Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    // 双击判定放在这里：intercept 阶段一定能收到 DOWN，
                    // 不管内层 WebView 有没有吃掉事件
                    long nowDown = SystemClock.uptimeMillis();
                    if (nowDown - lastInterceptDown < 340) {
                        lastInterceptDown = 0;
                        if (web != null) {
                            web.evaluateJavascript("window.petDoubleTap && window.petDoubleTap()", null);
                        }
                    } else {
                        lastInterceptDown = nowDown;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging
                            && (Math.abs(ev.getRawX() - downX) > slop
                            || Math.abs(ev.getRawY() - downY) > slop)) {
                        dragging = true;
                        // 抓起来：让她切到「被拎起/晕乎」的姿态
                        if (web != null) {
                            web.evaluateJavascript("window.petGrab && window.petGrab(true)", null);
                        }
                        return true;
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        private long lastTapAt;
        private long lastInterceptDown;
        private float lastX2, lastY2;
        private long lastMoveAt;
        private float vx, vy;

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX2 = ev.getRawX();
                    lastY2 = ev.getRawY();
                    lastMoveAt = SystemClock.uptimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    long now = SystemClock.uptimeMillis();
                    float dxs = ev.getRawX() - lastX2;
                    float dys = ev.getRawY() - lastY2;
                    long dts = Math.max(1, now - lastMoveAt);
                    vx = dxs / dts * 1000f;
                    vy = dys / dts * 1000f;
                    lastX2 = ev.getRawX();
                    lastY2 = ev.getRawY();
                    lastMoveAt = now;
                    moveTo(startX + (int) (ev.getRawX() - downX),
                            startY + (int) (ev.getRawY() - downY));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasDragging = dragging;
                    float speed = (float) Math.hypot(vx, vy);   // px/s
                    dragging = false;
                    if (web != null) {
                        web.evaluateJavascript("window.petGrab && window.petGrab(false)", null);
                        if (wasDragging && speed > 1800) {
                            // 快速甩出去
                            web.evaluateJavascript("window.petFling && window.petFling("
                                    + Math.round(speed) + ")", null);
                        } else if (!wasDragging) {
                            long t = SystemClock.uptimeMillis();
                            if (t - lastTapAt < 320) {
                                lastTapAt = 0;
                                web.evaluateJavascript("window.petDoubleTap && window.petDoubleTap()", null);
                            } else {
                                lastTapAt = t;
                            }
                        }
                    }
                    vx = vy = 0;
                    if (lp != null) {
                        lastX = lp.x;
                        lastY = lp.y;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putInt("x", lp.x).putInt("y", lp.y).apply();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    private int statusBarHeight() {
        try {
            android.content.res.Resources r = getResources();
            int id = r.getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? r.getDimensionPixelSize(id) : dp(28);
        } catch (Throwable t) {
            return dp(28);
        }
    }

    /** 把窗口挪到绝对位置（CSS 像素语义同 movePet）。 */
    private void movePetTo(final float xCss, final float yCss) {
        ui.post(() -> {
            if (lp == null || wm == null || root == null) return;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int keep = dp(80);
            int minY = -(lp.height - dp(96) - statusBarHeight());
            lp.x = Math.max(-lp.width + keep, Math.min(Math.round(xCss * density), dm.widthPixels - keep));
            lp.y = Math.max(minY, Math.min(Math.round(yCss * density), dm.heightPixels - dp(60)));
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 临时让窗口不吃触摸，好让点击落到下面的图标上。 */
    private void setTouchable(boolean on) {
        ui.post(() -> {
            if (lp == null || wm == null || root == null) return;
            lp.flags = on ? fullFlags : (fullFlags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 「表演式」启动：回桌面 → 上滑开抽屉 → 找图标 → 走到旁边 → 点开。
     * 失败返回 false，调用方再退回 monkey/am start。
     */
    public boolean visualLaunch(final String label) {
        if (!PetA11yService.isReady()) return false;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int cx = dm.widthPixels / 2;
        final int sh = dm.heightPixels;
        try {
            // 1) 先看目标是不是已经在眼前（用户可能本来就停在抽屉里）
            android.graphics.Rect r = PetA11yService.findLabel(label);

            // 2) 不在眼前就先在当前页面里滚动找（不切桌面、不重开抽屉）
            for (int i = 0; r == null && i < 8; i++) {
                if (!PetA11yService.scrollForward()) break;
                Thread.sleep(550);
                r = PetA11yService.findLabel(label);
            }

            // 3) 还是没有，才回桌面并尝试开抽屉（只开一次）
            if (r == null) {
                ShellExec.sh("input keyevent KEYCODE_HOME");
                Thread.sleep(600);
                sayFor("我去「" + label + "」那边看看…", 2200);
                String before = PetA11yService.pageFingerprint();
                ShellExec.sh("input swipe " + cx + " " + (int) (sh * 0.96) + " " + cx + " " + (int) (sh * 0.80) + " 160");
                Thread.sleep(900);
                if (PetA11yService.pageFingerprint().equals(before)) {
                    // 抽屉没开出来：退回 shell 直启，别反复尝试
                    Log.i(TAG, "visualLaunch: drawer not opened, fallback");
                    return false;
                }
                for (int i = 0; r == null && i < 8; i++) {
                    r = PetA11yService.findLabel(label);
                    if (r != null) break;
                    if (!PetA11yService.scrollForward()) break;
                    Thread.sleep(550);
                }
            }

            if (r == null) {
                Log.i(TAG, "visualLaunch: label not found");
                return false;
            }

            // 4) 走到图标旁边，暂时不吃触摸，然后点开
            float iconLeft = r.left / density;
            float iconCx = r.centerX() / density;
            float iconCy = r.centerY() / density;
            int standX = iconLeft > 190 ? Math.round(iconLeft - 150) : Math.round(iconCx + 150);
            int standY = Math.round(iconCy - 250);
            movePetTo(standX, standY);
            Thread.sleep(380);
            setTouchable(false);
            Thread.sleep(100);
            ShellExec.sh("input tap " + r.centerX() + " " + r.centerY());
            Thread.sleep(300);
            setTouchable(true);
            sayFor("找到啦，「" + label + "」！", 2600);
            Log.i(TAG, "visualLaunch ok: " + label + " " + r);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "visualLaunch failed: " + t);
            return false;
        }
    }

    /** 说话模式：只在桌面气泡里回答，不开聊天窗。 */
    private void startVoiceMode() {
        try {
            if (voiceIn != null && voiceIn.isRunning()) {
                voiceIn.stop();
                say("好，不说了。");
                return;
            }
            // 先停唤醒，别和她自己的录音抢麦
            if (wake != null) wake.stop();
            if (engine == null) engine = new ChatEngine(this);
            if (voiceTts == null) voiceTts = new Tts(this);
            if (voiceTts != null) voiceTts.stop();
            if (voiceIn == null) voiceIn = new VoiceInput();
            // 唤醒后的连续对话：说完约 3.5 秒没声音才收尾
            voiceIn.setSilenceMs(3500);
            say("在呢，你说～");
            voiceIn.start(this, new VoiceInput.Listener() {
                @Override
                public void onPartial(String text) {
                    // 像语音助手那样边说边显示听到的内容
                    long now = SystemClock.uptimeMillis();
                    if (now - lastPartialAt < 300) return;
                    lastPartialAt = now;
                    String t = text == null ? "" : text.trim();
                    if (t.isEmpty()) return;
                    if (t.length() > 26) t = t.substring(t.length() - 26);
                    sayFor("👂 " + t, 1400);
                }

                @Override
                public void onFinal(String text) {
                    if (text == null || text.trim().isEmpty()) {
                        say("没听清，再说一次？");
                        return;
                    }
                    final String q = WakeWord.stripWake(text).isEmpty()
                            ? text.trim() : WakeWord.stripWake(text);
                    Log.i(TAG, "voice-mode input=" + q);
                    sayFor("你说：「" + q + "」", 1600);
                    engine.ask(q, new ChatEngine.Callback() {
                        @Override
                        public void onUser(String t) {
                        }

                        @Override
                        public void onReply(String reply) {
                            sayFor(reply, bubbleMs());
                            if (voiceTts != null) voiceTts.speak(reply);
                        }

                        @Override
                        public void onError(String message) {
                            sayFor("连不上模型：" + message, 5000);
                        }
                    });
                }

                @Override
                public void onError(String message) {
                    sayFor("语音用不了：" + message, 5000);
                }
            }, true);
        } catch (Throwable t) {
            Log.w(TAG, "voice mode failed", t);
            sayFor("语音模式起不来：" + t.getMessage(), 5000);
        }
    }

    private void showChat(boolean voice) {
        if (chat != null) {
            if (voice) chat.startVoiceNow();
            return;
        }
        try {
            chat = new ChatPanel(this, this::hideChat, new ChatPanel.Host() {
                @Override
                public void moveBy(int dx, int dy) {
                    moveChatBy(dx, dy);
                }

                @Override
                public void resizeEdge(int edge, int dx, int dy) {
                    resizeChatEdge(edge, dx, dy);
                }

                @Override
                public void toggleMaximize() {
                    toggleChatMaximize();
                }
            });
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = Math.min(dm.widthPixels - dp(20), dp(430));
            int h = (int) (dm.heightPixels * 0.62);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            chatLp = new WindowManager.LayoutParams(w, h, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            chatLp.gravity = Gravity.TOP | Gravity.START;
            chatLp.x = (dm.widthPixels - w) / 2;
            chatLp.y = dm.heightPixels - h - dp(18);
            chatLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            wm.addView(chat, chatLp);
            Log.i(TAG, "chat window shown");
            if (wake != null) wake.stop();
            if (voice) chat.startVoiceNow();
        } catch (Throwable t) {
            Log.w(TAG, "chat window failed", t);
            chat = null;
            Toast.makeText(this, "聊天窗打不开：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final int CHAT_MIN_W = 300;
    private static final int CHAT_MIN_H = 280;

    private void moveChatBy(int dx, int dy) {
        if (chat == null || chatLp == null) return;
        Log.i(TAG, "moveChatBy " + dx + "," + dy);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        chatLp.x = Math.max(0, Math.min(chatLp.x + dx, dm.widthPixels - chatLp.width));
        chatLp.y = Math.max(0, Math.min(chatLp.y + dy, dm.heightPixels - chatLp.height));
        try {
            wm.updateViewLayout(chat, chatLp);
        } catch (Throwable ignored) {
        }
    }

    /** 拖哪条边就只动那条边，对边保持不动（Windows 的手感）。 */
    private void resizeChatEdge(int edge, int dx, int dy) {
        if (chat == null || chatLp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int minW = dp(CHAT_MIN_W), minH = dp(CHAT_MIN_H);
        int x = chatLp.x, y = chatLp.y, w = chatLp.width, h = chatLp.height;

        if ((edge & ChatPanel.EDGE_LEFT) != 0) {
            int nw = Math.max(minW, w - dx);
            x = x + (w - nw);
            w = nw;
        }
        if ((edge & ChatPanel.EDGE_RIGHT) != 0) {
            w = Math.max(minW, w + dx);
        }
        if ((edge & ChatPanel.EDGE_TOP) != 0) {
            int nh = Math.max(minH, h - dy);
            y = y + (h - nh);
            h = nh;
        }
        if ((edge & ChatPanel.EDGE_BOTTOM) != 0) {
            h = Math.max(minH, h + dy);
        }

        // 别越界：左上不出去，右下不出屏
        if (x < 0) { w += x; x = 0; }
        if (y < dp(20)) { h += (y - dp(20)); y = dp(20); }
        if (x + w > dm.widthPixels) w = dm.widthPixels - x;
        if (y + h > dm.heightPixels) h = dm.heightPixels - y;
        w = Math.max(minW, w);
        h = Math.max(minH, h);

        chatLp.x = x;
        chatLp.y = y;
        chatLp.width = w;
        chatLp.height = h;
        try {
            wm.updateViewLayout(chat, chatLp);
        } catch (Throwable ignored) {
        }
    }

    private void resizeChatTo(int w, int h) {
        if (chat == null || chatLp == null) return;
        Log.i(TAG, "resizeChatTo " + w + "x" + h);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int minW = dp(CHAT_MIN_W);
        int minH = dp(CHAT_MIN_H);
        int maxW = dm.widthPixels - dp(8);
        int maxH = (int) (dm.heightPixels * 0.92);
        chatLp.width = Math.max(minW, Math.min(w, maxW));
        chatLp.height = Math.max(minH, Math.min(h, maxH));
        // 拖大后别跑出屏幕
        chatLp.x = Math.max(0, Math.min(chatLp.x, dm.widthPixels - chatLp.width));
        chatLp.y = Math.max(0, Math.min(chatLp.y, dm.heightPixels - chatLp.height));
        try {
            wm.updateViewLayout(chat, chatLp);
        } catch (Throwable ignored) {
        }
    }

    private boolean chatMaxed = false;
    private int[] chatNormal;

    private void toggleChatMaximize() {
        if (chat == null || chatLp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (!chatMaxed) {
            chatNormal = new int[]{chatLp.width, chatLp.height, chatLp.x, chatLp.y};
            chatLp.width = dm.widthPixels;
            chatLp.height = (int) (dm.heightPixels * 0.92);
            chatLp.x = 0;
            chatLp.y = dp(20);
            chatMaxed = true;
        } else if (chatNormal != null) {
            chatLp.width = chatNormal[0];
            chatLp.height = chatNormal[1];
            chatLp.x = chatNormal[2];
            chatLp.y = chatNormal[3];
            chatMaxed = false;
        }
        try {
            wm.updateViewLayout(chat, chatLp);
        } catch (Throwable ignored) {
        }
    }

    private void hideChat() {
        if (chat == null) return;
        try {
            wm.removeView(chat);
        } catch (Throwable ignored) {
        }
        chat.release();
        chat = null;
        chatLp = null;
        chatMaxed = false;
        Log.i(TAG, "chat window hidden");
    }

    private void moveTo(int x, int y) {
        if (wm == null || lp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int keepX = dp(80);                 // 左右至少留这么多可见，能抓回来
        int statusH = statusBarHeight();
        // 顶部不能高过状态栏太多，否则她整块落在下拉栏区域，点不到
        int minY = -(lp.height - dp(96) - statusH);
        lp.x = Math.max(-lp.width + keepX, Math.min(x, dm.widthPixels - keepX));
        lp.y = Math.max(minY, Math.min(y, dm.heightPixels - dp(60)));
        try {
            wm.updateViewLayout(root, lp);
        } catch (Throwable ignored) {
        }
    }

    private String resolveHomePackage() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String currentTopPackage() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return "";
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 60000, now);
            if (ev != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                String last = "";
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
                }
                if (!last.isEmpty()) return last;
            }
            // 拿不到就返回空，交给调用方保持上一次状态（这台 ROM 的 lastTimeUsed 常年是 1970）
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean isScreenOnAndUnlocked() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return false;
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void updateVisibility() {
        if (root == null) return;
        String top = currentTopPackage();
        if (top.isEmpty()) {
            // 应用级拿不到就降级（Shizuku shell → root），全程后台线程，绝不阻塞主线程
            long nowMs = System.currentTimeMillis();
            if (!escalating && nowMs - lastShellTry > 4000) {
                lastShellTry = nowMs;
                escalating = true;
                new Thread(() -> {
                    try {
                        lastShellTop = ForegroundReader.topPackage(this);
                    } finally {
                        escalating = false;
                    }
                }, "fg-escalate").start();
            }
            if (nowMs - lastShellTry < 15000) top = lastShellTop;
        }
        if (top.isEmpty()) {
            // 没有新的前台事件：保持现状，别乱切
            return;
        }
        boolean selfTop = top.equals(getPackageName());
        // 桌面或自己的 App 里都显示她（自己的 App 里方便预览她的状态）
        boolean onHome = (!homePkg.isEmpty() && top.equals(homePkg))
                || top.startsWith("com.android.launcher")
                || top.contains("launcher")
                || selfTop;
        boolean screenOk = isScreenOnAndUnlocked();
        // 聊天窗打开时她必须继续显示：此时前台可能被识别成我们自己的包
        boolean show = (onHome || chat != null) && screenOk;
        if (tickCount++ % 10 == 0) {
            Log.d(TAG, "tick top=[" + top + "] show=" + show);
        }
        boolean voiceBusy = voiceIn != null && voiceIn.isRunning();
        updateWake(show && !selfTop && screenOk && chat == null && !voiceBusy);
        if (show && !selfTop) {
            tickIcon();
            if (!PetA11yService.isReady() && SystemClock.uptimeMillis() - lastA11yTry > 30000) {
                lastA11yTry = SystemClock.uptimeMillis();
                ensureA11y();
            }
        }
        if (show != shown) {
            shown = show;
            Log.i(TAG, "top=" + top + " show=" + show);
            applyShown(show);
        }
    }

    /** 桌面待机时听「小鲸小鲸」；进 App / 锁屏 / 息屏立刻停麦。 */
    private void updateWake(boolean should) {
        boolean enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("wake", false);
        if (!enabled) should = false;
        if (should) {
            if (wake == null) wake = new WakeWord(this);
            if (!wake.isRunning()) {
                wake.start(heard -> {
                    Log.i(TAG, "wake -> voice mode: " + heard);
                    sayFor("在呢～", 1200);
                    startVoiceMode();
                });
            }
        } else if (wake != null && wake.isRunning()) {
            wake.stop();
        }
    }

    /**
     * 收起时缩成 1x1 透明小窗而不是 setVisibility(GONE)：
     * 没有可见窗口的 App 会被 ColorOS 冻结，轮询一停就再也回不来。
     */
    private void applyShown(boolean show) {
        if (wm == null || root == null) return;
        try {
            if (show) {
                lp.width = fullW;
                lp.height = fullH;
                lp.flags = fullFlags;
                lp.x = lastX;
                lp.y = lastY;
                root.setAlpha(1f);
            } else {
                lastX = lp.x;
                lastY = lp.y;
                lp.width = Math.max(1, dp(1));
                lp.height = Math.max(1, dp(1));
                lp.flags = fullFlags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                lp.x = 0;
                lp.y = 0;
                root.setAlpha(0f);
            }
            wm.updateViewLayout(root, lp);
        } catch (Throwable t) {
            Log.w(TAG, "applyShown failed", t);
        }
    }

    /** 换识别模型后重启唤醒引擎，让新模型生效。 */
    public void restartWakeForModelChange() {
        ui.post(() -> {
            try {
                if (wake != null) {
                    wake.stop();
                    wake = null;
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 把渲染帧率下发给页面（60/90/120）。 */
    public static void applyFps(int fps) {
        PetService svc = INSTANCE;
        if (svc == null || svc.web == null) return;
        svc.ui.post(() -> {
            try {
                if (svc.web != null) {
                    svc.web.evaluateJavascript("window.petSetFps && window.petSetFps(" + fps + ")", null);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "鲸鱼娘桌宠",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("鲸鱼娘桌宠")
                .setContentText("她正待在桌面上")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 页面用得到的那几个桥方法。 */
    private class Bridge {
        @JavascriptInterface
        public String metrics() {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            return (dm.widthPixels / density) + "," + (dm.heightPixels / density);
        }

        @JavascriptInterface
        public String freeSpot() {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int pw = lp != null ? lp.width : dp(240);
            int ph = lp != null ? lp.height : dp(280);
            int maxX = Math.max(1, dm.widthPixels - pw);
            int maxY = Math.max(1, dm.heightPixels - ph);
            java.util.List<android.graphics.Rect> icons = PetA11yService.iconRects();
            long bestScore = Long.MAX_VALUE;
            int bx = -1, by = -1;
            java.util.Random rnd = new java.util.Random();
            for (int i = 0; i < 14; i++) {
                int x = rnd.nextInt(maxX + 1);
                int y = dp(40) + rnd.nextInt(Math.max(1, maxY - dp(40)));
                long overlap = 0;
                for (android.graphics.Rect r : icons) {
                    long ox = Math.min(x + pw, r.right) - Math.max(x, r.left);
                    long oy = Math.min(y + ph, r.bottom) - Math.max(y, r.top);
                    if (ox > 0 && oy > 0) overlap += ox * oy;
                }
                if (overlap < bestScore) {
                    bestScore = overlap;
                    bx = x;
                    by = y;
                }
                if (overlap == 0) break;
            }
            if (bx < 0) {
                bx = dip(dm, 20);
                by = maxY - dp(120);
            }
            return (bx / density) + "," + (by / density);
        }

        private int dip(DisplayMetrics dm, int v) {
            return Math.round(v * density);
        }

        @JavascriptInterface
        public void movePet(final int dxCss, final int dyCss) {
            ui.post(() -> {
                if (lp == null || wm == null || root == null) return;
                DisplayMetrics dm = getResources().getDisplayMetrics();
                // 允许部分出界，但顶部的下限和状态栏对齐，别让她整个落进下拉栏
                int keep = dp(80);
                int minY = -(lp.height - dp(96) - statusBarHeight());
                int nx = Math.max(-lp.width + keep,
                        Math.min(lp.x + Math.round(dxCss * density), dm.widthPixels - keep));
                int ny = Math.max(minY,
                        Math.min(lp.y + Math.round(dyCss * density), dm.heightPixels - dp(60)));
                if (nx == lp.x && ny == lp.y) return;
                lp.x = nx;
                lp.y = ny;
                try {
                    wm.updateViewLayout(root, lp);
                } catch (Throwable ignored) {
                }
            });
        }

        @JavascriptInterface
        public void movePetBy(final int dxCss) {
            ui.post(() -> {
                if (lp == null || wm == null || root == null) return;
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int dx = Math.round(dxCss * density);
                int nx = Math.max(0, Math.min(lp.x + dx, dm.widthPixels - lp.width));
                if (nx == lp.x) return;
                lp.x = nx;
                try {
                    wm.updateViewLayout(root, lp);
                } catch (Throwable ignored) {
                }
            });
        }

        @JavascriptInterface
        public String pos() {
            return (lp == null ? 0 : lp.x / density) + "," + (lp == null ? 0 : lp.y / density);
        }

        @JavascriptInterface
        public void logPose(final String name) {
            Log.i(TAG, "pose -> " + name);
        }

        @JavascriptInterface
        public void logBubble(final String text) {
            Log.i(TAG, "bubble: " + text);
        }

        @JavascriptInterface
        public void logFps(int fps) {
            Log.i(TAG, "render fps -> " + fps);
        }

        @JavascriptInterface
        public void toast(final String m) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PetService.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void startVoice() {
            ui.post(PetService.this::startVoiceMode);
        }

        @JavascriptInterface
        public void openChat(final boolean voice) {
            ui.post(() -> showChat(voice));
        }

        @JavascriptInterface
        public void quit() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    setEnabled(PetService.this, false);
                    stopSelf();
                }
            });
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (lp != null) moveTo(lp.x, lp.y);
    }

    @Override
    public void onDestroy() {
        hideChat();
        if (voiceIn != null) { voiceIn.stop(); voiceIn = null; }
        if (voiceTts != null) { voiceTts.release(); voiceTts = null; }
        if (INSTANCE == this) INSTANCE = null;
        ui.removeCallbacks(watch);
        if (wake != null) {
            wake.stop();
            wake = null;
        }
        isRunning = false;
        if (web != null) {
            if (wm != null && root != null) {
                try {
                    wm.removeView(root);
                } catch (Throwable ignored) {
                }
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统回收后尽量自己回来
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
