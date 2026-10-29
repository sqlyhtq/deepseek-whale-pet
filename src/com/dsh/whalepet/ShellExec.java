package com.dsh.whalepet;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 分级「用更高权限启动应用」：
 *   1) Shizuku shell（已授权，uid=2000）→ monkey/am start，绕过后台启动限制
 *   2) root（KernelSU/su）
 *   3) 普通 startActivity（可能被系统拦或需要确认）
 */
public final class ShellExec {

    private static final String TAG = "WhalePet";

    private ShellExec() {
    }

    /** 用 shell 拉起某个包的主入口；成功返回 true。 */
    public static boolean launchPackage(Context ctx, String pkg) {
        String cmd = "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1 2>/dev/null"
                + " || am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p " + pkg;
        String out = sh(cmd);
        Log.i(TAG, "shell launch " + pkg + " -> " + (out == null ? "null" : out.trim()));
        if (out == null) return false;
        // monkey 成功会打印 "Events injected: 1"；am start 成功会打印 "Starting:"
        String s = out.toLowerCase();
        if (s.contains("events injected") || s.contains("starting:") || s.contains("activity:")) return true;
        return !s.contains("error") && !s.contains("exception") && !s.contains("no activities");
    }

    /** 用 shell 执行任意命令（内部带 2s 超时），返回 stdout；都不可用返回 null。 */
    public static String sh(String cmd) {
        String out = viaShizuku(cmd);
        if (out != null) return out;
        return viaRoot(cmd);
    }

    private static String viaShizuku(String cmd) {
        try {
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission()
                    != PackageManager.PERMISSION_GRANTED) return null;
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            return read(p);
        } catch (Throwable t) {
            Log.w(TAG, "shizuku sh failed: " + t);
            return null;
        }
    }

    private static String viaRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return read(p);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String read(final Process p) {
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } catch (Throwable ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                reader.join(300);
                return sb.toString();
            }
            reader.join(500);
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }
}
