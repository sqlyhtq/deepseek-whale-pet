package com.dsh.whalepet;

import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONObject;

/** 能在本机直接办的事就不打扰模型：时间/日期/电量/开关手电筒/音量/闹钟/打开应用或设置。 */
public final class LocalSkills {

    private static final Pattern ALARM = Pattern.compile("(\\d{1,2})[:：点](\\d{1,2})?");

    private LocalSkills() {
    }

    public static boolean wantsWeather(String q) {
        return q != null && (q.contains("天气") || q.contains("气温") || q.contains("下雨") || q.contains("冷不冷") || q.contains("热不热"));
    }

    /** 免 key 的天气：Open-Meteo 地理编码 + 当前/当日预报。阻塞调用，放到后台线程。 */
    public static String weather(String q, String savedCity) {
        String city = cleanCity(q);
        if (city.isEmpty()) city = (savedCity == null || savedCity.trim().isEmpty()) ? "北京" : savedCity.trim();
        int day = 0;
        if (q != null) {
            if (q.contains("大后天")) day = 3;
            else if (q.contains("后天")) day = 2;
            else if (q.contains("明天")) day = 1;
        }
        try {
            String geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name="
                    + URLEncoder.encode(city, "UTF-8"));
            JSONObject g = new JSONObject(geo);
            if (!g.has("results") || g.getJSONArray("results").length() == 0) {
                return "没找到「" + city + "」这个地名。";
            }
            JSONObject r0 = g.getJSONArray("results").getJSONObject(0);
            double lat = r0.getDouble("latitude");
            double lon = r0.getDouble("longitude");
            String name = r0.optString("name", city);
            String f = get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,apparent_temperature,weather_code"
                    + "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max"
                    + "&timezone=auto&forecast_days=5");
            JSONObject fj = new JSONObject(f);
            JSONObject daily = fj.getJSONObject("daily");
            int hi = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(day));
            int lo = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(day));
            int dcode = daily.getJSONArray("weather_code").optInt(day, 0);
            int rain = daily.getJSONArray("precipitation_probability_max").optInt(day, 0);
            String when = day == 0 ? "今天" : day == 1 ? "明天" : day == 2 ? "后天" : "大后天";

            if (q != null && q.contains("下雨")) {
                String guess = rain >= 50 ? "会下雨" : rain >= 25 ? "可能会飘点雨" : "不太会下雨";
                return name + when + guess + "（降水概率 " + rain + "%），" + lo + "~" + hi + "°C。";
            }
            if (q != null && q.contains("穿") || q != null && q.contains("冷")) {
                String tip = hi >= 28 ? "穿短袖就行" : hi >= 20 ? "薄外套够了" : hi >= 12 ? "加件外套" : "要穿厚一点";
                return name + when + lo + "~" + hi + "°C，" + codeText(dcode) + "，" + tip + "。";
            }

            if (day == 0) {
                JSONObject cur = fj.getJSONObject("current");
                int t = (int) Math.round(cur.getDouble("temperature_2m"));
                int feels = (int) Math.round(cur.getDouble("apparent_temperature"));
                int code = cur.optInt("weather_code", 0);
                return name + "现在 " + t + "°C（体感 " + feels + "°C），" + codeText(code)
                        + "，今天 " + lo + "~" + hi + "°C，降水概率 " + rain + "%。";
            }
            return name + when + " " + lo + "~" + hi + "°C，" + codeText(dcode)
                    + "，降水概率 " + rain + "%。";
        } catch (Throwable e) {
            return "天气查不到：" + e.getMessage();
        }
    }

    /** 从「今天上海天气怎么样」里抠出城市；抠不出来返回空串。 */
    static String cleanCity(String q) {
        if (q == null) return "";
        java.util.regex.Matcher m = Pattern.compile("([\\u4e00-\\u9fa5]{2,6})(?:的)?(?:天气|气温|下雨|冷不冷|热不热)").matcher(q);
        if (!m.find()) return "";
        String c = m.group(1)
                .replaceAll("^(今天|明天|后天|现在|目前|这里|那里|这边|那边|你们|我们|我|你)", "")
                .replaceAll("的$", "");
        if (c.isEmpty()) return "";
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "whale-pet");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String codeText(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "少云";
        if (code == 3) return "阴";
        if (code <= 48) return "有雾";
        if (code <= 57) return "毛毛雨";
        if (code <= 67) return "下雨";
        if (code <= 77) return "下雪";
        if (code <= 82) return "阵雨";
        if (code <= 86) return "阵雪";
        return "雷雨";
    }

    /** 返回 null 表示这条交给模型。 */
    public static String handle(Context ctx, String input) {
        if (input == null) return null;
        String q = input.trim();

        if (q.contains("几点") || q.contains("什么时间") || q.equals("时间")) {
            return "现在 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()) + "。";
        }
        if (q.contains("几号") || q.contains("星期几") || q.contains("日期")) {
            return new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date()) + "。";
        }
        if (q.contains("电量") || q.contains("剩多少电")) {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            int p = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
            return p >= 0 ? ("电量还有 " + p + "%。") : "读不到电量，你看下状态栏。";
        }
        if (q.contains("手电筒") || q.contains("闪光灯")) {
            boolean on = q.contains("开") || q.contains("亮");
            String r = torch(ctx, on);
            if (r != null) return r;
        }
        if (q.contains("音量")) {
            String r = volume(ctx, q);
            if (r != null) return r;
        }
        if (q.contains("闹钟") || q.contains("叫醒")) {
            String r = alarm(ctx, q);
            if (r != null) return r;
        }
        if (q.contains("唤醒")) {
            boolean on = q.contains("开") || q.contains("启用");
            ctx.getSharedPreferences("pet", Context.MODE_PRIVATE).edit()
                    .putBoolean("wake", on).apply();
            return on ? "好，我在桌面上听着「小鲸小鲸」。" : "行，先不听麦了。";
        }
        if (q.startsWith("打开设置") || q.contains("去设置")) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "设置给你打开了。";
            } catch (Throwable t) {
                return "打不开设置：" + t.getMessage();
            }
        }
        // 「调用XX」= 快速直启；「打开XX」= 先找图标再点开
        boolean fastLaunch = q.startsWith("调用") || q.startsWith("快速打开");
        String openWord = q.startsWith("调用") ? "调用" : (q.startsWith("快速打开") ? "快速打开" : "打开");
        if (q.startsWith("打开") || fastLaunch) {
            String name = q.substring(openWord.length()).replace("应用", "").replace("app", "")
                    .replace(" ", "").replace("\u3000", "")
                    .replace("吧", "").replace("啊", "").replace("呀", "").trim();
            String r = launchApp(ctx, name, fastLaunch);
            if (r != null) return r;
        }
        return null;
    }

    private static String torch(Context ctx, boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return "没有相机服务。";
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(c)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    id = c;
                    break;
                }
            }
            if (id == null) return "这台机器没有闪光灯。";
            cm.setTorchMode(id, on);
            return on ? "手电筒开了。" : "手电筒关了。";
        } catch (Throwable t) {
            return "手电筒没操作成功：" + t.getMessage();
        }
    }

    private static String volume(Context ctx, String q) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "没有音频服务。";
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (q.contains("静音")) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                return "已静音。";
            }
            if (q.contains("最大")) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                return "音量拉满了。";
            }
            if (q.contains("大") || q.contains("小")) {
                boolean up = q.contains("大");
                int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int next = Math.max(0, Math.min(max, cur + (up ? max / 5 : -max / 5)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                return "音量" + (up ? "调大" : "调小") + "到 " + Math.round(next * 100f / max) + "%。";
            }
        } catch (Throwable t) {
            return "音量没调成：" + t.getMessage();
        }
        return null;
    }

    private static String alarm(Context ctx, String q) {
        Matcher m = ALARM.matcher(q);
        if (!m.find()) return "你想定几点的？说个时间，比如「7:30」。";
        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (q.contains("下午") && hour < 12) hour += 12;
        if (hour > 23 || minute > 59) return "这个时间不太对，换一个？";
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
            i.putExtra(AlarmClock.EXTRA_HOUR, hour);
            i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
            i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return String.format(Locale.CHINA, "定好了，%02d:%02d 叫你。", hour, minute);
        } catch (Throwable t) {
            return "闹钟没定成：" + t.getMessage();
        }
    }

    /** 常用别名，帮她认「b站」「小破站」这类说法。 */
    private static final String[][] ALIASES = {
            {"微信", "wechat", "vx", "wx"},
            {"哔哩哔哩", "b站", "小破站", "bilibili", "bili"},
            {"淘宝", "taobao", "某宝"},
            {"支付宝", "alipay", "蚂蚁"},
            {"抖音", "douyin", "tiktok"},
            {"快手", "kuaishou"},
            {"小红书", "xhs", "redbook"},
            {"知乎", "zhihu"},
            {"微博", "weibo"},
            {"网易云音乐", "网易云", "cloudmusic"},
            {"QQ", "qq", "扣扣"},
            {"高德地图", "高德", "amap"},
            {"美团", "meituan"},
            {"饿了么", "eleme"},
            {"京东", "jd", "jingdong"},
            {"拼多多", "pdd"},
            {"优酷", "youku"},
            {"爱奇艺", "iqiyi"},
            {"腾讯视频", "qqlive"},
            {"钉钉", "dingtalk"},
            {"酷狗音乐", "kugou"},
            {"QQ音乐", "qqmusic"},
            {"夸克", "quark"},
            {"迅雷", "thunder"},
            {"相机", "camera"},
            {"设置", "settings"},
            {"文件管理", "files"},
    };

    private static String norm(String s) {
        return s == null ? "" : s.replace(" ", "").replace("\u3000", "")
                .replace("app", "").toLowerCase(Locale.CHINA);
    }

    /** 关键词 → 别名集合。 */
    private static java.util.List<String> aliasesOf(String name) {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add(norm(name));
        for (String[] row : ALIASES) {
            boolean hit = false;
            for (String a : row) {
                if (norm(a).equals(norm(name)) || norm(name).contains(norm(a)) || norm(a).contains(norm(name))) {
                    hit = true;
                    break;
                }
            }
            if (hit) for (String a : row) out.add(norm(a));
        }
        return out;
    }

    private static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        return d[a.length()][b.length()];
    }

    private static String launchApp(Context ctx, String name, boolean fast) {
        name = name.replace(" ", "").replace("\u3000", "").trim();
        if (name.isEmpty()) return null;
        android.util.Log.i("WhalePet", "launchApp name=[" + name + "]");
        PackageManager pm = ctx.getPackageManager();

        java.util.List<String> keys = aliasesOf(name);
        String bestPkg = null, bestLabel = null;
        int bestScore = -1;

        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String label = pm.getApplicationLabel(ai).toString();
            String nl = norm(label);
            String np = norm(ai.packageName);
            int score = -1;
            for (String k : keys) {
                if (k.isEmpty()) continue;
                if (nl.equals(k)) score = Math.max(score, 100);
                else if (nl.contains(k)) score = Math.max(score, 80);
                else if (k.length() >= 2 && k.contains(nl)) score = Math.max(score, 70);
                else if (np.contains(k)) score = Math.max(score, 60);
                else if (k.length() >= 2 && distance(nl, k) <= (k.length() >= 4 ? 2 : 1)) score = Math.max(score, 50);
            }
            // 同类里优先名字短的（避免“微信”匹配到“微信读书”之类的长名）
            if (score > 0 && (score > bestScore || (score == bestScore && bestLabel != null
                    && label.length() < bestLabel.length()))) {
                bestScore = score;
                bestLabel = label;
                bestPkg = ai.packageName;
            }
        }
        if (bestPkg == null || bestScore < 50) return "没找到叫「" + name + "」的应用。";
        Intent i = pm.getLaunchIntentForPackage(bestPkg);
        if (i == null) return "「" + bestLabel + "」没有可启动的入口。";
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final String pkg = bestPkg;
        final Context appCtx = ctx.getApplicationContext();
        final Intent intent = i;
        final String label = bestLabel;
        new Thread(() -> {
            // 只走 shell 直启：不碰桌面手势、不开抽屉、不注入点击
            boolean ok = ShellExec.launchPackage(appCtx, pkg);
            if (!ok) {
                try {
                    appCtx.startActivity(intent);
                } catch (Throwable ignored) {
                }
            }
        }, "launch-app").start();
        return "给你打开「" + label + "」。";
    }
}
