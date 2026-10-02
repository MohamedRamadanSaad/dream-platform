package com.saadat.passkeys.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.IntegrationTestBase;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailService.RenderedMail;
import com.saadat.mail.MailTemplates;
import com.saadat.passkeys.domain.Passkey;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The {@code passkey-added} e-mail: its model (label, time, the passkeys section of the account's own profile page)
 * and its Arabic / English rendering in the shared layout; seeded switch and theme settings.
 */
class PasskeyAddedMailIntegrationTest extends IntegrationTestBase {

    private static final String FRONTEND = "http://localhost:5173"; // application-test.yml app.frontend-url

    @Autowired
    PasskeyService passkeyService;

    @Autowired
    MailService mailService;

    @Autowired
    SettingsService settingsService;

    @Test
    void theModelHasTheLabelTheTimeAndTheRightProfileLink() {
        User user = createUser("passkey-mail-user", Role.USER);
        User interpreter = createUser("passkey-mail-interp", Role.INTERPRETER);
        Passkey passkey = new Passkey();
        passkey.setLabel("Safari · iOS");
        passkey.setCreatedAt(Instant.parse("2026-10-02T09:30:00Z"));

        Map<String, Object> userModel = passkeyService.passkeyAddedModel(user, passkey);
        assertThat(userModel.get("passkey")).isEqualTo("Safari · iOS");
        assertThat(userModel.get("addedAt")).isEqualTo(passkey.getCreatedAt());
        assertThat(userModel.get(MailService.MODEL_LINK)).isEqualTo(FrontendPaths.MY_PASSKEYS)
                .isEqualTo("/me/profile#passkeys");
        Map<String, Object> interpreterModel = passkeyService.passkeyAddedModel(interpreter, passkey);
        assertThat(interpreterModel.get(MailService.MODEL_LINK)).isEqualTo(FrontendPaths.ADMIN_PASSKEYS)
                .isEqualTo("/admin/profile#passkeys");

        RenderedMail ar = mailService.render(MailTemplates.PASSKEY_ADDED, Locale.AR, userModel);
        assertThat(ar.subject()).isEqualTo("أُضيف مفتاح مرور إلى حسابك");
        assertThat(ar.html())
                .contains("dir=\"rtl\"")
                .contains(FRONTEND + "/me/profile#passkeys")
                .contains("مراجعة مفاتيح المرور")
                .contains("مفتاح المرور")
                .contains("Safari").contains("iOS")
                .doesNotContain("??");

        RenderedMail en = mailService.render(MailTemplates.PASSKEY_ADDED, Locale.EN, interpreterModel);
        assertThat(en.subject()).isEqualTo("A passkey was added to your account");
        assertThat(en.html())
                .contains("dir=\"ltr\"")
                .contains(FRONTEND + "/admin/profile#passkeys")
                .contains("Review my passkeys")
                .contains("fingerprint")
                .contains("Safari").contains("iOS")
                .doesNotContain("??");
    }

    @Test
    void theSwitchAndTheThemeAreSeeded() {
        assertThat(settingsService.getBool(SettingKeys.MAIL_EVENT_PASSKEY_ADDED)).isTrue();
        assertThat(settingsService.exists(SettingKeys.mailTheme(MailTemplates.PASSKEY_ADDED))).isTrue();
        assertThat(settingsService.getInt(SettingKeys.AUTH_PASSKEY_CHALLENGE_TTL_SECONDS)).isEqualTo(300);
        assertThat(mailService.isEnabled(MailTemplates.PASSKEY_ADDED)).isTrue();
    }
}
