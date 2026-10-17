package com.saadat.common.util;

import com.saadat.common.domain.Locale;

/**
 * "N credits" in a sentence, counted in dreams (one credit = one dream), with the correct Arabic number agreement:
 * 1 رؤيا واحدة، 2 رؤيتين، 3 to 10 رؤى، 11 and more رؤيا (Western digits, as everywhere on the site). English:
 * "1 dream credit", "N dream credits".
 */
public final class CreditCounts {

    private CreditCounts() {
    }

    public static String text(int credits, Locale locale) {
        if (locale == Locale.EN) {
            return credits == 1 ? "1 dream credit" : credits + " dream credits";
        }
        if (credits == 1) {
            return "رؤيا واحدة";
        }
        if (credits == 2) {
            return "رؤيتين";
        }
        if (credits >= 3 && credits <= 10) {
            return credits + " رؤى";
        }
        return credits + " رؤيا";
    }
}
