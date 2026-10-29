package com.dsh.whalepet;

import android.content.Context;
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
 */
public final class BigModel {

    private static final String TAG = "WhalePet";
    public static final String URL =
            "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip";
    private static final String DIR = "vosk-cn-big";

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
        new Thread(() -> {
            try {
                File tmp = new File(ctx.getFilesDir(), "vosk-big-tmp");
                deleteRec(tmp);
                tmp.mkdirs();
                unzip(zip, tmp, cb);
                File found = null;
                File[] kids = tmp.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory() && new File(k, "am").exists()) {
                            found = k;
                            break;
                        }
                    }
                    if (found == null) for (File k : kids) if (k.isDirectory()) found = k;
                }
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
        }, "big-model-zip").start();
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }

    public static void start(final Context ctx, final Progress cb) {
        if (isReady(ctx)) {
            cb.onDone(true, "大模型已就绪");
            return;
        }
        new Thread(() -> {
            File tmp = new File(ctx.getFilesDir(), "vosk-cn-big.zip");
            try {
                download(URL, tmp, cb);
                unzip(tmp, ctx.getFilesDir(), cb);
                File d = dir(ctx);
                if (!d.exists()) throw new Exception("解压后没找到模型目录");
                new File(d, ".ready").createNewFile();
                tmp.delete();
                cb.onDone(true, "大模型准备完成");
            } catch (Throwable t) {
                Log.w(TAG, "big model failed: " + t);
                cb.onDone(false, String.valueOf(t.getMessage()));
            }
        }, "big-model").start();
    }

    private static void download(String urlStr, File out, Progress cb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.connect();
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        long total = c.getContentLengthLong();
        long done = 0;
        int lastPct = -1;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
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
