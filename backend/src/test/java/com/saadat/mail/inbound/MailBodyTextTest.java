package com.saadat.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailBodyTextTest {

    @Test
    void htmlToTextDropsScriptsKeepsLineBreaksAndDecodesEntities() {
        String html = "<html><head><title>T</title><style>p{color:red}</style></head><body>"
                + "<script type=\"text/javascript\">alert('x')</script>"
                + "<p>Salam&nbsp;alaykum,</p><div>Line&nbsp;one<br/>Line two<BR>Line three</div>"
                + "<ul><li>First</li><li>Second</li></ul><!-- hidden --><p>5 &lt; 6 &amp; &quot;ok&quot; &#1587;</p>"
                + "</body></html>";

        String text = MailBodyText.clean(MailBodyText.htmlToText(html));

        assertThat(text).isEqualTo(
                "Salam alaykum,\nLine one\nLine two\nLine three\nFirst\nSecond\n\n5 < 6 & \"ok\" س");
        assertThat(text).doesNotContain("alert").doesNotContain("color").doesNotContain("hidden")
                .doesNotContain("<p").doesNotContain("<br");
        assertThat(MailBodyText.htmlToText(null)).isNull();
    }

    @Test
    void htmlLineBreaksInTheSourceAreNotLineBreaks() {
        assertThat(MailBodyText.clean(MailBodyText.htmlToText("<p>one\ntwo</p>\n\n\n\n<p>three</p>")))
                .isEqualTo("one two\nthree");
    }

    @Test
    void normalizeFixesLineEndingsBlankLinesAndLength() {
        assertThat(MailBodyText.normalize("  a\r\nb\rc  \n\n\n\n\nd\u0007  ")).isEqualTo("a\nb\nc\n\nd");
        assertThat(MailBodyText.normalize(" \n ")).isNull();
        assertThat(MailBodyText.normalize(null)).isNull();

        String cut = MailBodyText.normalize("x".repeat(MailBodyText.MAX_CHARS + 500));
        assertThat(cut).hasSize(MailBodyText.MAX_CHARS).endsWith("…");
        assertThat(MailBodyText.normalize("y".repeat(MailBodyText.MAX_CHARS))).hasSize(MailBodyText.MAX_CHARS)
                .doesNotContain("…");
    }

    @Test
    void trimsQuotedRepliesInEnglishAndArabic() {
        assertThat(MailBodyText.clean("Thanks, it works now.\n\nOn Fri, Oct 3, 2026 at 10:00 AM Support"
                + " <support@saadatu-aldarein.com> wrote:\n> We are working on it"))
                .isEqualTo("Thanks, it works now.");
        assertThat(MailBodyText.clean("شكرًا لكم\n\nفي الجمعة، 3 أكتوبر 2026 في 10:00 ص كتب الدعم"
                + " <support@saadatu-aldarein.com>:\n> نعمل على طلبك"))
                .isEqualTo("شكرًا لكم");
        assertThat(MailBodyText.clean("My question\n-----Original Message-----\nFrom: someone"))
                .isEqualTo("My question");
    }

    @Test
    void keepsTheTextWhenTheMarkerIsTheFirstLineOrAbsent() {
        String onlyQuote = "On Fri, Oct 3, 2026 Support <s@example.com> wrote:\n> old text";
        assertThat(MailBodyText.clean(onlyQuote)).isEqualTo(onlyQuote);
        assertThat(MailBodyText.clean("I wrote: hello\nOn Monday I paid")).isEqualTo("I wrote: hello\nOn Monday I paid");
    }

    @Test
    void recognisesHtmlAndBase64() {
        assertThat(MailBodyText.looksLikeHtml("<div>Hi</div>")).isTrue();
        assertThat(MailBodyText.looksLikeHtml("5 < 6 and a<b")).isFalse();
        assertThat(MailBodyText.looksLikeBase64("QUJD".repeat(100))).isTrue();
        assertThat(MailBodyText.looksLikeBase64("short")).isFalse();
        assertThat(MailBodyText.looksLikeBase64("word ".repeat(100))).isFalse();
    }
}
