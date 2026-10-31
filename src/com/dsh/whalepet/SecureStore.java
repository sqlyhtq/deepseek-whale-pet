package com.dsh.whalepet;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 用 Android Keystore 里的 AES-GCM 密钥加密少量敏感值（API Key）。
 * 明文写法（enc: 前缀）第一次读取时会自动加密迁移。
 */
public final class SecureStore {

    private static final String TAG = "WhalePet";
    private static final String ALIAS = "whale_prefs_key";
    private static final String PREFIX = "enc:";

    private SecureStore() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    public static String get(SharedPreferences prefs, String name) {
        String raw = prefs.getString(name, "");
        if (raw == null || raw.isEmpty()) return "";
        if (!raw.startsWith(PREFIX)) {
            // 旧版本明文：顺手加密
            put(prefs, name, raw);
            return raw;
        }
        try {
            byte[] all = Base64.decode(raw.substring(PREFIX.length()), Base64.NO_WRAP);
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] body = Arrays.copyOfRange(all, 12, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(body), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "decrypt failed: " + t);
            return "";
        }
    }

    public static void put(SharedPreferences prefs, String name, String value) {
        if (value == null) value = "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] body = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(body, 0, all, iv.length, body.length);
            prefs.edit().putString(name, PREFIX + Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Throwable t) {
            Log.w(TAG, "encrypt failed, store plain: " + t);
            prefs.edit().putString(name, value).apply();
        }
    }
}
