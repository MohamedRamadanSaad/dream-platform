package com.saadat.mail.inbound;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * Plain-text helpers for the text of an e-mail a person sent to the support mailbox (stored on the support ticket so
 * the interpreter can read it on the dashboard). Everything here is defensive: null in → null out, never throws.
 *
 * <ul>
 *   <li>{@link #htmlToText}: drops {@code <script>}/{@code <style>}/{@code <head>} and comments, turns
 *       {@code <br>}, {@code </p>}, {@code </div>}, {@code </li>} … into line breaks, strips the other tags and
 *       decodes entities ({@link HtmlUtils#htmlUnescape})</li>
 *   <li>{@link #trimQuotedReply}: cuts the text at the first quoted-reply marker ("On … wrote:", «في … كتب …:»,
 *       "-----Original Message-----"), only when some text stays above it</li>
 *   <li>{@link #normalize}: LF line endings, control characters removed, trailing spaces per line removed, at most one
 *       blank line in a row, trimmed, at most {@link #MAX_CHARS} characters (cut with "…"); null when empty</li>
 *   <li>{@link #clean}: trimQuotedReply + normalize — what is stored</li>
 * </ul>
 */
public final class MailBodyText {

    /** Longest text stored on a ticket (characters, the trailing "…" included). */
    public static final int MAX_CHARS = 20_000;
    /** A payload value or MIME part larger than this (characters) is ignored. */
    public static final int MAX_INPUT_CHARS = 1_000_000;
    static final String ELLIPSIS = "…";

    private static final Pattern DROP_BLOCKS =
            Pattern.compile("(?is)<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?\\s*>");
    private static final Pattern BLOCK_END =
            Pattern.compile("(?i)</(p|div|li|tr|h[1-6]|blockquote|table|ul|ol|pre)\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern LOOKS_LIKE_HTML =
            Pattern.compile("(?i)<(html|body|div|p|br|span|table|a\\s|b>|strong|em>|font|meta)\\b");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern TRAILING_SPACES = Pattern.compile("(?m)[ \\t\\u00A0]+$");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern BASE64 = Pattern.compile("^[A-Za-z0-9+/=\\r\\n]+$");

    private static final List<Pattern> QUOTE_MARKERS = List.of(
            Pattern.compile("(?i)^\\s*On\\s.+\\swrote:\\s*$"),
            Pattern.compile("^\\s*في\\s.*كتب.*:\\s*$"),
            Pattern.compile("(?i)^\\s*-{2,}\\s*Original Message\\s*-{2,}\\s*$"),
            Pattern.compile("^\\s*-{2,}\\s*الرسالة الأصلية\\s*-{2,}\\s*$"));

    private MailBodyText() {
    }

    /** trimQuotedReply then normalize; null when nothing is left. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        return normalize(trimQuotedReply(text));
    }

    /** HTML → readable plain text (not normalized yet: call {@link #clean} afterwards). */
    public static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = html.length() > MAX_INPUT_CHARS ? html.substring(0, MAX_INPUT_CHARS) : html;
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = DROP_BLOCKS.matcher(s).replaceAll("");
        s = COMMENTS.matcher(s).replaceAll("");
        // line breaks in the HTML source are not line breaks on screen
        s = s.replace('\n', ' ');
        s = BREAK.matcher(s).replaceAll("\n");
        s = BLOCK_END.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = HtmlUtils.htmlUnescape(s);
        s = s.replace(' ', ' ');
        // spaces left at the start of each line by the removed markup
        s = s.replaceAll("(?m)^[ \\t]+", "");
        return s;
    }

    /** Whether a text value is most likely HTML markup. */
    public static boolean looksLikeHtml(String text) {
        return text != null && LOOKS_LIKE_HTML.matcher(text).find();
    }

    /** A long run of base64 characters without spaces (an encoded attachment, not something a person wrote). */
    public static boolean looksLikeBase64(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.length() >= 200 && t.indexOf(' ') < 0 && BASE64.matcher(t).matches();
    }

    /** Cuts at the first quoted-reply marker when at least one non-blank line stays above it. */
    public static String trimQuotedReply(String text) {
        if (text == null) {
            return null;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean hasContent = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (hasContent && isQuoteMarker(line)) {
                return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, i));
            }
            if (!line.isBlank()) {
                hasContent = true;
            }
        }
        return String.join("\n", lines);
    }

    private static boolean isQuoteMarker(String line) {
        if (line.length() > 500) {
            return false;
        }
        for (Pattern p : QUOTE_MARKERS) {
            if (p.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /** See the class comment; null when empty. */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String s = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = CONTROL.matcher(s).replaceAll("");
        s = TRAILING_SPACES.matcher(s).replaceAll("");
        s = MANY_BLANK_LINES.matcher(s).replaceAll("\n\n");
        s = s.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_CHARS) {
            int cut = MAX_CHARS - ELLIPSIS.length();
            if (Character.isHighSurrogate(s.charAt(cut - 1))) {
                cut--;
            }
            s = s.substring(0, cut).stripTrailing() + ELLIPSIS;
        }
        return s;
    }
}
