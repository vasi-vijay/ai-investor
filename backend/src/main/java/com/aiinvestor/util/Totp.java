package com.aiinvestor.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) — used to complete Groww's TOTP token flow from a secret held in the environment. */
public final class Totp {
    private Totp() {}

    public static String code(String base32Secret, Instant at) {
        try {
            byte[] key = base32(base32Secret);
            long step = at.getEpochSecond() / 30;
            byte[] msg = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0xF;
            int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16) | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
            return String.format("%06d", bin % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("TOTP generation failed", e);
        }
    }

    static byte[] base32(String s) {
        String clean = s.replace(" ", "").replace("=", "").toUpperCase();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = alphabet.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("invalid base32 secret");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xFF); bits -= 8; }
        }
        return out.toByteArray();
    }
}
