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
    void readsTheSenderNameAndTheReceivedTime() throws Exception {
        InboundMailParser.InboundMessage a = InboundMailParser.parse(mapper.readTree(
                "{\"data\":{\"from\":{\"name\":\"Visitor\",\"address\":\"a@example.com\"},"
                        + "\"date\":\"2026-10-01T08:00:00Z\"}}"));
        assertThat(a.senderName()).isEqualTo("Visitor");
        assertThat(a.receivedAt()).isEqualTo(java.time.Instant.parse("2026-10-01T08:00:00Z"));

        InboundMailParser.InboundMessage b = InboundMailParser.parse(mapper.readTree(
                "{\"from\":\"\\\"Sara Ali\\\" <s@example.com>\",\"date\":\"Wed, 1 Oct 2025 10:15:00 +0300 (AST)\"}"));
        assertThat(b.senderName()).isEqualTo("Sara Ali");
        assertThat(b.receivedAt()).isEqualTo(java.time.Instant.parse("2025-10-01T07:15:00Z"));

        InboundMailParser.InboundMessage c = InboundMailParser.parse(mapper.readTree(
                "{\"from\":[{\"email\":\"c@example.com\"}],\"timestamp\":1759302900}"));
        assertThat(c.senderName()).isNull();
        assertThat(c.receivedAt()).isEqualTo(java.time.Instant.ofEpochSecond(1759302900L));

        InboundMailParser.InboundMessage d = InboundMailParser.parse(mapper.readTree(
                "{\"from\":\"d@example.com\",\"date\":\"not a date\"}"));
        assertThat(d.senderName()).isNull();
        assertThat(d.receivedAt()).isNull();
    }

    @Test
    void detectsAutomatedSenders() {
        assertThat(SupportAutoReplyService.isAutomatedSender("noreply@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("No-Reply@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("bounces+123@example.com")).isTrue();
        assertThat(SupportAutoReplyService.isAutomatedSender("ahmed@example.com")).isFalse();
    }
}
