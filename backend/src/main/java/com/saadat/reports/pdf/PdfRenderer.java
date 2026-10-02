package com.saadat.reports.pdf;

import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.saadat.common.domain.Locale;
import com.saadat.mail.MessageText;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders a Thymeleaf XHTML template ({@code templates/pdf/...}) to PDF with openhtmltopdf (PDFBox).
 *
 * <p>Arabic: PDFBox does not apply OpenType GSUB/GPOS, so letters are joined by ICU shaping into Arabic
 * Presentation Forms (rtl-support: {@link ICUBidiSplitter} + {@link ArabicPdfReorderer}, which also leaves out
 * diacritics the engine cannot position); the embedded IBM Plex Sans Arabic has glyphs for U+FB50–U+FDFF /
 * U+FE70–U+FEFF. Latin text uses IBM Plex Sans; openhtmltopdf falls back per glyph along the CSS font-family list.
 * The bidi splitter/reorderer is always on (Arabic text can appear in English documents); only the default
 * direction follows the locale.
 *
 * <p>The template output is parsed as XML: templates must be well-formed and user text must pass
 * {@link #clean(String)} (removes characters XML 1.0 forbids).
 */
@Component
public class PdfRenderer {

    public static final String FAMILY_ARABIC = "IBM Plex Sans Arabic";
    public static final String FAMILY_LATIN = "IBM Plex Sans";
    static final int REGULAR = 400;
    static final int BOLD = 700;
    private static final String FONT_DIR = "fonts/";

    private final ITemplateEngine templateEngine;
    private final byte[] arabicRegular;
    private final byte[] arabicBold;
    private final byte[] latinRegular;
    private final byte[] latinBold;

    public PdfRenderer(ITemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
        this.arabicRegular = load("IBMPlexSansArabic-Regular.ttf");
        this.arabicBold = load("IBMPlexSansArabic-Bold.ttf");
        this.latinRegular = load("IBMPlexSans-Regular.ttf");
        this.latinBold = load("IBMPlexSans-Bold.ttf");
    }

    /** Renders {@code template} (e.g. {@code pdf/report}) with {@code model} in {@code locale}. */
    public byte[] render(String template, Locale locale, Map<String, Object> model) {
        Locale loc = locale == null ? Locale.AR : locale;
        String html = templateEngine.process(template, new Context(MessageText.toJava(loc), model));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
        builder.useUnicodeBidiReorderer(new ArabicPdfReorderer());
        builder.defaultTextDirection(loc == Locale.AR
                ? BaseRendererBuilder.TextDirection.RTL : BaseRendererBuilder.TextDirection.LTR);
        font(builder, arabicRegular, FAMILY_ARABIC, REGULAR);
        font(builder, arabicBold, FAMILY_ARABIC, BOLD);
        font(builder, latinRegular, FAMILY_LATIN, REGULAR);
        font(builder, latinBold, FAMILY_LATIN, BOLD);
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF rendering failed for " + template, e);
        }
        return out.toByteArray();
    }

    /** Removes characters that are not allowed in XML 1.0 (control characters other than tab/CR/LF). */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0xFFFE && c != 0xFFFF);
            if (!allowed) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                    sb.append(text, 0, i);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? text : sb.toString();
    }

    private static void font(PdfRendererBuilder builder, byte[] bytes, String family, int weight) {
        FSSupplier<InputStream> supplier = () -> new ByteArrayInputStream(bytes);
        builder.useFont(supplier, family, weight, BaseRendererBuilder.FontStyle.NORMAL, true);
    }

    private static byte[] load(String file) {
        try (InputStream in = new ClassPathResource(FONT_DIR + file).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing font " + file, e);
        }
    }
}
