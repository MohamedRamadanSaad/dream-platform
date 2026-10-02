package com.saadat.mail;

import org.springframework.web.util.HtmlUtils;

/**
 * The resolved theme as the e-mail layout reads it ({@code ${theme.pageBg}}, {@code ${theme.headerImageUrl}}…).
 * Plain JavaBean getters so Thymeleaf/SpEL property access is unambiguous.
 *
 * <p>{@link #getHeaderVml()} / {@link #getFooterVml()} are the opening Outlook (VML) conditional comments that
 * paint the background image in Outlook for Windows; the layout prints them unescaped and closes them with a
 * static {@code <!--[if gte mso 9]></v:textbox></v:rect><![endif]-->}. They are built here because Thymeleaf does
 * not evaluate expressions inside HTML comments.
 */
public final class MailThemeView {

    public static final int WIDTH = 600;
    public static final int HEADER_HEIGHT = 180;
    public static final int FOOTER_HEIGHT = 150;

    private final MailTheme theme;
    private final String headerImageUrl;
    private final String footerImageUrl;

    public MailThemeView(MailTheme theme, String headerImageUrl, String footerImageUrl) {
        this.theme = theme;
        this.headerImageUrl = headerImageUrl == null ? "" : headerImageUrl;
        this.footerImageUrl = footerImageUrl == null ? "" : footerImageUrl;
    }

    public String getKey() {
        return theme.key();
    }

    public String getNameAr() {
        return theme.nameAr();
    }

    public String getNameEn() {
        return theme.nameEn();
    }

    public String getPageBg() {
        return theme.pageBg();
    }

    public String getCardBg() {
        return theme.cardBg();
    }

    public String getBodyText() {
        return theme.bodyText();
    }

    public String getAccent() {
        return theme.accent();
    }

    public String getHeaderText() {
        return theme.headerText();
    }

    public String getFooterText() {
        return theme.footerText();
    }

    public String getHeaderImageUrl() {
        return headerImageUrl;
    }

    public String getFooterImageUrl() {
        return footerImageUrl;
    }

    public String getHeaderVml() {
        return vmlOpen(headerImageUrl, "width:" + WIDTH + "px;height:" + HEADER_HEIGHT + "px;", "");
    }

    public String getFooterVml() {
        return vmlOpen(footerImageUrl, "width:" + WIDTH + "px;", " style=\"mso-fit-shape-to-text:true\"");
    }

    private String vmlOpen(String imageUrl, String rectStyle, String textboxAttrs) {
        return "<!--[if gte mso 9]><v:rect xmlns:v=\"urn:schemas-microsoft-com:vml\" fill=\"true\" stroke=\"false\""
                + " style=\"" + rectStyle + "\"><v:fill type=\"frame\" src=\"" + HtmlUtils.htmlEscape(imageUrl)
                + "\" color=\"" + HtmlUtils.htmlEscape(theme.pageBg() == null ? "" : theme.pageBg()) + "\" />"
                + "<v:textbox inset=\"0,0,0,0\"" + textboxAttrs + "><![endif]-->";
    }
}
