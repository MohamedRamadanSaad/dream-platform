package com.saadat.mail.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.ApiException;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.mail.MailSamples;
import com.saadat.mail.MailService;
import com.saadat.mail.MailTemplates;
import com.saadat.mail.MailTheme;
import com.saadat.mail.MailThemeService;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The interpreter's e-mail look (ROLE_INTERPRETER via SecurityConfig, like every /admin route):
 * <ul>
 *   <li>GET /admin/mail/themes — the theme registry with absolute image URLs</li>
 *   <li>GET /admin/mail/templates — every template with its effective theme and its on/off state</li>
 *   <li>PUT /admin/mail/templates/{template}/theme {theme} — blank theme = follow the default</li>
 *   <li>PUT /admin/mail/theme-default {theme}</li>
 *   <li>GET /admin/mail/preview?template&amp;theme&amp;locale — the rendered e-mail (text/html, sample data)</li>
 * </ul>
 * Unknown template or theme → 400 problem with code UNKNOWN_TEMPLATE / UNKNOWN_THEME.
 */
@RestController
@RequiredArgsConstructor
public class AdminMailController {

    public static final String CODE_UNKNOWN_TEMPLATE = "UNKNOWN_TEMPLATE";
    public static final String CODE_UNKNOWN_THEME = "UNKNOWN_THEME";

    /** Mirrors frontend {@code MailThemeDto}. {@code usedByDefault}: this is the current default theme. */
    public record MailThemeDto(String key, String nameAr, String nameEn, String headerImageUrl, String footerImageUrl,
                               String pageBg, String cardBg, String accent, boolean usedByDefault) {
    }

    /**
     * Mirrors frontend {@code MailTemplateRow}. {@code theme} is the effective theme key; {@code inherited} = the
     * template has no theme of its own and follows the default; {@code switchable} = false for the sign-in e-mail,
     * which is always sent.
     */
    public record MailTemplateRow(String template, String theme, boolean inherited, boolean enabled,
                                  boolean switchable) {
    }

    /** Body of the PUT routes. */
    public record ThemeRequest(String theme) {
    }

    /** Response of PUT /admin/mail/theme-default. */
    public record DefaultThemeResponse(String theme) {
    }

    private final MailThemeService themeService;
    private final MailService mailService;
    private final MailSamples samples;
    private final SettingsService settings;

    @GetMapping(ApiPaths.Admin.MAIL_THEMES)
    public List<MailThemeDto> themes() {
        String defaultKey = themeService.defaultTheme().key();
        return themeService.registry().all().stream()
                .map(t -> new MailThemeDto(t.key(), t.nameAr(), t.nameEn(), themeService.absoluteUrl(t.headerImage()),
                        themeService.absoluteUrl(t.footerImage()), t.pageBg(), t.cardBg(), t.accent(),
                        t.key().equals(defaultKey)))
                .toList();
    }

    @GetMapping(ApiPaths.Admin.MAIL_TEMPLATES)
    public List<MailTemplateRow> templates() {
        return MailTemplates.ALL.stream().map(this::row).toList();
    }

    @PutMapping(ApiPaths.Admin.MAIL_TEMPLATE_THEME)
    public MailTemplateRow setTemplateTheme(@PathVariable String template, @RequestBody ThemeRequest body) {
        requireTemplate(template);
        String theme = body == null || body.theme() == null ? "" : body.theme().trim();
        if (!theme.isEmpty()) {
            requireTheme(theme);
        }
        settings.put(SettingKeys.mailTheme(template), theme, AuthPrincipal.current().userId());
        return row(template);
    }

    @PutMapping(ApiPaths.Admin.MAIL_THEME_DEFAULT)
    public DefaultThemeResponse setDefaultTheme(@RequestBody ThemeRequest body) {
        String theme = body == null || body.theme() == null ? "" : body.theme().trim();
        requireTheme(theme);
        settings.put(SettingKeys.MAIL_THEME_DEFAULT, theme, AuthPrincipal.current().userId());
        return new DefaultThemeResponse(theme);
    }

    @GetMapping(value = ApiPaths.Admin.MAIL_PREVIEW, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@RequestParam(required = false) String template,
                                          @RequestParam(required = false) String theme,
                                          @RequestParam(required = false) String locale) {
        requireTemplate(template);
        String themeKey = theme == null ? "" : theme.trim();
        if (!themeKey.isEmpty()) {
            requireTheme(themeKey);
        }
        Locale loc = Locale.fromTag(locale);
        String html = mailService.render(template, loc, samples.model(template, loc),
                themeKey.isEmpty() ? null : themeKey).html();
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(html);
    }

    // ------------------------------------------------------------------ internals

    private MailTemplateRow row(String template) {
        MailTheme effective = themeService.resolve(template);
        return new MailTemplateRow(template, effective.key(), !themeService.hasOwnTheme(template),
                mailService.isEnabled(template), !MailTemplates.MAGIC_LINK.equals(template));
    }

    private static void requireTemplate(String template) {
        if (template == null || !MailTemplates.ALL.contains(template)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_UNKNOWN_TEMPLATE,
                    "Unknown e-mail template: " + template);
        }
    }

    private void requireTheme(String theme) {
        if (!themeService.registry().exists(theme)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_UNKNOWN_THEME,
                    "Unknown e-mail theme: " + theme);
        }
    }
}
