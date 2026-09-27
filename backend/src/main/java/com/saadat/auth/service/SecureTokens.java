package com.saadat.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Random tokens and one-way hashes for magic links and refresh tokens. Raw tokens are never stored. */
public final class SecureTokens {

    /** 32 random bytes → 43 url-safe characters. */
    public static final int TOKEN_BYTES = 32;
    /** Magic-link code length (digits). */
    public static final int CODE_DIGITS = 6;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SHA_256 = "SHA-256";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private SecureTokens() {
    }

    /** 32 random bytes, base64url without padding. */
    public static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A zero-padded numeric code of {@link #CODE_DIGITS} digits. */
    public static String randomCode() {
        int bound = (int) Math.pow(10, CODE_DIGITS);
        String digits = Integer.toString(RANDOM.nextInt(bound));
        StringBuilder sb = new StringBuilder(CODE_DIGITS);
        for (int i = digits.length(); i < CODE_DIGITS; i++) {
            sb.append('0');
        }
        return sb.append(digits).toString();
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Lower-case hex HMAC-SHA256 (keyed hash, used for the short numeric code so it cannot be brute-forced offline). */
    public static String hmacSha256(String key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    /** Constant-time comparison of two hex digests. */
    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
