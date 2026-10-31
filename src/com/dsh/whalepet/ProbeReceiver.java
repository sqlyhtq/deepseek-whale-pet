package com.dsh.whalepet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 语音链路自检：adb/shell 发一个显式广播，就会用当前设置的声音念一句固定话。
 * 只念固定文本，不接受外部传入的内容，没有提权面。
 */
public class ProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // 只接受 root(0) / shell(2000) / 本应用 发来的调试广播
        int uid = android.os.Binder.getCallingUid();
        if (uid != android.os.Process.myUid() && uid != 0 && uid != 2000) {
            android.util.Log.w("WhalePet", "probe rejected from uid " + uid);
            return;
        }
        Log.i("WhalePet", "probe broadcast received: " + intent.getAction());
        if ("com.dsh.whalepet.TTS_DONE_PROBE".equals(intent.getAction())) {
            Tts t = new Tts(context);
            t.speak("播放完成回调测试。", () -> Log.i("WhalePet", "tts done callback fired"));
            return;
        }
        if ("com.dsh.whalepet.TOP_PROBE".equals(intent.getAction())) {
            Bg.run(() -> {
                boolean alive = ForegroundReader.shizukuAlive();
                boolean granted = ForegroundReader.shizukuGranted();
                String top = ForegroundReader.topPackage(context);
                Log.i("WhalePet", "top probe: shizukuAlive=" + alive + " granted=" + granted
                        + " top=[" + top + "]");
            });
            return;
        }
        if ("com.dsh.whalepet.USAGE_PROBE".equals(intent.getAction())) {
            Bg.run(() -> {
                try {
                    android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager)
                            context.getSystemService(android.content.Context.USAGE_STATS_SERVICE);
                    long now = System.currentTimeMillis();
                    android.app.usage.UsageEvents ev = usm.queryEvents(now - 600000, now);
                    android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                    int n = 0;
                    String last = "";
                    long lastTs = 0;
                    while (ev != null && ev.hasNextEvent()) {
                        ev.getNextEvent(e);
                        n++;
                        if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                            last = e.getPackageName();
                            lastTs = e.getTimeStamp();
                        }
                    }
                    Log.i("WhalePet", "usage events=" + n + " last=" + last
                            + " ago=" + (now - lastTs) / 1000 + "s");
                } catch (Throwable t) {
                    Log.e("WhalePet", "usage probe failed", t);
                }
            });
            return;
        }
        if ("com.dsh.whalepet.WEATHER_PROBE".equals(intent.getAction())) {
            String q = intent.getStringExtra("text");
            Bg.run(() -> Log.i("WhalePet", "weather probe [" + q + "] -> "
                    + LocalSkills.weather(q == null ? "北京天气" : q, "")));
            return;
        }
        if ("com.dsh.whalepet.SAY_PROBE".equals(intent.getAction())) {
            String t = intent.getStringExtra("text");
            Tts tts = new Tts(context);
            tts.speak(t == null ? "小鲸小鲸" : t);
            return;
        }
        if ("com.dsh.whalepet.BUBBLE_PROBE".equals(intent.getAction())) {
            PetService.say("气泡测试：我是吃白饭的蓝色大肥鱼。");
            return;
        }
        if ("com.dsh.whalepet.LOCAL_PROBE".equals(intent.getAction())) {
            String q = intent.getStringExtra("text");
            String r = LocalSkills.handle(context, q);
            Log.i("WhalePet", "local probe [" + q + "] -> " + r);
            return;
        }
        if ("com.dsh.whalepet.POSE_PROBE".equals(intent.getAction())) {
            PetService.pose("think");
            return;
        }
        if ("com.dsh.whalepet.VOSK_PROBE".equals(intent.getAction())) {
            Bg.run(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    org.vosk.Model m = VoiceInput.model(context);
                    Log.i("WhalePet", "vosk model ok in " + (System.currentTimeMillis() - t0) + "ms");
                    m.close();
                } catch (Throwable t) {
                    Log.e("WhalePet", "vosk model failed", t);
                }
            });
            return;
        }
        if ("com.dsh.whalepet.WAKE_MATCH_PROBE".equals(intent.getAction())) {
            String[] cases = {
                    "小鲸小鲸", "小鲸小鲸，帮我看下时间", "嗯…小鲸小鲸", "鲸鱼娘你在吗",
                    "我说那个小鲸小鲸到底在不在", "今天吃了吗",
                    "小经小经帮我看看几点了", "小静小静"
            };
            for (String c : cases) {
                Log.i("WhalePet", "match [" + c + "] -> " + WakeWord.matchesKeyword(c)
                        + " strip=[" + WakeWord.stripWake(c) + "]");
            }
            return;
        }
        if ("com.dsh.whalepet.WAKE_PROBE".equals(intent.getAction())) {
            WakeWord w = new WakeWord(context);
            w.start(heard -> Log.i("WhalePet", "wake probe hit: " + heard));
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(w::stop, 8000);
            return;
        }
        if ("com.dsh.whalepet.WAKE_SIM".equals(intent.getAction())) {
            PetService.openChatWindow(true);
            return;
        }
        Tts t = new Tts(context);
        t.speak("语音自检，一二三，我是鲸鱼娘。");
    }
}
