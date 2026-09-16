package com.monitor.sdk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 安全相关的小工具。 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("value to hash must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(b & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }
}
