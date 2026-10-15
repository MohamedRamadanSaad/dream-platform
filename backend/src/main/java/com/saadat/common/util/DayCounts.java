package com.saadat.common.util;

import com.saadat.common.domain.Locale;

/**
 * "N days" in a sentence, with the correct Arabic number agreement: 1 يوم واحد، 2 يومين، 3 to 10 أيام، 11 and more
 * يومًا (Western digits, as everywhere on the site). English: "1 day", "N days".
 */
public final class DayCounts {

    private DayCounts() {
    }

    public static String text(int days, Locale locale) {
        if (locale == Locale.EN) {
            return days == 1 ? "1 day" : days + " days";
        }
        if (days == 1) {
            return "يوم واحد";
        }
        if (days == 2) {
            return "يومين";
        }
        if (days >= 3 && days <= 10) {
            return days + " أيام";
        }
        return days + " يومًا";
    }
}
