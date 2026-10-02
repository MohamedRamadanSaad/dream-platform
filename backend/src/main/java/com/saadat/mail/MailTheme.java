package com.saadat.mail;

/**
 * One e-mail theme from the classpath registry {@code mail/themes.json}. A theme changes only the header,
 * the page background and the footer of an e-mail (images + colours); the wording of every template stays
 * the same. Image values are paths on the public site (e.g. {@code /email/themes/crescent-night/header.jpg});
 * {@link MailThemeService#absoluteUrl(String)} turns them into absolute URLs. {@code highlight} is the light/strong
 * colour for the brand name and links written on the header/footer images; {@code buttonText} is the text colour on
 * the accent button. Both are optional (fallbacks: accent / bodyText).
 */
public record MailTheme(
        String key,
        String nameAr,
        String nameEn,
        String headerImage,
        String footerImage,
        String pageBg,
        String cardBg,
        String bodyText,
        String accent,
        String headerText,
        String footerText,
        String highlight,
        String buttonText) {
}
