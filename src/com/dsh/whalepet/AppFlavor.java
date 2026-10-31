package com.dsh.whalepet;

import android.content.pm.ApplicationInfo;

import java.util.Random;

/** 打开应用时按类别说一句符合情景的话。 */
public final class AppFlavor {

    private static final Random R = new Random();

    private AppFlavor() {
    }

    private static String pick(String... lines) {
        return lines[R.nextInt(lines.length)];
    }

    public static String lineFor(ApplicationInfo ai, String label, String pkg) {
        String p = pkg == null ? "" : pkg.toLowerCase();
        String l = label == null ? "" : label;

        if ((ai.category & ApplicationInfo.CATEGORY_GAME) != 0
                || p.contains("tmgp") || p.contains("mihoyo") || p.contains("game")) {
            return pick("「" + l + "」开打了，别坑队友。",
                    "游戏时间到，我在旁边看着。",
                    "又开「" + l + "」…上分顺利？");
        }
        if (l.contains("天气") || p.contains("weather")) {
            return pick("天气我帮你看好了，记得带伞。", "外面什么天气？你自己看吧。");
        }
        if (l.contains("闹钟") || l.contains("时钟") || p.contains("clock") || p.contains("alarm")) {
            return pick("闹钟在那边，别睡过头。", "定好时间了吗？别又睡到中午。");
        }
        if (l.contains("文件") || p.contains("file") || p.contains("documents")) {
            return pick("文件管理器…又在找什么？", "东西都在里面，慢慢翻。");
        }
        if (l.contains("相机") || p.contains("camera")) {
            return pick("拍照记得开美颜。", "别拍我，我没化妆。");
        }
        if (l.contains("设置") || p.contains("settings")) {
            return pick("设置界面，慢慢调。", "又要改什么？");
        }
        if (l.contains("浏览器") || p.contains("browser") || p.contains("chrome")) {
            return pick("上网别乱点链接。", "又去逛什么了？");
        }
        if ((ai.category & ApplicationInfo.CATEGORY_SOCIAL) != 0
                || l.contains("微信") || l.contains("QQ") || p.contains("wechat")) {
            return pick("又去聊天了。", "看看谁找你。");
        }
        if ((ai.category & ApplicationInfo.CATEGORY_VIDEO) != 0
                || l.contains("视频") || l.contains("抖音") || l.contains("哔哩")) {
            return pick("看视频别太久。", "刷一会儿就回来陪我。");
        }
        if ((ai.category & ApplicationInfo.CATEGORY_AUDIO) != 0
                || l.contains("音乐") || p.contains("music")) {
            return pick("放点歌听？", "音乐时间。");
        }
        if ((ai.category & ApplicationInfo.CATEGORY_MAPS) != 0 || p.contains("maps")) {
            return pick("出门记得看路。", "要去哪儿？");
        }
        return pick("「" + l + "」给你打开啦。", "「" + l + "」在这儿。");
    }
}
