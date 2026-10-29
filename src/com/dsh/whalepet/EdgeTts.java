package com.dsh.whalepet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 Edge TTS 客户端（微软神经网络语音）。
 * 走 wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1，
 * 自己实现 WebSocket 握手与帧，返回 MP3 字节。
 */
public class EdgeTts {

    private static final String HOST = "speech.platform.bing.com";
    private static final String PATH = "/consumer/speech/synthesize/readaloud/edge/v1";
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String GEC_VERSION = "2-143.0.3650.75";

    public static byte[] synth(String text, String voice) throws Exception {
        String gec = SecGec.token();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(HOST, 443), 12000);
        raw.setSoTimeout(20000);
        SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(raw, HOST, 443, true);
        ssl.startHandshake();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
        String connId = UUID.randomUUID().toString().replace("-", "");

        String req = "GET " + PATH + "?TrustedClientToken=" + TOKEN + "&ConnectionId=" + connId
                + "&Sec-MS-GEC=" + gec + "&Sec-MS-GEC-Version=" + GEC_VERSION + " HTTP/1.1\r\n"
                + "Host: " + HOST + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"

                + "Accept-Encoding: gzip, deflate, br\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "Pragma: no-cache\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0\r\n\r\n";

        OutputStream out = ssl.getOutputStream();
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = ssl.getInputStream();
        String respHead = readHttpHead(in);
        if (!respHead.startsWith("HTTP/1.1 101")) {
            ssl.close();
            throw new Exception("handshake " + firstLine(respHead));
        }

        // 1) 语音配置
        String cfg = "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":"
                + "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}";
        sendText(ssl, out, "X-Timestamp:" + ts() + "\r\nContent-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n" + cfg);

        // 2) SSML
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>"
                + "<voice name='" + voice + "'>"
                + "<prosody pitch='+2Hz' rate='+4%' volume='+0%'>" + escape(text) + "</prosody>"
                + "</voice></speak>";
        String rid = UUID.randomUUID().toString().replace("-", "");
        sendText(ssl, out, "X-RequestId:" + rid + "\r\nContent-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + ts() + "Z\r\nPath:ssml\r\n\r\n" + ssml);

        // 3) 收音频
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 25000;
        boolean done = false;
        while (!done && System.currentTimeMillis() < deadline) {
            Frame f = readFrame(in);
            if (f == null) break;
            if (f.opcode == 0x8) break;
            if (f.opcode == 0x9) { sendFrame(out, 0xA, f.payload, true); continue; }
            if (f.opcode == 0x1) {
                String s = new String(f.payload, StandardCharsets.UTF_8);
                if (s.contains("Path:turn.end")) done = true;
            } else if (f.opcode == 0x2) {
                // 二进制帧：前 2 字节是大端头长度，之后是 "Path:audio\r\n"
                int hlen = ((f.payload[0] & 0xFF) << 8) | (f.payload[1] & 0xFF);
                int start = 2 + hlen;
                if (start < f.payload.length) audio.write(f.payload, start, f.payload.length - start);
            }
        }
        try { ssl.close(); } catch (Exception ignored) {}
        byte[] mp3 = audio.toByteArray();
        if (mp3.length == 0) throw new Exception("没有收到音频");
        return mp3;
    }

    /** Sec-MS-GEC：Windows file time（圆整到 5 分钟）+ token 的 SHA256 大写十六进制。 */
    private static String secMsGec() throws Exception {
        long winEpoch = 11644473600L;
        double now = System.currentTimeMillis() / 1000.0 + winEpoch;
        double ticks = now * 10000000L;
        ticks -= ticks % 3000000000L; // 5 分钟
        String s = String.format(Locale.US, "%.0f", ticks) + TOKEN;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    private static String ts() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static String firstLine(String s) {
        int i = s.indexOf("\r\n");
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&apos;").replace("\"", "&quot;");
    }

    private static String readHttpHead(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last4 = 0;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) break;
            if (buf.size() > 16384) throw new Exception("响应头过长");
        }
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void sendText(Socket s, OutputStream out, String s2) throws Exception {
        sendFrame(out, 0x1, s2.getBytes(StandardCharsets.UTF_8), true);
    }

    private static synchronized void sendFrame(OutputStream out, int opcode, byte[] payload, boolean mask)
            throws Exception {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
        int len = payload.length;
        int maskBit = mask ? 0x80 : 0;
        if (len < 126) {
            f.write(maskBit | len);
        } else if (len < 65536) {
            f.write(maskBit | 126);
            f.write((len >> 8) & 0xFF);
            f.write(len & 0xFF);
        } else {
            f.write(maskBit | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        if (mask) {
            byte[] k = new byte[4];
            new SecureRandom().nextBytes(k);
            f.write(k);
            for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ k[i % 4]);
        } else {
            f.write(payload);
        }
        out.write(f.toByteArray());
        out.flush();
    }

    private static class Frame {
        int opcode;
        byte[] payload;
    }

    private static Frame readFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) in.read() << 8) | in.read();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
        }
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            readFully(in, maskKey, 4);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload, payload.length);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= maskKey[i % 4];
        Frame f = new Frame();
        f.opcode = opcode;
        f.payload = payload;
        if (!fin && opcode == 0x0) {
            // 忽略续帧（Edge TTS 一般不分片）
        }
        return f;
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws Exception {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new Exception("连接提前断开");
            off += r;
        }
    }

    public static String voiceName(String code) {
        if ("xiaoxiao".equals(code)) return "zh-CN-XiaoxiaoNeural";
        if ("yunxi".equals(code)) return "zh-CN-YunxiNeural";
        if ("xiaoyi".equals(code)) return "zh-CN-XiaoyiNeural";
        return "zh-CN-XiaoyiNeural";
    }
}
