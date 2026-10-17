package com.saadat.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Locale;
import org.junit.jupiter.api.Test;

class CreditCountsTest {

    @Test
    void arabicNumberAgreementAndEnglishPlural() {
        assertThat(CreditCounts.text(1, Locale.AR)).isEqualTo("رؤيا واحدة");
        assertThat(CreditCounts.text(2, Locale.AR)).isEqualTo("رؤيتين");
        assertThat(CreditCounts.text(3, Locale.AR)).isEqualTo("3 رؤى");
        assertThat(CreditCounts.text(10, Locale.AR)).isEqualTo("10 رؤى");
        assertThat(CreditCounts.text(11, Locale.AR)).isEqualTo("11 رؤيا");
        assertThat(CreditCounts.text(1, Locale.EN)).isEqualTo("1 dream credit");
        assertThat(CreditCounts.text(3, Locale.EN)).isEqualTo("3 dream credits");
    }
}
