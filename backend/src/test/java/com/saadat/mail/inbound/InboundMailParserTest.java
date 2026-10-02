package com.saadat.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class InboundMailParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String sender(String json) throws Exception {
        return InboundMailParser.parse(mapper.readTree(json)).sender();
    }

    @Test
    void findsTheSenderInCommonShapes() throws Exception {
        assertThat(sender("{\"from\":\"A@Example.com\"}")).isEqualTo("a@example.com");
        assertThat(sender("{\"from\":\"Ahmed <ahmed@example.com>\"}")).isEqualTo("ahmed@example.com");
        assertThat(sender("{\"from\":{\"address\":\"a@example.com\"}}")).isEqualTo("a@example.com");
        assertThat(sender("{\"from\":{\"name\":\"A\",\"email\":\"a@example.com\"}}")).isEqualTo("a@example.com");
        assertThat(sender("{\"from\":[{\"address\":\"a@example.com\"}]}")).isEqualTo("a@example.com");
        assertThat(sender("{\"data\":{\"from\":{\"address\":\"a@example.com\"}}}")).isEqualTo("a@example.com");
        assertThat(sender("{\"message\":{\"from\":\"a@example.com\"}}")).isEqualTo("a@example.com");
        assertThat(sender("{\"envelope\":{\"from\":\"a@example.com\"}}")).isEqualTo("a@example.com");
        assertThat(sender("{\"envelope_from\":\"a@example.com\"}")).isEqualTo("a@example.com");
        assertThat(sender("{\"sender\":\"a@example.com\"}")).isEqualTo("a@example.com");
        assertThat(sender("{\"subject\":\"hello\"}")).isNull();
    }

    @Test
    void prefersFromOverSender() throws Exception {
        assertThat(sender("{\"sender\":\"support@site.com\",\"from\":\"a@example.com\"}")).isEqualTo("a@example.com");
    }

    @Test
    void readsSubjectMessageIdAndHeaders() throws Exception {
        JsonNode root = mapper.readTree("{\"data\":{\"subject\":\"Hi\",\"message-id\":\"<x@y>\","
                + "\"headers\":{\"Auto-Submitted\":\"auto-replied\"},"
                + "\"header_list\":[{\"name\":\"precedence\",\"value\":\"bulk\"}],"
                + "\"rawHeaders\":\"X: 1\\nList-Id: <l.example.com>\\n\"}}");
        InboundMailParser.InboundMessage m = InboundMailParser.parse(root);
        assertThat(m.subject()).isEqualTo("Hi");
        assertThat(m.messageId()).isEqualTo("<x@y>");
        assertThat(InboundMailParser.headerValues(root, "Auto-Submitted")).containsExactly("auto-replied");
        assertThat(InboundMailParser.headerValues(root, "Precedence")).containsExactly("bulk");
        assertThat(InboundMailParser.headerValues(root, "List-Id")).containsExactly("<l.example.com>");
    }

    @Test
    void detectsAutomatedSenders() {
        assertThat(SupportAutoReplyService.isAutomatedSender("noreply@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("No-Reply@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("bounces+123@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("ahmed@example.com")).isFalse();
    }
}
