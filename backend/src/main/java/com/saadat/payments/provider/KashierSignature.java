package com.saadat.payments.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Kashier webhook signature (developers.kashier.io/docs/webhooks, "Verify the signature"): take the keys listed in
 * {@code data.signatureKeys}, sort them, build {@code key=urlEncodedValue&…} (only values are encoded, RFC 3986),
 * HMAC-SHA256 it with the Payment API Key, hex, compare with header {@code x-kashier-signature}.
 */
public final class KashierSignature {

    public static final String HEADER = "x-kashier-signature";

    private static final String ALGORITHM = "HmacSHA256";

    private KashierSignature() {
    }

    /** The string that is signed, from the webhook's {@code data} object. */
    public static String payload(JsonNode data) {
        List<String> keys = new ArrayList<>();
        data.path("signatureKeys").forEach(k -> keys.add(k.asText()));
        keys.sort(null);
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(key).append('=').append(encode(value(data.get(key))));
        }
        return sb.toString();
    }

    public static String sign(String payload, String apiKey) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time, case-insensitive comparison. False when anything is missing. */
    public static boolean matches(JsonNode data, String apiKey, String received) {
        if (data == null || !data.path("signatureKeys").isArray() || apiKey == null || apiKey.isBlank()
                || received == null || received.isBlank()) {
            return false;
        }
        byte[] expected = sign(payload(data), apiKey.trim()).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }

    /** encodeURIComponent-style: spaces as %20, and ! ' ( ) * encoded as query-string's strict mode does. */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
