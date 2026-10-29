package com.dsh.whalepet;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/** BreezeBlue 官方「大肥鱼」音色（机灵版 / 迷糊版）。 */
public class BreezeTts {

    public static final String CLEVER = "voc_u3nxkd5578wc";  // 大肥鱼（机灵版）
    public static final String DREAMY = "voc_8yud7hz7c9q2";  // 大肥鱼（迷糊版）

    private static final String BASE = "https://api.breeze.blue";

    public static byte[] synth(String text, String apiKey, String voiceId) throws Exception {
        String url = BASE + "/v1/text-to-speech/" + voiceId + "?output_format=mp3_24000_128";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("xi-api-key", apiKey);

        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("language_code", "zh");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(bytes);
        }

        int code = c.getResponseCode();
        if (code >= 400) {
            InputStream err = c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (err != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            throw new Exception("HTTP " + code + " " + (sb.length() > 120 ? sb.substring(0, 120) : sb));
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
