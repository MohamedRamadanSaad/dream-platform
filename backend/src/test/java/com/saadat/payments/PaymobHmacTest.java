package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saadat.payments.provider.PaymobHmac;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Paymob transaction-callback HMAC with a fixture payload. The expected digest is computed here
 * independently: HMAC-SHA512(secret, concatenation of the values below in this order, no separators).
 */
class PaymobHmacTest {

    private static final String SECRET = "test-hmac-secret";

    /** Trimmed "transaction processed" callback (TRANSACTION type) as Paymob sends it. */
    private static final String FIXTURE = """
            {
              "type": "TRANSACTION",
              "obj": {
                "id": 192036465,
                "pending": false,
                "amount_cents": 19900,
                "success": true,
                "is_auth": false,
                "is_capture": false,
                "is_standalone_payment": true,
                "is_voided": false,
                "is_refunded": false,
                "is_3d_secure": true,
                "integration_id": 123,
                "has_parent_transaction": false,
                "order": {
                  "id": 217503754,
                  "merchant_order_id": "7f1f5a0e-3c1d-4d59-9a57-2d3b0f0c9a11",
                  "amount_cents": 19900
                },
                "created_at": "2026-09-27T12:33:10.123456",
                "currency": "EGP",
                "source_data": {
                  "pan": "2346",
                  "type": "card",
                  "sub_type": "MasterCard"
                },
                "error_occured": false,
                "owner": 302852
              }
            }
            """;

    /**
     * amount_cents | created_at | currency | error_occured | has_parent_transaction | id | integration_id |
     * is_3d_secure | is_auth | is_capture | is_refunded | is_standalone_payment | is_voided | order.id | owner |
     * pending | source_data.pan | source_data.sub_type | source_data.type | success
     */
    private static final String EXPECTED_CONCATENATION = "19900"
            + "2026-09-27T12:33:10.123456"
            + "EGP"
            + "false"
            + "false"
            + "192036465"
            + "123"
            + "true"
            + "false"
            + "false"
            + "false"
            + "true"
            + "false"
            + "217503754"
            + "302852"
            + "false"
            + "2346"
            + "MasterCard"
            + "card"
            + "true";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void concatenatesFieldsInSpecOrder() throws Exception {
        assertThat(PaymobHmac.concatenate(obj())).isEqualTo(EXPECTED_CONCATENATION);
    }

    @Test
    void computesHmacSha512Hex() throws Exception {
        String expected = hmacSha512(EXPECTED_CONCATENATION, SECRET);
        assertThat(PaymobHmac.compute(obj(), SECRET)).isEqualTo(expected).hasSize(128);
    }

    @Test
    void matchesAcceptsCorrectDigestCaseInsensitivelyAndRejectsTampering() throws Exception {
        String digest = hmacSha512(EXPECTED_CONCATENATION, SECRET);
        assertThat(PaymobHmac.matches(obj(), SECRET, digest)).isTrue();
        assertThat(PaymobHmac.matches(obj(), SECRET, digest.toUpperCase())).isTrue();
        assertThat(PaymobHmac.matches(obj(), "other-secret", digest)).isFalse();
        assertThat(PaymobHmac.matches(obj(), SECRET, null)).isFalse();
        assertThat(PaymobHmac.matches(obj(), SECRET, "")).isFalse();

        ObjectNode tampered = (ObjectNode) obj();
        tampered.put("amount_cents", 100);
        assertThat(PaymobHmac.matches(tampered, SECRET, digest)).isFalse();
    }

    @Test
    void missingFieldsAreEmptyStrings() throws Exception {
        JsonNode partial = objectMapper.readTree("{\"amount_cents\":100,\"success\":true}");
        assertThat(PaymobHmac.concatenate(partial)).isEqualTo("100true");
    }

    private JsonNode obj() throws Exception {
        return objectMapper.readTree(FIXTURE).get("obj");
    }

    private static String hmacSha512(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
