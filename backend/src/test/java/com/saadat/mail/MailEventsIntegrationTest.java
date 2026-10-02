package com.saadat.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.domain.Testimonial;
import com.saadat.dreams.repo.TestimonialRepository;
import com.saadat.dreams.service.DreamService;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Every new e-mail event writes an email_log row with its template; a switched-off event writes nothing. */
class MailEventsIntegrationTest extends IntegrationTestBase {

    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    private static final String DREAM_TEXT = "رأيت أنني أزرع شجرة صغيرة في حديقة البيت ثم كبرت سريعاً وأثمرت.";
    private static final String ONBOARDING =
            "{\"name\":\"Mona\",\"gender\":\"FEMALE\",\"birthDate\":\"1991-07-02\",\"acceptedTerms\":true}";

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    SettingsService settingsService;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Autowired
    TestimonialRepository testimonialRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void onboardingSendsWelcomeAndNewUser() throws Exception {
        createUser("events-interp", Role.INTERPRETER);
        User user = notOnboarded("events-onboard");

        onboard(user);

        awaitLogged(MailTemplates.WELCOME, MailTemplates.WELCOME + ":" + user.getId());
        awaitLogged(MailTemplates.NEW_USER, MailTemplates.NEW_USER + ":" + user.getId());
    }

    @Test
    void failedPaymentSendsPaymentFailed() throws Exception {
        User user = createUser("events-failed", Role.USER);
        MvcResult checkout = mvc.perform(post(ApiPaths.Checkout.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packageId\":\"" + PACKAGE_ONE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String orderId = objectMapper.readTree(checkout.getResponse().getContentAsString()).get("orderId").asText();
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "false"))
                .andExpect(status().isOk());

        awaitLogged(MailTemplates.PAYMENT_FAILED, MailTemplates.PAYMENT_FAILED + ":" + orderId);
    }

    @Test
    void cancellationSendsDreamCancelled() throws Exception {
        User interpreter = createUser("events-interp", Role.INTERPRETER);
        User user = createUser("events-cancel", Role.USER);
        UUID dream = submitted(user);

        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + dream + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isNoContent());

        awaitLogged(MailTemplates.DREAM_CANCELLED, MailTemplates.DREAM_CANCELLED + ":" + dream);
    }

    @Test
    void manualCreditsSendCreditsAdjusted() throws Exception {
        User interpreter = createUser("events-interp", Role.INTERPRETER);
        User user = createUser("events-credits", Role.USER);

        mvc.perform(post(ApiPaths.Admin.USERS + "/" + user.getId() + "/credits")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":2,\"reason\":\"gift\"}"))
                .andExpect(status().isNoContent());

        awaitTrue("credits-adjusted e-mail", () -> logsTo(user.getEmail(), MailTemplates.CREDITS_ADJUSTED) == 1);
    }

    @Test
    void testimonialSendsReceivedThenApproved() throws Exception {
        User interpreter = createUser("events-interp", Role.INTERPRETER);
        User user = createUser("events-testimonial", Role.USER);
        UUID dream = submitted(user);
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + dream + "/interpretation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"رؤيا طيبة والله أعلم.\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + dream + "/testimonial")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"جزاكم الله خيراً\"}"))
                .andExpect(status().isCreated());
        Testimonial testimonial = testimonialRepository.findByDreamId(dream).orElseThrow();

        awaitLogged(MailTemplates.TESTIMONIAL_RECEIVED, MailTemplates.TESTIMONIAL_RECEIVED + ":" + testimonial.getId());

        mvc.perform(patch(ApiPaths.Admin.TESTIMONIALS + "/" + testimonial.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true}"))
                .andExpect(status().isNoContent());

        awaitLogged(MailTemplates.TESTIMONIAL_APPROVED, MailTemplates.TESTIMONIAL_APPROVED + ":" + testimonial.getId());
    }

    @Test
    void accountDeletionMailsTheOriginalAddress() throws Exception {
        User user = createUser("events-delete", Role.USER);
        String email = user.getEmail();

        mvc.perform(delete(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        awaitLogged(MailTemplates.ACCOUNT_DELETED, MailTemplates.ACCOUNT_DELETED + ":" + user.getId());
        assertThat(logsTo(email, MailTemplates.ACCOUNT_DELETED)).isEqualTo(1);
    }

    @Test
    void aSwitchedOffEventWritesNothing() throws Exception {
        createUser("events-interp", Role.INTERPRETER);
        settingsService.put(SettingKeys.MAIL_EVENT_WELCOME, "false", null);
        try {
            User user = notOnboarded("events-off");
            onboard(user);
            // the new-user e-mail (still on) proves the after-commit work ran
            awaitLogged(MailTemplates.NEW_USER, MailTemplates.NEW_USER + ":" + user.getId());
            Thread.sleep(500);
            assertThat(emailLogRepository.existsByTemplateAndRef(MailTemplates.WELCOME,
                    MailTemplates.WELCOME + ":" + user.getId())).isFalse();
        } finally {
            settingsService.put(SettingKeys.MAIL_EVENT_WELCOME, "true", null);
        }
    }

    // ------------------------------------------------------------------ helpers

    private User notOnboarded(String prefix) {
        User user = createUser(prefix, Role.USER);
        user.setOnboarded(false);
        return userRepository.save(user);
    }

    private void onboard(User user) throws Exception {
        mvc.perform(post(ApiPaths.Auth.ONBOARDING)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONBOARDING))
                .andExpect(status().isOk());
    }

    private UUID submitted(User user) throws Exception {
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", DREAM_TEXT, "gender", "FEMALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
        dreamService.submit(user.getId(), List.of(id), null);
        return id;
    }

    private void awaitLogged(String template, String ref) {
        awaitTrue(template + " e-mail (" + ref + ")", () -> emailLogRepository.existsByTemplateAndRef(template, ref));
    }

    private long logsTo(String email, String template) {
        List<EmailLog> rows = emailLogRepository.findByToEmailOrderByCreatedAtDesc(email);
        return rows.stream().filter(r -> template.equals(r.getTemplate())).count();
    }
}
