package com.saadat.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.EmailStatus;
import com.saadat.config.props.AppProperties;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** POST /webhooks/mail/inbound — secret check, auto-reply sent once (email_log LOGGED: no SMTP in tests), guards. */
class SupportAutoReplyWebhookIntegrationTest extends IntegrationTestBase {

    private static final String SECRET = "test-mail-webhook-secret"; // application-test.yml app.mail.webhook-secret

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    AppProperties properties;

    @Test
    void wrongOrMissingSecretIs401() throws Exception {
        String payload = payloadFrom(uniqueSender());
        mvc.perform(post(ApiPaths.Webhooks.MAIL_INBOUND).header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.Webhooks.MAIL_INBOUND)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankSecretDisablesTheEndpoint() throws Exception {
        String before = properties.getMail().getWebhookSecret();
        properties.getMail().setWebhookSecret("");
        try {
            mvc.perform(post(ApiPaths.Webhooks.MAIL_INBOUND).header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET)
                            .contentType(MediaType.APPLICATION_JSON).content(payloadFrom(uniqueSender())))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            properties.getMail().setWebhookSecret(before);
        }
    }

    @Test
    void validCallSendsOnceThenCooldownSkips() throws Exception {
        String sender = uniqueSender();

        inbound(payloadFrom(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.reason").doesNotExist());

        awaitTrue("support auto-reply logged", () -> autoReplies(sender) == 1);
        List<EmailLog> rows = emailLogRepository.findByToEmailOrderByCreatedAtDesc(sender);
        assertThat(rows).filteredOn(r -> MailTemplates.SUPPORT_AUTO_REPLY.equals(r.getTemplate()))
                .singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(EmailStatus.LOGGED));

        inbound(payloadFrom(sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.reason").value("cooldown"));
        Thread.sleep(300);
        assertThat(autoReplies(sender)).isEqualTo(1);
    }

    @Test
    void otherPayloadShapesAreUnderstood() throws Exception {
        String a = uniqueSender();
        inbound("{\"from\":\"Visitor <" + a + ">\",\"subject\":\"Hi\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        String b = uniqueSender();
        inbound("{\"message\":{\"from\":[{\"name\":\"V\",\"email\":\"" + b + "\"}]}}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        awaitTrue("both auto-replies logged", () -> autoReplies(a) == 1 && autoReplies(b) == 1);
    }

    @Test
    void automatedSendersAndAutomaticMessagesAreSkipped() throws Exception {
        String noreply = "no-reply-" + UUID.randomUUID() + "@example.com";
        inbound(payloadFrom(noreply))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.reason").value("automated-sender"));

        inbound(payloadFrom("mailer-daemon@example.com"))
                .andExpect(jsonPath("$.reason").value("automated-sender"));

        inbound(payloadFrom("someone@saadatu-aldarein.com"))
                .andExpect(jsonPath("$.reason").value("own-address"));

        String autoSubmitted = uniqueSender();
        inbound("{\"data\":{\"from\":{\"address\":\"" + autoSubmitted + "\"},"
                + "\"headers\":{\"Auto-Submitted\":\"auto-replied\"}}}")
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.reason").value("auto-submitted"));

        String bulk = uniqueSender();
        inbound("{\"from\":\"" + bulk + "\",\"headers\":[{\"name\":\"Precedence\",\"value\":\"bulk\"}]}")
                .andExpect(jsonPath("$.reason").value("bulk"));

        String list = uniqueSender();
        inbound("{\"from\":\"" + list + "\",\"raw_headers\":\"Subject: x\\r\\nList-Id: <news.example.com>\\r\\n\"}")
                .andExpect(jsonPath("$.reason").value("mailing-list"));

        inbound("{\"subject\":\"no sender here\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("no-sender"));

        inbound("not json")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("invalid-payload"));

        Thread.sleep(300);
        assertThat(autoReplies(noreply)).isZero();
        assertThat(autoReplies(autoSubmitted)).isZero();
        assertThat(autoReplies(bulk)).isZero();
        assertThat(autoReplies(list)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions inbound(String payload) throws Exception {
        return mvc.perform(post(ApiPaths.Webhooks.MAIL_INBOUND).header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private static String uniqueSender() {
        return "visitor-" + UUID.randomUUID() + "@example.com";
    }

    private static String payloadFrom(String sender) {
        return "{\"event\":\"message.received\",\"data\":{\"from\":{\"name\":\"Visitor\",\"address\":\"" + sender
                + "\"},\"to\":[{\"address\":\"support@saadatu-aldarein.com\"}],\"subject\":\"Question\","
                + "\"message_id\":\"<" + UUID.randomUUID() + "@example.com>\"}}";
    }

    private long autoReplies(String sender) {
        return emailLogRepository.findByToEmailOrderByCreatedAtDesc(sender).stream()
                .filter(r -> MailTemplates.SUPPORT_AUTO_REPLY.equals(r.getTemplate()))
                .count();
    }
}
