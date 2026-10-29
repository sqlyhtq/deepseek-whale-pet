package com.dsh.whalepet;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * 分级拿「当前前台是谁」：先用应用级 UsageStats，拿不到再走 Shizuku shell，
 * 还不行再试 root（KernelSU / su）。全部失败就返回空，调用方保持现状。
 */
public final class ForegroundReader {

    private static final String TAG = "WhalePet";
    public static final int REQ_SHIZUKU = 3001;

    private ForegroundReader() {
    }

    public static boolean shizukuAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shizukuGranted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestShizuku() {
        try {
            if (shizukuAlive() && !shizukuGranted()) Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Log.w(TAG, "shizuku request failed", t);
        }
    }

    /** shell 命令读当前焦点窗口的包名。 */
    public static String topPackage(Context ctx) {
        String p = viaShell();
        if (p != null && !p.isEmpty()) return p;
        p = viaRoot();
        if (p != null && !p.isEmpty()) return p;
        return "";
    }

    private static final String CMD =
            "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus";

    private static String viaShell() {
        if (!shizukuAlive() || !shizukuGranted()) return "";
        try {
            // Shizuku 13.x 把 newProcess 收成 private 了，只能反射调
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", CMD}, null, null);
            return parse(run(p));
        } catch (Throwable t) {
            Log.w(TAG, "shizuku exec failed: " + t);
            return "";
        }
    }

    private static String viaRoot() {
        try {
            return parse(run(Runtime.getRuntime().exec(new String[]{"su", "-c", CMD})));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 带超时：su 等授权弹窗、Shizuku 远程进程都可能挂着，绝不能拖死调用线程。 */
    private static String run(final Process p) {
        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append(' ');
            } catch (Throwable ignored) {
            }
        }, "fg-reader");
        reader.setDaemon(true);
        reader.start();

        Thread waiter = new Thread(() -> {
            try {
                p.waitFor();
            } catch (Throwable ignored) {
            }
        }, "fg-waiter");
        waiter.setDaemon(true);
        waiter.start();
        try {
            waiter.join(1500);
        } catch (InterruptedException ignored) {
        }
        if (waiter.isAlive()) {
            Log.w(TAG, "command timed out");
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
            return "";
        }
        try {
            reader.join(400);
        } catch (InterruptedException ignored) {
        }
        return sb.toString();
    }

    /** 输出形如：mCurrentFocus=Window{... u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI} */
    private static String parse(String out) {
        if (out == null) return "";
        int i = out.indexOf(" u0 ");
        if (i < 0) return "";
        String rest = out.substring(i + 4).trim();
        int sp = rest.indexOf('/');
        if (sp > 0) return rest.substring(0, sp);
        int br = rest.indexOf('}');
        return br > 0 ? rest.substring(0, br) : rest;
    }
}
