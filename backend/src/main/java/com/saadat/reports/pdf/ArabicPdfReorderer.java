package com.saadat.reports.pdf;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import java.text.Normalizer;

/**
 * ICU shaping and reordering for the PDF engine, without Arabic diacritics (tashkeel).
 *
 * <p>PDFBox draws every glyph at its advance width and cannot apply the font's mark positioning (OpenType GPOS).
 * ICU therefore shapes harakat into spacing presentation forms (U+FE70–U+FE7F) that float beside their letter
 * ("جدا ً"), and zero-width marks would collide with tall or dotted letters. Unvocalised text is the norm in print,
 * so the marks are removed before shaping. Letters never change: the text is put in NFC first, so a decomposed
 * hamza or madda stays on its letter (ا + U+0654 → أ) instead of being dropped.
 */
public class ArabicPdfReorderer extends ICUBidiReorderer {

    @Override
    public String shapeText(String text) {
        return super.shapeText(withoutDiacritics(text));
    }

    /** {@code text} in NFC without Arabic combining marks (harakat, tanween, shadda, sukun, Quranic signs). */
    static String withoutDiacritics(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(nfc.length());
        nfc.codePoints().filter(cp -> !isArabicMark(cp)).forEach(sb::appendCodePoint);
        return sb.toString();
    }

    /** A non-spacing mark from the Arabic, Arabic Supplement or Arabic Extended-A/B blocks. */
    static boolean isArabicMark(int cp) {
        boolean arabicBlock = (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F)
                || (cp >= 0x0870 && cp <= 0x08FF);
        return arabicBlock && Character.getType(cp) == Character.NON_SPACING_MARK;
    }
}
