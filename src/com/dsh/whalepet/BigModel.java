package com.dsh.whalepet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 大模型（vosk-model-cn-0.22，约 1.3GB）的下载与解压。
 * 放在 filesDir/vosk-cn-big，解压完写 .ready 标记。
 *
 * 本地补丁（相对上游 v1.2.0）：
 *  1. 优先走镜像源，失败再回退官方源；
 *  2. 支持 HTTP Range 断点续传，中断后不用从 0 重来；
 *  3. 解压后按“含 am/ 的顶层目录”识别模型目录（官方包顶层叫 vosk-model-cn-0.22，镜像叫 model-cn，
 *     上游写死找 vosk-cn-big，导致下载完也装不上）；
 *  4. 支持把大模型 zip 预置在 assets/vosk-big.zip（大模型版 APK），首次启动直接流式解压，不联网。
 */
public final class BigModel {

    private static final String TAG = "WhalePet";
    private static final String DIR = "vosk-cn-big";

    /** 镜像优先（国内可达），官方源兜底。 */
    private static final String[] URLS = {
            "https://hf-mirror.com/LiangJingyi/vosk-model-cn-0.22/resolve/main/model-cn.zip",
            "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip",
    };

    /** 预置在 assets 里的大模型包（大模型版 APK 才有）。 */
    private static final String ASSET_ZIP = "vosk-big.zip";

    private static volatile boolean installing;

    public interface Progress {
        void onProgress(int percent, long done, long total);

        void onDone(boolean ok, String message);
    }

    private BigModel() {
    }

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    public static boolean isReady(Context ctx) {
        return new File(dir(ctx), ".ready").exists();
    }

    /** 从本地 zip 安装（手机上下好的包，绕开 toybox unzip 的 offset 问题）。 */
    public static void installFromZip(final Context ctx, final File zip, final Progress cb) {
        Bg.run(() -> {
            try {
                File tmp = new File(ctx.getFilesDir(), "vosk-big-tmp");
                deleteRec(tmp);
                tmp.mkdirs();
                unzip(zip, tmp, cb);
                File found = findModelDir(tmp);
                if (found == null) throw new Exception("zip 里没有模型目录");
                File dest = dir(ctx);
                deleteRec(dest);
                if (!found.renameTo(dest)) throw new Exception("移动到目标目录失败");
                deleteRec(tmp);
                new File(dest, ".ready").createNewFile();
                cb.onDone(true, "大模型安装完成");
            } catch (Throwable t) {
                Log.w(TAG, "installFromZip failed: " + t);
                cb.onDone(false, String.valueOf(t.getMessage()));
            }
        });
    }

    /**
     * 大模型版 APK：assets 里预置了 vosk-big.zip，首次启动直接流式解压到 filesDir/vosk-cn-big。
     * 只尝试一次（成功后写 .ready；失败写标记，避免每次启动都重试）。
     */
    public static void installFromAssetsIfPresent(final Context ctx, final Progress cb) {
        if (isReady(ctx) || installing) return;
        SharedPreferences p = ctx.getSharedPreferences("pet", Context.MODE_PRIVATE);
        if (p.getBoolean("bigAssetTried", false)) return;
        boolean has = false;
        try {
            String[] root = ctx.getAssets().list("");
            if (root != null) {
                for (String n : root) {
                    if (ASSET_ZIP.equals(n)) {
                        has = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (!has) return;
        p.edit().putBoolean("bigAssetTried", true).apply();
        installing = true;
        Bg.run(() -> {
            try {
                File dest = dir(ctx);
                deleteRec(dest);
                dest.mkdirs();
                int count = 0;
                try (InputStream in = ctx.getAssets().open(ASSET_ZIP);
                     BufferedInputStream bin = new BufferedInputStream(in, 1 << 16);
                     ZipInputStream zis = new ZipInputStream(bin)) {
                    ZipEntry e;
                    byte[] buf = new byte[1 << 16];
                    File base = ctx.getFilesDir();
                    while ((e = zis.getNextEntry()) != null) {
                        String name = e.getName();
                        File out = new File(base, name);
                        if (!out.getCanonicalPath().startsWith(base.getCanonicalPath())) continue;
                        if (e.isDirectory()) {
                            out.mkdirs();
                        } else {
                            File parent = out.getParentFile();
                            if (parent != null) parent.mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                int n;
                                while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                            }
                        }
                        count++;
                        if (count % 20 == 0) cb.onProgress(-1, count, 0);
                    }
                }
                File found = findModelDir(ctx.getFilesDir());
                if (found == null) throw new Exception("assets 里没有模型目录");
                if (!found.equals(dest)) {
                    deleteRec(dest);
                    if (!found.renameTo(dest)) throw new Exception("移动到目标目录失败");
                }
                new File(dest, ".ready").createNewFile();
                cb.onDone(true, "内置大模型准备完成");
            } catch (Throwable t) {
                Log.w(TAG, "installFromAssets failed: " + t);
                cb.onDone(false, String.valueOf(t.getMessage()));
            } finally {
                installing = false;
            }
        });
    }

    public static void start(final Context ctx, final Progress cb) {
        if (isReady(ctx)) {
            cb.onDone(true, "大模型已就绪");
            return;
        }
        Bg.run(() -> {
            File tmp = new File(ctx.getFilesDir(), "vosk-cn-big.zip");
            try {
                Throwable last = null;
                for (String url : URLS) {
                    try {
                        Log.i(TAG, "big model download from " + url);
                        download(url, tmp, cb);
                        last = null;
                        break;
                    } catch (Throwable t) {
                        last = t;
                        Log.w(TAG, "download failed: " + url + " -> " + t);
                    }
                }
                if (last != null) throw last;
                unzip(tmp, ctx.getFilesDir(), cb);
                File d = settleModelDir(ctx);
                new File(d, ".ready").createNewFile();
                tmp.delete();
                cb.onDone(true, "大模型准备完成");
            } catch (Throwable t) {
                Log.w(TAG, "big model failed: " + t);
                cb.onDone(false, String.valueOf(t.getMessage()));
            }
        });
    }

    /** 解压后的顶层目录名随压缩包而变（vosk-model-cn-0.22 / model-cn / vosk-cn-big），统一收敛到目的目录。 */
    private static File settleModelDir(Context ctx) throws Exception {
        File dest = dir(ctx);
        if (new File(dest, "am").exists()) return dest;
        File found = findModelDir(ctx.getFilesDir());
        if (found == null) throw new Exception("解压后没找到模型目录");
        deleteRec(dest);
        if (!found.renameTo(dest)) throw new Exception("移动到目标目录失败");
        return dest;
    }

    private static File findModelDir(File root) {
        File[] kids = root.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory() && new File(k, "am").exists()) return k;
        }
        return null;
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    private static HttpURLConnection open(String urlStr, long from) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        c.connect();
        return c;
    }

    /** 支持断点续传：本地已有部分文件就用 Range 接着下。 */
    private static void download(String urlStr, File out, Progress cb) throws Exception {
        long have = out.exists() ? out.length() : 0L;
        HttpURLConnection c = open(urlStr, have);
        int code = c.getResponseCode();
        // 服务端不支持 Range（回 200）或区间无效（416）时，清空重下
        if (have > 0 && code != 206) {
            Log.w(TAG, "no resume (HTTP " + code + "), restart from 0");
            c.disconnect();
            out.delete();
            have = 0L;
            c = open(urlStr, 0L);
            code = c.getResponseCode();
        }
        if (code >= 400) throw new Exception("HTTP " + code);
        long remain = c.getContentLengthLong();
        long total = remain > 0 ? have + remain : 0L;
        long done = have;
        int lastPct = -1;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out, have > 0))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != lastPct) {
                    lastPct = pct;
                    cb.onProgress(pct, done, total);
                }
            }
        }
    }

    private static void unzip(File zip, File destRoot, Progress cb) throws Exception {
        long totalEntries = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            int count = 0;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                File out = new File(destRoot, name);
                if (!out.getCanonicalPath().startsWith(destRoot.getCanonicalPath())) continue;
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                count++;
                if (count % 20 == 0) cb.onProgress(-1, count, 0);
            }
            totalEntries = count;
        }
        Log.i(TAG, "unzipped " + totalEntries + " entries");
    }
}
