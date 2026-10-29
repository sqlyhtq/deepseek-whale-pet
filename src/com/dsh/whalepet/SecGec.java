package com.dsh.whalepet;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 微软 Edge TTS 的 Sec-MS-GEC v2 令牌（CS64-SHA256 组合）纯 Java 实现。
 * 迁移自 edge-tts 的 drm 模块，用来通过 speech.platform.bing.com 的鉴权。
 */
public final class SecGec {

    private static final long M = 0xFFFFFFFFL;
    private static final long WIN_EPOCH = 11644473600L;
    private static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    private static final long[] SHA256_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
    };

    private static final long[] SHA256_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
    };

    private static final long[] SHA256_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL, 0xA622EE8CL,
            0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L, 0xF0C31573L, 0x7058CE4EL,
            0x7188E606L, 0x00E5AB33L, 0xD258B060L, 0x72766564L
    };

    private static final long[] ROUND = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL,
            0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L, 0xD807AA98L, 0x12835B01L,
            0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L,
            0xC19BF174L, 0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL, 0x983E5152L,
            0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L,
            0x06CA6351L, 0x14292967L, 0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL,
            0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L,
            0xD6990624L, 0xF40E3585L, 0x106AA070L, 0x19A4C116L, 0x1E376C08L,
            0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL,
            0x682E6FF3L, 0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
    };

    private static final long SHA256_ADJUSTMENT = 0xC9DC06A7L;
    private static final long SIGMA0_ADJUSTMENT = 0x059C0B80L;
    private static final long SIGMA1_ADJUSTMENT = 0x000B92C0L;

    private static final long[] BLOCK = new long[64];
    static {
        System.arraycopy(ROUND, 0, BLOCK, 0, 14);
        BLOCK[14] = SHA256_ADJUSTMENT;
        System.arraycopy(ROUND, 15, BLOCK, 15, 49);
    }

    private static final long[][] CS64_COEF = {
            {0xE82E91B1L, 0xB32700D0L}, {0x44DE24C6L, 0x88DFF51DL},
            {0x3BEB65BCL, 0x29514038L}, {0xC89C5933L, 0xB328CBDFL},
            {0x8D1EC211L, 0xD405B4D5L}, {0x314EF5D5L, 0x26E55F3CL},
            {0xE92491B1L, 0x607801C6L}, {0xF0A5B9D5L, 0x82B2C1FBL},
            {0x06064CF3L, 0xFA51A3DAL}, {0xDA79FA60L, 0x207CC464L},
            {0xBED20560L, 0x3C3C74F3L}, {0x89F9C9F8L, 0xD2D14B61L},
            {0x35FA38C6L, 0xBB04A2C1L}, {0xCB49E3C6L, 0xC5626167L}
    };

    private static final long CS64_FACTOR = 0x82893933L;
    private static final long CS64_ADJ0 = 0xF2F90B1AL;
    private static final long CS64_ADJ1 = 0x0B1A0000L;

    private SecGec() {
    }

    public static String token() {
        return token(System.currentTimeMillis() / 1000.0);
    }

    /** unixSeconds 使用小数秒，方便对测试向量。 */
    public static String token(double unixSeconds) {
        double ticks = unixSeconds + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 1e7;
        String s = String.format(Locale.US, "%.0f", ticks) + TOKEN;
        byte[] data = s.getBytes(StandardCharsets.US_ASCII);
        String sha = hexUpper(sha256Digest(data));
        String cs = cs64(data, sha256Digest(data));
        return cs + "-" + sha;
    }

    private static long rol16(long v) {
        v &= M;
        return ((v << 16) | (v >>> 16)) & M;
    }

    private static long rotr(long v, int n) {
        v &= M;
        return ((v >>> n) | (v << (32 - n))) & M;
    }

    private static long sigma0(long v) {
        v &= M;
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & M;
    }

    private static long sigma1(long v) {
        v &= M;
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & M;
    }

    private static long[] expandSchedule(long[] words, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            long x = words[i - 2];
            long y = words[i - 15];
            long s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            long s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words[i] = (s1 + words[i - 7] + s0 + words[i - 16]) & M;
        }
        return words;
    }

    private static long[] rounds(long[] state, long[] words, int firstRound) {
        long a = state[0], b = state[1], c = state[2], d = state[3];
        long e = state[4], f = state[5], g = state[6], h = state[7];
        long[] k = (firstRound == 14) ? BLOCK : ROUND;
        for (int i = firstRound; i < 64; i++) {
            long t1 = (h + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25))
                    + ((e & f) ^ ((~e) & g)) + k[i] + words[i]) & M;
            long t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22))
                    + ((a & b) ^ (a & c) ^ (b & c))) & M;
            h = g;
            g = f;
            f = e;
            e = (d + t1) & M;
            d = c;
            c = b;
            b = a;
            a = (t1 + t2) & M;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static long[] sha256Init(byte[] data7) {
        long[] words = new long[64];
        words[14] = ((data7[0] & 0xFFL) << 16) | ((data7[1] & 0xFFL) << 8) | (data7[2] & 0xFFL);
        words[15] = ((data7[3] & 0xFFL) << 24) | ((data7[4] & 0xFFL) << 16)
                | ((data7[5] & 0xFFL) << 8) | (data7[6] & 0xFFL);
        for (int i = 16; i < 30; i++) {
            long value = SHA256_SCHEDULE[i - 16];
            if (i - 2 >= 14) {
                value += (i == 16) ? (sigma1(words[i - 2]) ^ SIGMA1_ADJUSTMENT) : sigma1(words[i - 2]);
            }
            if (i - 7 >= 14) value += words[i - 7];
            if (i - 15 >= 14) {
                value += (i == 29) ? (sigma0(words[i - 15]) ^ SIGMA0_ADJUSTMENT) : sigma0(words[i - 15]);
            }
            words[i] = value & M;
        }
        words[30] = (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14]
                + SHA256_ADJUSTMENT - ROUND[14]) & M;
        expandSchedule(words, 31);
        long[] working = rounds(SHA256_STATE, words, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = (SHA256_INITIAL[i] + working[i]) & M;
        return out;
    }

    private static byte[] sha256Digest(byte[] data) {
        int length = 57 + data.length;
        int zeros = ((55 - length) % 64 + 64) % 64;
        byte[] padded = new byte[data.length - 7 + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        padded[data.length - 7] = (byte) 0x80;
        long bits = (long) length * 8;
        int off = padded.length - 8;
        for (int i = 7; i >= 0; i--) {
            padded[off + i] = (byte) (bits & 0xFF);
            bits >>>= 8;
        }
        long[] state = sha256Init(new byte[]{data[0], data[1], data[2], data[3], data[4], data[5], data[6]});
        for (int blockStart = 0; blockStart < padded.length; blockStart += 64) {
            long[] w = new long[64];
            for (int i = 0; i < 16; i++) {
                int p = blockStart + i * 4;
                w[i] = ((padded[p] & 0xFFL) << 24) | ((padded[p + 1] & 0xFFL) << 16)
                        | ((padded[p + 2] & 0xFFL) << 8) | (padded[p + 3] & 0xFFL);
            }
            expandSchedule(w, 16);
            long[] working = rounds(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] = (state[i] + working[i]) & M;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) ((state[i] >>> 24) & 0xFF);
            out[i * 4 + 1] = (byte) ((state[i] >>> 16) & 0xFF);
            out[i * 4 + 2] = (byte) ((state[i] >>> 8) & 0xFF);
            out[i * 4 + 3] = (byte) (state[i] & 0xFF);
        }
        return out;
    }

    private static String cs64(byte[] data, byte[] digest) {
        long keyA = ((digest[0] & 0xFFL) | ((digest[1] & 0xFFL) << 8)
                | ((digest[2] & 0xFFL) << 16) | ((digest[3] & 0xFFL) << 24)) | 1;
        long keyB = ((digest[4] & 0xFFL) | ((digest[5] & 0xFFL) << 8)
                | ((digest[6] & 0xFFL) << 16) | ((digest[7] & 0xFFL) << 24)) | 1;
        long f0 = (keyA * CS64_FACTOR) & M;
        long f1 = (keyB * CS64_FACTOR) & M;

        byte[] withZero = new byte[data.length + 1];
        withZero[0] = 0;
        System.arraycopy(data, 0, withZero, 1, data.length);

        int nWords = (withZero.length + 3) / 4;
        long[] words = new long[nWords + (nWords % 2)];
        for (int i = 0; i < nWords; i++) {
            long v = 0;
            for (int b = 3; b >= 0; b--) {
                int p = i * 4 + b;
                v = (v << 8) | (p < withZero.length ? (withZero[p] & 0xFFL) : 0);
            }
            words[i] = v;
        }

        int termsLen = 14 + words.length;
        long[] terms = new long[termsLen];
        long[] offsets = new long[termsLen];
        for (int i = 0; i < 14; i++) {
            terms[i] = (CS64_COEF[i][0] * ((i % 2 == 0) ? f0 : f1)) & M;
            offsets[i] = CS64_COEF[i][1];
        }
        for (int i = 0; i < words.length; i++) {
            long word = words[i];
            long term = word * ((i % 2 == 0) ? keyA : keyB);
            long offset = rol16(word) * ((i % 2 == 0) ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJ0 * f0;
                offset += CS64_ADJ1;
            }
            terms[14 + i] = term & M;
            offsets[14 + i] = offset & M;
        }

        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < termsLen; i += 2) {
            long x = terms[i], y = terms[i + 1];
            long t = rol16((offsets[i] + x) & M);
            long q = (t * 0x76F50F34L) & M;
            swapAcc = (swapAcc + ((rol16(q) * 0xC3EFEA97L + q) & M)) & M;

            t = rol16((offsets[i + 1] + y) & M);
            q = (t * 0x1881CE3CL) & M;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & M;
            swapAcc = (swapAcc + swapLast) & M;

            state = (state * keyA + x) & M;
            state = (rol16(state) * 0xCF98B111L) & M;
            state = (rol16(state) * 0x87085B9FL) & M;
            state = (rol16(state) * 0x12CEB96DL) & M;
            state = (rol16(state) * 0x257E1D83L) & M;
            acc = (acc + state) & M;

            t = (state * keyB + y) & M;
            t = (rol16(t) * 0xA27416F5L) & M;
            t = (rol16(t) * 0xD38396FFL) & M;
            state = (rol16((rol16(t) * 0x7C932B89L) & M) * 0xBFA49F69L) & M;
            acc = (acc + state) & M;
        }

        long a = swapLast ^ state;
        long b = swapAcc ^ acc;
        return hexUpper(new byte[]{
                (byte) (a & 0xFF), (byte) ((a >>> 8) & 0xFF), (byte) ((a >>> 16) & 0xFF), (byte) ((a >>> 24) & 0xFF),
                (byte) (b & 0xFF), (byte) ((b >>> 8) & 0xFF), (byte) ((b >>> 16) & 0xFF), (byte) ((b >>> 24) & 0xFF)
        });
    }

    private static String hexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }
}
