package com.saadat.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.saadat.common.domain.Locale;
import org.junit.jupiter.api.Test;

class DayCountsTest {

    @Test
    void arabicAgreesWithTheNumber() {
        assertThat(DayCounts.text(1, Locale.AR)).isEqualTo("يوم واحد");
        assertThat(DayCounts.text(2, Locale.AR)).isEqualTo("يومين");
        assertThat(DayCounts.text(3, Locale.AR)).isEqualTo("3 أيام");
        assertThat(DayCounts.text(10, Locale.AR)).isEqualTo("10 أيام");
        assertThat(DayCounts.text(11, Locale.AR)).isEqualTo("11 يومًا");
        assertThat(DayCounts.text(30, Locale.AR)).isEqualTo("30 يومًا");
    }

    @Test
    void english() {
        assertThat(DayCounts.text(1, Locale.EN)).isEqualTo("1 day");
        assertThat(DayCounts.text(3, Locale.EN)).isEqualTo("3 days");
    }
}
