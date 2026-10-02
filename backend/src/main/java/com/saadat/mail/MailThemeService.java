package com.saadat.mail;

import com.saadat.config.props.AppProperties;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Which theme an e-mail uses and where its images live.
 *
 * <p>Resolution for a template: STRING setting {@code mail.theme.<template>} → when blank or unknown, setting
 * {@code mail.theme.default} → when blank or unknown, the registry default ({@link MailThemes#defaultKey()}).
 *
 * <p>Image URLs: (setting {@code mail.assets_base_url} when not blank, else {@code app.frontend-url}) without a
 * trailing slash + the theme's image path. Absolute image URLs in the theme are kept as they are.
 */
@Service
public class MailThemeService {

    private final MailThemes themes;
    private final SettingsService settings;
    private final AppProperties properties;

    public MailThemeService(MailThemes themes, SettingsService settings, AppProperties properties) {
        this.themes = themes;
        this.settings = settings;
        this.properties = properties;
    }

    /** The theme this template uses now (never null). */
    public MailTheme resolve(String template) {
        Optional<MailTheme> own = template == null ? Optional.empty()
                : themes.find(settings.getString(SettingKeys.mailTheme(template), null));
        return own.orElseGet(this::defaultTheme);
    }

    /** {@code overrideKey} when it names a known theme (preview), else {@link #resolve(String)}. */
    public MailTheme resolve(String template, String overrideKey) {
        return themes.find(overrideKey).orElseGet(() -> resolve(template));
    }

    /** Setting {@code mail.theme.default} when it names a known theme, else the registry default. */
    public MailTheme defaultTheme() {
        return themes.find(settings.getString(SettingKeys.MAIL_THEME_DEFAULT, null))
                .orElseGet(themes::defaultTheme);
    }

    /** Raw per-template setting value names a known theme (false = the template follows the default). */
    public boolean hasOwnTheme(String template) {
        return themes.exists(settings.getString(SettingKeys.mailTheme(template), null));
    }

    /** Base URL of the e-mail images, without a trailing slash. */
    public String assetsBaseUrl() {
        String base = settings.getString(SettingKeys.MAIL_ASSETS_BASE_URL, "");
        if (base == null || base.isBlank()) {
            base = properties.getFrontendUrl();
        }
        return trimSlash(base);
    }

    /** Absolute URL of an image path of a theme ("" for a blank path). */
    public String absoluteUrl(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String p = path.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        return assetsBaseUrl() + (p.startsWith("/") ? p : "/" + p);
    }

    /** What the e-mail layout reads as {@code ${theme}}. */
    public MailThemeView view(MailTheme theme) {
        return new MailThemeView(theme, absoluteUrl(theme.headerImage()), absoluteUrl(theme.footerImage()));
    }

    public MailThemes registry() {
        return themes;
    }

    static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
