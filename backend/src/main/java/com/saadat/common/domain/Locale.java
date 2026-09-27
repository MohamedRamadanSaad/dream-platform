package com.saadat.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * UI / e-mail locale (types.ts Locale = 'ar' | 'en').
 * Stored in the DB as the constant name ("AR"/"EN"), serialized to JSON as lower-case ("ar"/"en").
 * NOTE: clashes by simple name with {@link java.util.Locale}; import carefully.
 */
public enum Locale {
    AR("ar"),
    EN("en");

    private final String code;

    Locale(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public java.util.Locale toJavaLocale() {
        return java.util.Locale.forLanguageTag(code);
    }

    /** Lenient parse: accepts "ar", "AR", "ar-SA", "en-US"...; anything unknown falls back to AR. */
    public static Locale fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return AR;
        }
        String t = tag.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith(EN.code) ? EN : AR;
    }

    /** Strict JSON parse: only "ar"/"en" (case-insensitive). */
    @JsonCreator
    public static Locale fromJson(String value) {
        if (value == null) {
            return null;
        }
        for (Locale l : values()) {
            if (l.code.equalsIgnoreCase(value) || l.name().equalsIgnoreCase(value)) {
                return l;
            }
        }
        throw new IllegalArgumentException("Unknown locale: " + value);
    }
}
