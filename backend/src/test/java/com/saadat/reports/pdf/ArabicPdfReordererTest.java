package com.saadat.reports.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import org.junit.jupiter.api.Test;

class ArabicPdfReordererTest {

    @Test
    void dropsArabicDiacriticsAndKeepsTheLetters() {
        assertThat(ArabicPdfReorderer.withoutDiacritics("جداً")).isEqualTo("جدا");
        assertThat(ArabicPdfReorderer.withoutDiacritics("المعبّرة")).isEqualTo("المعبرة");
        assertThat(ArabicPdfReorderer.withoutDiacritics("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"))
                .isEqualTo("بسم الله الرحمن الرحيم");
        assertThat(ArabicPdfReorderer.withoutDiacritics("Hope 2024، ليلة 27 ـ «بارك الله فيك»"))
                .isEqualTo("Hope 2024، ليلة 27 ـ «بارك الله فيك»");
    }

    @Test
    void aDecomposedHamzaOrMaddaStaysOnItsLetter() {
        assertThat(ArabicPdfReorderer.withoutDiacritics("أ")).isEqualTo("أ"); // أ
        assertThat(ArabicPdfReorderer.withoutDiacritics("إ")).isEqualTo("إ"); // إ
        assertThat(ArabicPdfReorderer.withoutDiacritics("آ")).isEqualTo("آ"); // آ
        assertThat(ArabicPdfReorderer.withoutDiacritics("ئ")).isEqualTo("ئ"); // ئ
        assertThat(ArabicPdfReorderer.withoutDiacritics("أَ")).isEqualTo("أ"); // fatha + hamza
    }

    @Test
    void latinAccentsAreKept() {
        assertThat(ArabicPdfReorderer.withoutDiacritics("Café")).isEqualTo("Café");
    }

    @Test
    void shapedTextIsJoinedWithoutFloatingMarks() {
        String shaped = new ArabicPdfReorderer().shapeText("وجدت خاتماً من ذهب، والمعبّرة");

        assertThat(shaped).isEqualTo(new ICUBidiReorderer().shapeText("وجدت خاتما من ذهب، والمعبرة"));
        assertThat(shaped.chars().anyMatch(c -> c >= 0xFE80 && c <= 0xFEFC)).as("presentation forms").isTrue();
        assertThat(shaped.chars().noneMatch(c -> (c >= 0xFE70 && c <= 0xFE7F) || (c >= 0x064B && c <= 0x065F)))
                .as("no spacing or combining harakat").isTrue();
    }
}
