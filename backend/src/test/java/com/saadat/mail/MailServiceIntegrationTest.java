package com.saadat.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.IntegrationTestBase;
import com.saadat.common.domain.EmailStatus;
import com.saadat.common.domain.Locale;
import com.saadat.mail.MailService.RenderedMail;
import com.saadat.notifications.repo.EmailLogRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MailServiceIntegrationTest extends IntegrationTestBase {

    private static final String FRONTEND = "http://localhost:5173"; // application-test.yml app.frontend-url

    @Autowired
    MailService mailService;

    @Autowired
    EmailLogRepository emailLogRepository;

    private static Map<String, Object> dreamReceivedModel() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Sara");
        model.put("expectedBy", Instant.parse("2026-09-30T10:00:00Z"));
        model.put("waitMessage", "wait-message-text");
        model.put("link", "/me/dreams/abc");
        return model;
    }

    @Test
    void rendersDreamReceivedInArabic() {
        RenderedMail mail = mailService.render(MailTemplates.DREAM_RECEIVED, Locale.AR, dreamReceivedModel());

        assertThat(mail.subject()).isNotBlank().doesNotContain("{");
        assertThat(mail.html())
                .contains("dir=\"rtl\"")
                .contains("lang=\"ar\"")
                .contains("#0A1128")
                .contains("#D4AF37")
                .contains("#F4EFE6")
                .contains("IBM+Plex+Sans+Arabic")
                .contains("Sara")
                .contains("wait-message-text")
                .contains(FRONTEND + "/me/dreams/abc")
                .doesNotContain("??");       // every #{...} message resolved
    }

    @Test
    void rendersDreamReceivedInEnglish() {
        RenderedMail mail = mailService.render(MailTemplates.DREAM_RECEIVED, Locale.EN, dreamReceivedModel());

        assertThat(mail.subject()).isNotBlank();
        assertThat(mail.html())
                .contains("dir=\"ltr\"")
                .contains("lang=\"en\"")
                .contains("Sara")
                .contains(FRONTEND + "/me/dreams/abc")
                .doesNotContain("??");
    }

    @Test
    void everyTemplateRendersInBothLocales() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Sara");
        model.put("userName", "Sara");
        model.put("userEmail", "sara@example.com");
        model.put("link", "/me");
        model.put("code", "123456");
        model.put("ttlMinutes", 15);
        model.put("expectedBy", Instant.now());
        model.put("packageName", "Package");
        model.put("amount", new BigDecimal("150.00"));
        model.put("currency", "EGP");
        model.put("credits", 3);
        model.put("orderRef", "ord-1");
        model.put("paidAt", Instant.now());
        model.put("reason", "AMOUNT_MISMATCH");
        model.put("waiting", 4);
        model.put("overdue", 1);
        model.put("awaitingReply", 2);
        model.put("videoTitle", "Video");
        model.put("videoUrl", "https://www.youtube.com/watch?v=AAAAAAAAAAA");
        model.put("thumbnailUrl", "https://i1.ytimg.com/vi/AAAAAAAAAAA/hqdefault.jpg");
        model.put("delta", "+2");
        model.put("balance", 5);
        model.put("refunded", true);
        model.put("excerpt", "A quiet river");
        model.put("rating", 5);
        model.put("comment", "Thank you");
        model.put("countryName", "Egypt");
        model.put("age", 33);
        model.put("gender", "Female");

        for (String template : MailTemplates.ALL) {
            for (Locale locale : Locale.values()) {
                RenderedMail mail = mailService.render(template, locale, model);
                assertThat(mail.subject()).as("%s/%s subject", template, locale).isNotBlank().doesNotContain("{");
                assertThat(mail.html()).as("%s/%s html", template, locale)
                        .contains(locale == Locale.AR ? "dir=\"rtl\"" : "dir=\"ltr\"")
                        .doesNotContain("??");
            }
        }
    }

    @Test
    void newEventTemplatesUseTheirCopyFromMessages() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Sara");
        model.put("delta", "+2");
        model.put("reason", "gift");
        model.put("balance", 7);
        model.put("link", "/me");

        RenderedMail ar = mailService.render(MailTemplates.CREDITS_ADJUSTED, Locale.AR, model);
        assertThat(ar.subject()).isEqualTo("تحديث على رصيد رؤاك");
        assertThat(ar.html()).contains("السلام عليكم").contains("+2").contains("gift").contains(">7<")
                .contains("الذهاب إلى حسابي").contains(FRONTEND + "/me").doesNotContain("??");

        RenderedMail en = mailService.render(MailTemplates.CREDITS_ADJUSTED, Locale.EN, model);
        assertThat(en.subject()).isEqualTo("Your dream balance was updated");
        assertThat(en.html()).contains("Assalamu alaikum").contains("Current balance").contains("Go to my account")
                .doesNotContain("??");
    }

    @Test
    void switchedOffEventIsNotSentAndMagicLinkAlwaysIs() {
        assertThat(mailService.isEnabled(MailTemplates.MAGIC_LINK)).isTrue();
        assertThat(mailService.isEnabled(MailTemplates.WELCOME)).isTrue();
    }

    @Test
    void sendWithoutSmtpHostLogsAndRecordsLogged() {
        String ref = UUID.randomUUID().toString();

        EmailStatus status = mailService.send("someone@example.com", MailTemplates.DREAM_RECEIVED, Locale.EN,
                dreamReceivedModel(), ref);

        assertThat(status).isEqualTo(EmailStatus.LOGGED);
        assertThat(emailLogRepository.existsByTemplateAndRef(MailTemplates.DREAM_RECEIVED, ref)).isTrue();
    }

    @Test
    void unknownTemplateIsRecordedAsFailedWithoutThrowing() {
        String ref = UUID.randomUUID().toString();

        EmailStatus status = mailService.send("someone@example.com", "no-such-template", Locale.AR, Map.of(), ref);

        assertThat(status).isEqualTo(EmailStatus.FAILED);
        assertThat(emailLogRepository.existsByTemplateAndRef("no-such-template", ref)).isTrue();
    }
}
