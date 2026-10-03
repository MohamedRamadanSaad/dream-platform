package com.saadat.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.payments.provider.KashierSignature;
import org.junit.jupiter.api.Test;

/** Vector from developers.kashier.io/docs/webhooks ("How the signature is computed, step by step"), key 11111. */
class KashierSignatureTest {

    private static final String DATA = """
            {"amount":1,"channel":"online | e-commerce","currency":"EGP",
             "kashierOrderId":"9ad06b17-755b-4e21-9774-aff3e2726ac9","merchantOrderId":"1653481557813",
             "method":"card","orderReference":"TEST-ORD-38855","status":"SUCCESS","transactionId":"TX-249893963",
             "transactionResponseCode":"00","card":{"cardInfo":{"maskedCard":"511111******1118"}},
             "signatureKeys":["transactionResponseCode","amount","channel","currency","kashierOrderId",
                              "merchantOrderId","method","orderReference","status","transactionId"]}
            """;
    private static final String EXPECTED_PAYLOAD = "amount=1&channel=online%20%7C%20e-commerce&currency=EGP"
            + "&kashierOrderId=9ad06b17-755b-4e21-9774-aff3e2726ac9&merchantOrderId=1653481557813&method=card"
            + "&orderReference=TEST-ORD-38855&status=SUCCESS&transactionId=TX-249893963&transactionResponseCode=00";
    private static final String EXPECTED_SIGNATURE =
            "9610477b2255b2a8ef84fd89adfaa5f1305ff9c20324205851890f1ea03109f4";

    @Test
    void matchesTheDocumentedVector() throws Exception {
        JsonNode data = new ObjectMapper().readTree(DATA);
        assertThat(KashierSignature.payload(data)).isEqualTo(EXPECTED_PAYLOAD);
        assertThat(KashierSignature.sign(EXPECTED_PAYLOAD, "11111")).isEqualTo(EXPECTED_SIGNATURE);
        assertThat(KashierSignature.matches(data, "11111", EXPECTED_SIGNATURE.toUpperCase())).isTrue();
    }

    @Test
    void rejectsWrongKeyMissingHeaderAndTamperedData() throws Exception {
        JsonNode data = new ObjectMapper().readTree(DATA);
        assertThat(KashierSignature.matches(data, "22222", EXPECTED_SIGNATURE)).isFalse();
        assertThat(KashierSignature.matches(data, "11111", null)).isFalse();
        JsonNode tampered = new ObjectMapper().readTree(DATA.replace("\"amount\":1", "\"amount\":100"));
        assertThat(KashierSignature.matches(tampered, "11111", EXPECTED_SIGNATURE)).isFalse();
    }
}
