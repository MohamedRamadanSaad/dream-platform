package com.saadat.payments.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Paymob "transaction processed" callback HMAC (spec §5): HMAC-SHA512, hex, over the plain concatenation
 * (no separators) of these {@code obj} fields in this exact order. Booleans are rendered {@code true/false},
 * numbers as-is, missing/null values as the empty string.
 */
public final class PaymobHmac {

    public static final List<String> FIELDS = List.of(
            "amount_cents",
            "created_at",
            "currency",
            "error_occured",
            "has_parent_transaction",
            "id",
            "integration_id",
            "is_3d_secure",
            "is_auth",
            "is_capture",
            "is_refunded",
            "is_standalone_payment",
            "is_voided",
            "order.id",
            "owner",
            "pending",
            "source_data.pan",
            "source_data.sub_type",
            "source_data.type",
            "success");

    private static final String ALGORITHM = "HmacSHA512";

    private PaymobHmac() {
    }

    /** The string that is signed. */
    public static String concatenate(JsonNode obj) {
        StringBuilder sb = new StringBuilder();
        for (String path : FIELDS) {
            sb.append(text(obj, path));
        }
        return sb.toString();
    }

    public static String compute(JsonNode obj, String secret) {
        return sign(concatenate(obj), secret);
    }

    public static String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA512 unavailable", e);
        }
    }

    /** Constant-time, case-insensitive comparison of hex digests. */
    public static boolean matches(JsonNode obj, String secret, String received) {
        if (obj == null || secret == null || secret.isEmpty() || received == null || received.isBlank()) {
            return false;
        }
        byte[] expected = compute(obj, secret).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    static String text(JsonNode obj, String path) {
        JsonNode node = obj;
        for (String part : path.split("\\.")) {
            if (node == null) {
                return "";
            }
            node = node.get(part);
        }
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
