package com.saadat.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.mail.MailService.RenderedMail;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** E-mail themes: registry, resolution order, image URLs, admin API (list / set / validate / preview). */
class MailThemesIntegrationTest extends IntegrationTestBase {

    private static final String FRONTEND = "http://localhost:5173"; // application-test.yml app.frontend-url

    @Autowired
    MailThemes mailThemes;

    @Autowired
    MailThemeService mailThemeService;

    @Autowired
    MailService mailService;

    @Autowired
    SettingsService settingsService;

    @Test
    void registryHasTheFourThemesAndEveryTemplateHasASeededThemeSetting() {
        assertThat(mailThemes.all()).extracting(MailTheme::key)
                .containsExactly("crescent-night", "rose-dawn", "sea-breeze", "lavender-night", "desert-dusk", "emerald-night", "winter-sky", "calm-morning");
        assertThat(mailThemes.defaultKey()).isEqualTo("crescent-night");
        assertThat(settingsService.getString(SettingKeys.MAIL_THEME_DEFAULT)).isEqualTo("crescent-night");
        for (String template : MailTemplates.ALL) {
            assertThat(settingsService.exists(SettingKeys.mailTheme(template))).as("theme of %s", template).isTrue();
            assertThat(mailThemeService.resolve(template).key()).as("theme of %s", template).isEqualTo("crescent-night");
        }
    }

    @Test
    void themeResolutionFollowsTemplateThenDefaultThenRegistry() {
        String key = SettingKeys.mailTheme(MailTemplates.WELCOME);
        try {
            settingsService.put(key, "rose-dawn", null);
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME).key()).isEqualTo("rose-dawn");

            settingsService.put(key, "no-such-theme", null);
            settingsService.put(SettingKeys.MAIL_THEME_DEFAULT, "sea-breeze", null);
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME).key()).isEqualTo("sea-breeze");

            settingsService.put(SettingKeys.MAIL_THEME_DEFAULT, "also-unknown", null);
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME).key()).isEqualTo("crescent-night");

            // a preview override wins when it is a known theme
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME, "emerald-night").key())
                    .isEqualTo("emerald-night");
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME, "nope").key()).isEqualTo("crescent-night");
        } finally {
            settingsService.put(key, "", null);
            settingsService.put(SettingKeys.MAIL_THEME_DEFAULT, "crescent-night", null);
        }
    }

    @Test
    void renderedMailUsesTheThemeColoursImagesAndFooterLinks() {
        String key = SettingKeys.mailTheme(MailTemplates.WELCOME);
        try {
            settingsService.put(key, "rose-dawn", null);
            RenderedMail ar = mailService.render(MailTemplates.WELCOME, Locale.AR, Map.of("name", "Sara", "link", "/me"));
            assertThat(ar.html())
                    .contains(FRONTEND + "/email/themes/rose-dawn/header.jpg")
                    .contains(FRONTEND + "/email/themes/rose-dawn/footer.jpg")
                    .contains("#3E3352")             // pageBg
                    .contains("#FBF4F2")             // cardBg
                    .contains("#B76E79")             // accent (button)
                    .contains("v:fill")              // Outlook fallback
                    .contains("IBM+Plex+Sans+Arabic")
                    .contains("family=IBM+Plex+Sans:wght")
                    .contains("لمتابعة أحدث الفيديوهات، تفضّل بزيارة قناتنا على يوتيوب")
                    .contains("يُرجى زيارة موقعنا")
                    .contains("localhost:5173")
                    .doesNotContain("??");
            RenderedMail en = mailService.render(MailTemplates.WELCOME, Locale.EN, Map.of("name", "Sara"));
            assertThat(en.html())
                    .contains("Watch our latest videos on our YouTube channel")
                    .contains("Please visit our website")
                    .doesNotContain("??");
        } finally {
            settingsService.put(key, "", null);
        }
    }

    @Test
    void assetsBaseUrlSettingReplacesTheSiteUrl() {
        try {
            settingsService.put(SettingKeys.MAIL_ASSETS_BASE_URL, "https://cdn.example.com/", null);
            RenderedMail mail = mailService.render(MailTemplates.WELCOME, Locale.AR, Map.of());
            assertThat(mail.html()).contains("https://cdn.example.com/email/themes/crescent-night/header.jpg");
        } finally {
            settingsService.put(SettingKeys.MAIL_ASSETS_BASE_URL, "", null);
        }
    }

    @Test
    void supportAutoReplyIsBilingualWithoutAButton() {
        RenderedMail mail = mailService.render(MailTemplates.SUPPORT_AUTO_REPLY, Locale.AR, Map.of());
        assertThat(mail.subject()).isEqualTo("تم استلام رسالتك · We received your message");
        assertThat(mail.html())
                .contains("السلام عليكم ورحمة الله وبركاته")
                .contains("Thank you for contacting us. We have received your message.")
                .doesNotContain("إذا لم يعمل الزر")      // no call-to-action button
                .doesNotContain("وصلتك هذه الرسالة لأنها تخص حسابك") // no "your account" footer note
                .doesNotContain("??");
    }

    @Test
    void adminListsThemesAndTemplates() throws Exception {
        User interpreter = createUser("themes-admin", Role.INTERPRETER);

        mvc.perform(get(ApiPaths.Admin.MAIL_THEMES).header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].key").value("crescent-night"))
                .andExpect(jsonPath("$[0].headerImageUrl").value(FRONTEND + "/email/themes/crescent-night/header.jpg"))
                .andExpect(jsonPath("$[0].usedByDefault").value(true));

        mvc.perform(get(ApiPaths.Admin.MAIL_TEMPLATES).header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(MailTemplates.ALL.size()))
                .andExpect(jsonPath("$[?(@.template == 'support-auto-reply')].enabled").value(true))
                .andExpect(jsonPath("$[?(@.template == 'magic-link')].switchable").value(false))
                .andExpect(jsonPath("$[?(@.template == 'welcome')].theme").value("crescent-night"))
                .andExpect(jsonPath("$[?(@.template == 'welcome')].inherited").value(true));
    }

    @Test
    void userCannotUseTheMailAdminApi() throws Exception {
        User user = createUser("themes-user", Role.USER);
        mvc.perform(get(ApiPaths.Admin.MAIL_THEMES).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void putTemplateThemeValidatesAndSaves() throws Exception {
        User interpreter = createUser("themes-put", Role.INTERPRETER);
        String auth = bearer(interpreter);
        try {
            mvc.perform(put(ApiPaths.Admin.MAIL_TEMPLATES + "/no-such-template/theme").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"sea-breeze\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("UNKNOWN_TEMPLATE"));

            mvc.perform(put(ApiPaths.Admin.MAIL_TEMPLATES + "/welcome/theme").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"no-such-theme\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_THEME"));

            mvc.perform(put(ApiPaths.Admin.MAIL_TEMPLATES + "/welcome/theme").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"sea-breeze\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.template").value("welcome"))
                    .andExpect(jsonPath("$.theme").value("sea-breeze"))
                    .andExpect(jsonPath("$.inherited").value(false));
            assertThat(settingsService.getString(SettingKeys.mailTheme(MailTemplates.WELCOME))).isEqualTo("sea-breeze");

            // blank = follow the default again
            mvc.perform(put(ApiPaths.Admin.MAIL_TEMPLATES + "/welcome/theme").header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.theme").value("crescent-night"))
                    .andExpect(jsonPath("$.inherited").value(true));

            mvc.perform(put(ApiPaths.Admin.MAIL_THEME_DEFAULT).header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"nope\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNKNOWN_THEME"));

            mvc.perform(put(ApiPaths.Admin.MAIL_THEME_DEFAULT).header(HttpHeaders.AUTHORIZATION, auth)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"emerald-night\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.theme").value("emerald-night"));
            assertThat(mailThemeService.resolve(MailTemplates.WELCOME).key()).isEqualTo("emerald-night");
        } finally {
            settingsService.put(SettingKeys.mailTheme(MailTemplates.WELCOME), "", null);
            settingsService.put(SettingKeys.MAIL_THEME_DEFAULT, "crescent-night", null);
        }
    }

    @Test
    void previewReturnsHtmlInTheRequestedThemeAndLocale() throws Exception {
        User interpreter = createUser("themes-preview", Role.INTERPRETER);

        MvcResult en = mvc.perform(get(ApiPaths.Admin.MAIL_PREVIEW).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .accept(MediaType.TEXT_HTML)
                        .param("template", MailTemplates.DREAM_RECEIVED)
                        .param("theme", "sea-breeze")
                        .param("locale", "en"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn();
        String enHtml = en.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(enHtml)
                .contains(FRONTEND + "/email/themes/sea-breeze/header.jpg")
                .contains("#0A2A33")
                .contains("Watch our latest videos on our YouTube channel")
                .contains("Ahmed")
                .contains("dir=\"ltr\"")
                .doesNotContain("??");

        MvcResult ar = mvc.perform(get(ApiPaths.Admin.MAIL_PREVIEW).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .accept(MediaType.TEXT_HTML)
                        .param("template", MailTemplates.WELCOME)
                        .param("locale", "ar"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ar.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(FRONTEND + "/email/themes/crescent-night/header.jpg")
                .contains("لمتابعة أحدث الفيديوهات")
                .contains("أحمد")
                .contains("dir=\"rtl\"");

        // every template previews in both locales
        for (String template : MailTemplates.ALL) {
            for (String locale : new String[] {"ar", "en"}) {
                mvc.perform(get(ApiPaths.Admin.MAIL_PREVIEW).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                                .accept(MediaType.TEXT_HTML)
                                .param("template", template)
                                .param("locale", locale))
                        .andExpect(status().isOk());
            }
        }
    }

    @Test
    void previewRejectsUnknownTemplateOrTheme() throws Exception {
        User interpreter = createUser("themes-preview-bad", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.MAIL_PREVIEW).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .accept(MediaType.TEXT_HTML, MediaType.APPLICATION_PROBLEM_JSON)
                        .param("template", "nope"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ApiPaths.Admin.MAIL_PREVIEW).header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .accept(MediaType.TEXT_HTML, MediaType.APPLICATION_PROBLEM_JSON)
                        .param("template", MailTemplates.WELCOME)
                        .param("theme", "nope"))
                .andExpect(status().isBadRequest());
    }
}
