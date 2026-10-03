package com.saadat.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.EmailStatus;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.mail.MailService;
import com.saadat.mail.MailService.RenderedMail;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.inbound.InboundMailParser.InboundMessage;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.support.domain.SupportTicket;
import com.saadat.support.domain.SupportTicketStatus;
import com.saadat.support.repo.SupportTicketRepository;
import com.saadat.support.service.SupportTicketService;
import com.saadat.users.domain.User;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Support tickets: opened by the inbound-mail webhook for messages written by people (auto-reply unaffected),
 * de-duplicated, listed/counted by status, moved to IN_PROGRESS / CLOSED by the interpreter with an e-mail to the
 * sender (email_log LOGGED: no SMTP in tests).
 */
class SupportTicketsIntegrationTest extends IntegrationTestBase {

    private static final String SECRET = "test-mail-webhook-secret"; // application-test.yml app.mail.webhook-secret

    @Autowired
    SupportTicketRepository ticketRepository;

    @Autowired
    SupportTicketService ticketService;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    SettingsService settingsService;

    @Autowired
    MailService mailService;

    @Autowired
    ObjectMapper objectMapper;

    // ================================================================== webhook → ticket

    @Test
    void webhookFromAPersonOpensANewTicketAndStillAutoReplies() throws Exception {
        String sender = uniqueSender();
        String messageId = "<" + UUID.randomUUID() + "@example.com>";

        inbound(payload(sender, "Question about my order", messageId, "\"date\":\"Wed, 1 Oct 2025 10:15:00 +0300\","))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        SupportTicket ticket = ticketRepository.findByMessageId(messageId).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.NEW);
        assertThat(ticket.getFromEmail()).isEqualTo(sender);
        assertThat(ticket.getFromName()).isEqualTo("Visitor Name");
        assertThat(ticket.getSubject()).isEqualTo("Question about my order");
        assertThat(ticket.getReceivedAt()).isEqualTo(Instant.parse("2025-10-01T07:15:00Z"));
        assertThat(ticket.getNumber()).isGreaterThanOrEqualTo(1001L);

        awaitTrue("support auto-reply logged", () -> mails(sender, MailTemplates.SUPPORT_AUTO_REPLY) == 1);
    }

    @Test
    void automatedSendersAndOwnAddressesOpenNoTicket() throws Exception {
        String noreply = "no-reply-" + UUID.randomUUID() + "@example.com";
        inbound(payload(noreply, "Newsletter", "<" + UUID.randomUUID() + "@example.com>", ""))
                .andExpect(jsonPath("$.reason").value("automated-sender"));

        String bulk = uniqueSender();
        inbound("{\"from\":\"" + bulk + "\",\"subject\":\"Offer\",\"headers\":[{\"name\":\"Precedence\",\"value\":\"bulk\"}]}")
                .andExpect(jsonPath("$.reason").value("bulk"));

        String own = "staff-" + UUID.randomUUID() + "@saadatu-aldarein.com";
        inbound(payload(own, "Internal", "<" + UUID.randomUUID() + "@example.com>", ""))
                .andExpect(jsonPath("$.reason").value("own-address"));

        assertThat(ticketsFrom(noreply)).isEmpty();
        assertThat(ticketsFrom(bulk)).isEmpty();
        assertThat(ticketsFrom(own)).isEmpty();
    }

    @Test
    void repeatedDeliveriesOpenOneTicket() throws Exception {
        String sender = uniqueSender();
        String body = payload(sender, "Same message", "<" + UUID.randomUUID() + "@example.com>", "");
        inbound(body).andExpect(jsonPath("$.status").value("ok"));
        inbound(body).andExpect(jsonPath("$.reason").value("cooldown"));
        assertThat(ticketsFrom(sender)).hasSize(1);

        // no Message-ID: same sender + subject within two minutes is the same message
        String other = uniqueSender();
        String noId = "{\"from\":{\"address\":\"" + other + "\"},\"subject\":\"Hello\"}";
        inbound(noId).andExpect(status().isOk());
        inbound(noId).andExpect(status().isOk());
        assertThat(ticketsFrom(other)).hasSize(1);
    }

    @Test
    void ticketIsOpenedEvenWhenTheAutoReplyIsSwitchedOff() throws Exception {
        String sender = uniqueSender();
        settingsService.put(SettingKeys.MAIL_EVENT_SUPPORT_AUTO_REPLY, "false", null);
        try {
            inbound(payload(sender, "Help", "<" + UUID.randomUUID() + "@example.com>", ""))
                    .andExpect(jsonPath("$.status").value("skipped"))
                    .andExpect(jsonPath("$.reason").value("disabled"));
        } finally {
            settingsService.put(SettingKeys.MAIL_EVENT_SUPPORT_AUTO_REPLY, "true", null);
        }
        assertThat(ticketsFrom(sender)).singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(SupportTicketStatus.NEW));
    }

    @Test
    void webhookWithTheTextStoresItAndTheApiReturnsIt() throws Exception {
        User interpreter = createUser("support-body", Role.INTERPRETER);
        String sender = uniqueSender();
        String messageId = "<" + UUID.randomUUID() + "@example.com>";
        String extra = "\"text\":\"السلام عليكم\\r\\nلم يصلني التفسير بعد.\\n\\nOn Fri, 3 Oct 2026, Support"
                + " <support@saadatu-aldarein.com> wrote:\\n> old\",";

        inbound(payload(sender, "Missing interpretation", messageId, extra)).andExpect(status().isOk());

        SupportTicket ticket = ticketRepository.findByMessageId(messageId).orElseThrow();
        String expected = "السلام عليكم\nلم يصلني التفسير بعد.";
        assertThat(ticket.getBody()).isEqualTo(expected);
        assertThat(ticket.getBodyFetchedAt()).isNotNull();

        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKET, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(expected));

        JsonNode page = json(get(ApiPaths.Admin.SUPPORT_TICKETS).param("status", "NEW").param("size", "100"),
                interpreter);
        JsonNode row = null;
        for (JsonNode r : page.get("items")) {
            if (r.get("id").asText().equals(ticket.getId().toString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("body").asText()).isEqualTo(expected);
    }

    @Test
    void webhookWithAnHtmlBodyStoresPlainText() throws Exception {
        String sender = uniqueSender();
        String messageId = "<" + UUID.randomUUID() + "@example.com>";
        inbound(payload(sender, "Html", messageId,
                "\"html\":\"<div>Hello<br>World</div><script>alert(1)</script><p>A &amp; B</p>\","))
                .andExpect(status().isOk());

        assertThat(ticketRepository.findByMessageId(messageId).orElseThrow().getBody())
                .isEqualTo("Hello\nWorld\nA & B");
    }

    @Test
    void ticketWithoutTextHasANullBodyAndFetchBodyWithoutImapReturnsTheDetail() throws Exception {
        User interpreter = createUser("support-fetch", Role.INTERPRETER);
        SupportTicket ticket = open(uniqueSender(), "No text");
        assertThat(ticket.getBody()).isNull();

        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKET, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").isEmpty());

        // no SMTP/IMAP account in tests: nothing is fetched, the detail comes back unchanged (no 500)
        mvc.perform(post(ApiPaths.Admin.SUPPORT_TICKET_FETCH_BODY, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticket.getId().toString()))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.subject").value("No text"))
                .andExpect(jsonPath("$.body").isEmpty());

        mvc.perform(post(ApiPaths.Admin.SUPPORT_TICKET_FETCH_BODY, UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isNotFound());

        User user = createUser("support-fetch-user", Role.USER);
        mvc.perform(post(ApiPaths.Admin.SUPPORT_TICKET_FETCH_BODY, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    // ================================================================== admin API

    @Test
    void listAndCountsByStatus() throws Exception {
        User interpreter = createUser("support-list", Role.INTERPRETER);
        JsonNode before = json(get(ApiPaths.Admin.SUPPORT_TICKET_COUNTS), interpreter);

        SupportTicket a = open(uniqueSender(), "First");
        SupportTicket b = open(uniqueSender(), "Second");
        ticketService.close(interpreter.getId(), b.getId(), "Done");

        JsonNode after = json(get(ApiPaths.Admin.SUPPORT_TICKET_COUNTS), interpreter);
        assertThat(after.get("new").asLong()).isEqualTo(before.get("new").asLong() + 1);
        assertThat(after.get("closed").asLong()).isEqualTo(before.get("closed").asLong() + 1);
        assertThat(after.get("inProgress").asLong()).isEqualTo(before.get("inProgress").asLong());

        JsonNode newPage = json(get(ApiPaths.Admin.SUPPORT_TICKETS).param("status", "NEW").param("size", "100"),
                interpreter);
        assertThat(newPage.get("items").findValuesAsText("id")).contains(a.getId().toString())
                .doesNotContain(b.getId().toString());
        assertThat(newPage.has("total")).isTrue();

        JsonNode closedPage = json(get(ApiPaths.Admin.SUPPORT_TICKETS).param("status", "CLOSED").param("size", "100"),
                interpreter);
        JsonNode closedRow = null;
        for (JsonNode row : closedPage.get("items")) {
            if (row.get("id").asText().equals(b.getId().toString())) {
                closedRow = row;
            }
        }
        assertThat(closedRow).isNotNull();
        assertThat(closedRow.get("lastMessage").asText()).isEqualTo("Done");
        assertThat(closedRow.get("eventsCount").asInt()).isEqualTo(1);
        assertThat(closedRow.get("number").asLong()).isEqualTo(b.getNumber());
    }

    @Test
    void inProgressThenCloseSendsTheInterpreterMessageAndRecordsEvents() throws Exception {
        User interpreter = createUser("support-flow", Role.INTERPRETER);
        String sender = uniqueSender();
        SupportTicket ticket = open(sender, "My dream is missing");
        String message = "Line one\n<b>bold</b> & more";

        mvc.perform(post(ApiPaths.Admin.SUPPORT_TICKET_IN_PROGRESS, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "  " + message + "  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.lastMessage").value(message))
                .andExpect(jsonPath("$.eventsCount").value(1));

        // an in-progress ticket can get another update
        action(ApiPaths.Admin.SUPPORT_TICKET_IN_PROGRESS, ticket.getId(), interpreter, "Still checking")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.eventsCount").value(2));

        action(ApiPaths.Admin.SUPPORT_TICKET_CLOSE, ticket.getId(), interpreter, "All fixed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastMessage").value("All fixed"));

        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKET, ticket.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value((int) ticket.getNumber()))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[0].action").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.events[0].message").value(message))
                .andExpect(jsonPath("$.events[0].actorName").value("support-flow"))
                .andExpect(jsonPath("$.events[0].emailStatus").value("LOGGED"))
                .andExpect(jsonPath("$.events[2].action").value("CLOSED"));

        // e-mails to the sender (sent synchronously, LOGGED without SMTP)
        List<EmailLog> rows = emailLogRepository.findByToEmailOrderByCreatedAtDesc(sender);
        assertThat(rows).filteredOn(r -> MailTemplates.SUPPORT_IN_PROGRESS.equals(r.getTemplate())).hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.getStatus()).isEqualTo(EmailStatus.LOGGED);
                    assertThat(r.getSubject()).contains("#" + ticket.getNumber()).contains("We are working on your request");
                });
        assertThat(rows).filteredOn(r -> MailTemplates.SUPPORT_CLOSED.equals(r.getTemplate())).singleElement()
                .satisfies(r -> assertThat(r.getSubject()).contains("#" + ticket.getNumber()).contains("تم إغلاق تذكرتك"));

        // the rendered body carries the message escaped, line breaks kept, and the ticket number
        RenderedMail mail = mailService.render(MailTemplates.SUPPORT_IN_PROGRESS, Locale.AR,
                SupportTicketService.mailModel(ticket.getNumber(), ticket.getSubject(), message, null));
        assertThat(mail.html())
                .contains("Line one<br")
                .contains("&lt;b&gt;bold&lt;/b&gt; &amp; more")
                .doesNotContain("<b>bold</b>")
                .contains(">" + ticket.getNumber() + "</span>")
                .contains("My dream is missing")
                .contains("نود إعلامك بأننا نعمل الآن على طلبك")
                .contains("We are now working on your request.")
                .doesNotContain("وصلتك هذه الرسالة لأنها تخص حسابك")
                .doesNotContain("??");
        RenderedMail closed = mailService.render(MailTemplates.SUPPORT_CLOSED, Locale.EN,
                SupportTicketService.mailModel(ticket.getNumber(), ticket.getSubject(), "All fixed", null));
        assertThat(closed.html()).contains("Your ticket has been closed.").contains("All fixed")
                .contains("dir=\"ltr\"").doesNotContain("??");

        // closed is final
        action(ApiPaths.Admin.SUPPORT_TICKET_CLOSE, ticket.getId(), interpreter, "Again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(SupportTicketService.CODE_TICKET_CLOSED));
        action(ApiPaths.Admin.SUPPORT_TICKET_IN_PROGRESS, ticket.getId(), interpreter, "Reopen?")
                .andExpect(status().isConflict());
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStatus())
                .isEqualTo(SupportTicketStatus.CLOSED);
    }

    @Test
    void messageIsRequiredAndLimited() throws Exception {
        User interpreter = createUser("support-validate", Role.INTERPRETER);
        SupportTicket ticket = open(uniqueSender(), "Validate");
        action(ApiPaths.Admin.SUPPORT_TICKET_IN_PROGRESS, ticket.getId(), interpreter, "   ")
                .andExpect(status().isBadRequest());
        action(ApiPaths.Admin.SUPPORT_TICKET_CLOSE, ticket.getId(), interpreter, "x".repeat(2001))
                .andExpect(status().isBadRequest());
        action(ApiPaths.Admin.SUPPORT_TICKET_CLOSE, UUID.randomUUID(), interpreter, "Hello")
                .andExpect(status().isNotFound());
        assertThat(ticketRepository.findById(ticket.getId()).orElseThrow().getStatus())
                .isEqualTo(SupportTicketStatus.NEW);
    }

    @Test
    void onlyTheInterpreterCanUseTheSupportRoutes() throws Exception {
        User user = createUser("support-user", Role.USER);
        SupportTicket ticket = open(uniqueSender(), "Private");
        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKETS).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKET_COUNTS).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        action(ApiPaths.Admin.SUPPORT_TICKET_CLOSE, ticket.getId(), user, "Nope")
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.Admin.SUPPORT_TICKETS))
                .andExpect(status().isUnauthorized());
    }

    // ================================================================== helpers

    private SupportTicket open(String sender, String subject) {
        return ticketService.openFromInbound(new InboundMessage(sender, subject,
                "<" + UUID.randomUUID() + "@example.com>", "Visitor", null)).orElseThrow();
    }

    private ResultActions inbound(String payload) throws Exception {
        return mvc.perform(post(ApiPaths.Webhooks.MAIL_INBOUND).header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
    }

    private ResultActions action(String path, UUID id, User actor, String message) throws Exception {
        return mvc.perform(post(path, id)
                .header(HttpHeaders.AUTHORIZATION, bearer(actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", message))));
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          User actor) throws Exception {
        String body = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(actor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body);
    }

    private static String uniqueSender() {
        return "visitor-" + UUID.randomUUID() + "@example.com";
    }

    private static String payload(String sender, String subject, String messageId, String extra) {
        return "{\"event\":\"message.received\",\"data\":{" + extra + "\"from\":{\"name\":\"Visitor Name\",\"address\":\""
                + sender + "\"},\"to\":[{\"address\":\"support@saadatu-aldarein.com\"}],\"subject\":\"" + subject
                + "\",\"message_id\":\"" + messageId + "\"}}";
    }

    private List<SupportTicket> ticketsFrom(String email) {
        return ticketRepository.findAll().stream().filter(t -> email.equalsIgnoreCase(t.getFromEmail())).toList();
    }

    private long mails(String to, String template) {
        return emailLogRepository.findByToEmailOrderByCreatedAtDesc(to).stream()
                .filter(r -> template.equals(r.getTemplate()))
                .count();
    }
}
