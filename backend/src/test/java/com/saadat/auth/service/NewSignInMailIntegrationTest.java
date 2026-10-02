package com.saadat.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.auth.SessionTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MailService;
import com.saadat.mail.MailService.RenderedMail;
import com.saadat.mail.MailTemplates;
import com.saadat.notifications.domain.EmailLog;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * New sign-in alert (docs/SESSIONS_PROFILE_CONTRACT.md §3): sent after a Google or magic-link sign-in when the user
 * signed in before and the browser + system matches none of the user's sign-ins of the last 90 days; never for the
 * first sign-in, a known device or a refresh; nothing when {@code mail.event.new-sign-in} is off.
 */
class NewSignInMailIntegrationTest extends SessionTestBase {

    private static final String FRONTEND = "http://localhost:5173"; // application-test.yml app.frontend-url

    @Autowired
    DeviceService deviceService;

    @Autowired
    EmailLogRepository emailLogRepository;

    @Autowired
    SettingsService settingsService;

    @Autowired
    MailService mailService;

    @Autowired
    CountryRepository countryRepository;

    private boolean mailed(SignIn device) {
        return emailLogRepository.existsByTemplateAndRef(MailTemplates.NEW_SIGN_IN,
                MailTemplates.NEW_SIGN_IN + ":" + device.familyId());
    }

    private List<EmailLog> newSignInMailsTo(String email) {
        return emailLogRepository.findByToEmailOrderByCreatedAtDesc(email).stream()
                .filter(r -> MailTemplates.NEW_SIGN_IN.equals(r.getTemplate()))
                .toList();
    }

    @Test
    void aNewBrowserAndSystemIsMailedAKnownOneIsNot() throws Exception {
        String email = uniqueEmail("new-sign-in");
        SignIn first = signIn(email, CHROME_WINDOWS, "EG", null);           // first sign-in ever
        SignIn sameDevice = signIn(email, CHROME_WINDOWS_NEWER, "EG", null); // same browser + system, newer version
        SignIn refreshed = refresh(sameDevice, CHROME_WINDOWS_NEWER);        // a refresh never mails
        SignIn newDevice = signIn(email, FIREFOX_LINUX, "SA", false);        // new browser + system

        awaitTrue("new-sign-in e-mail", () -> mailed(newDevice));
        Thread.sleep(500);
        assertThat(mailed(first)).isFalse();
        assertThat(mailed(sameDevice)).isFalse();
        assertThat(mailed(refreshed)).isFalse();

        List<EmailLog> mails = newSignInMailsTo(email);
        assertThat(mails).hasSize(1);
        // the account was created without Accept-Language, so it reads Arabic
        assertThat(mails.get(0).getSubject()).isEqualTo("تسجيل دخول جديد إلى حسابك");
        assertThat(mails.get(0).getUserId()).isEqualTo(userRepository.findByEmailIgnoreCase(email).orElseThrow().getId());
    }

    @Test
    void aSignedOutDeviceIsStillAKnownDevice() throws Exception {
        String email = uniqueEmail("new-sign-in-known");
        SignIn laptop = signIn(email, CHROME_WINDOWS, "EG", null);
        mvc.perform(post(ApiPaths.Auth.LOGOUT).cookie(cookie(laptop.refreshToken())))
                .andExpect(status().isNoContent());

        SignIn again = signIn(email, CHROME_WINDOWS, "EG", null);
        SignIn phone = signIn(email, SAFARI_IPHONE, "EG", null); // proves the after-commit e-mails ran

        awaitTrue("new-sign-in e-mail", () -> mailed(phone));
        Thread.sleep(500);
        assertThat(mailed(again)).isFalse();
        assertThat(newSignInMailsTo(email)).hasSize(1);
    }

    @Test
    void nothingIsSentWhenTheEventIsSwitchedOff() throws Exception {
        String email = uniqueEmail("new-sign-in-off");
        signIn(email, CHROME_WINDOWS, "EG", null);
        SignIn newDevice;
        settingsService.put(SettingKeys.MAIL_EVENT_NEW_SIGN_IN, "false", null);
        try {
            newDevice = signIn(email, SAFARI_MAC, "EG", null);
        } finally {
            settingsService.put(SettingKeys.MAIL_EVENT_NEW_SIGN_IN, "true", null);
        }
        // switched on again: the next new device is mailed (and proves the after-commit work of the earlier one ran)
        SignIn another = signIn(email, EDGE_WINDOWS, "EG", null);

        awaitTrue("new-sign-in e-mail", () -> mailed(another));
        Thread.sleep(500);
        assertThat(mailed(newDevice)).isFalse();
        assertThat(newSignInMailsTo(email)).hasSize(1);
    }

    @Test
    void theModelHasDeviceCountryTimeAndTheRightDevicesLink() {
        User user = createUser("new-sign-in-model", Role.USER);
        User interpreter = createUser("new-sign-in-model-interp", Role.INTERPRETER);
        Instant at = Instant.parse("2026-10-02T09:30:00Z");

        Map<String, Object> userModel = deviceService.newSignInModel(user, FIREFOX_LINUX, "SA", at);
        assertThat(userModel.get("link")).isEqualTo(FrontendPaths.MY_DEVICES).isEqualTo("/me/profile#devices");
        assertThat(userModel.get("device")).isEqualTo("Firefox · Linux");
        assertThat(userModel.get("deviceType")).isEqualTo("حاسوب"); // createUser accounts read Arabic
        assertThat(userModel.get("countryName")).isEqualTo(countryRepository.findById("SA").orElseThrow().getNameAr());
        assertThat(userModel.get("signedInAt")).isEqualTo(at);

        Map<String, Object> interpreterModel = deviceService.newSignInModel(interpreter, SAFARI_IPHONE, null, at);
        assertThat(interpreterModel.get("link")).isEqualTo(FrontendPaths.ADMIN_DEVICES)
                .isEqualTo("/admin/profile#devices");
        assertThat(interpreterModel.get("device")).isEqualTo("Safari · iOS");
        assertThat(interpreterModel.get("deviceType")).isEqualTo("هاتف");
        assertThat(interpreterModel.get("countryName")).isEqualTo("");

        RenderedMail ar = mailService.render(MailTemplates.NEW_SIGN_IN, Locale.AR, userModel);
        assertThat(ar.subject()).isEqualTo("تسجيل دخول جديد إلى حسابك");
        assertThat(ar.html())
                .contains("dir=\"rtl\"")
                .contains(FRONTEND + "/me/profile#devices")
                .contains("مراجعة أجهزتي")
                .contains("Firefox").contains("Linux")
                .contains("حاسوب")
                .contains(countryRepository.findById("SA").orElseThrow().getNameAr())
                .doesNotContain("??");

        RenderedMail en = mailService.render(MailTemplates.NEW_SIGN_IN, Locale.EN, interpreterModel);
        assertThat(en.subject()).isEqualTo("New sign-in to your account");
        assertThat(en.html())
                .contains("dir=\"ltr\"")
                .contains(FRONTEND + "/admin/profile#devices")
                .contains("Review my devices")
                .contains("Safari").contains("iOS")
                .doesNotContain("??");
    }

    @Test
    void firstSignInIsNotANewDevice() {
        User user = createUser("new-sign-in-first", Role.USER);
        assertThat(deviceService.isNewDevice(user.getId(), FIREFOX_LINUX, Instant.now())).isFalse();
    }
}
