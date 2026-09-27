package com.saadat.mail;

import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Localized texts from {@code messages_<ar|en>.properties} with <b>named</b> placeholders: {@code {name}} is
 * replaced by {@code args.get("name")} (positional {@code {0}} works too with keys "0", "1"...). Messages are read
 * raw (no {@link java.text.MessageFormat}), so apostrophes need no escaping.
 *
 * <p>Also formats values for display: {@link Instant}s become a localized date-time in setting
 * {@code schedule.time_zone}; {@link BigDecimal}s are rendered without exponent.
 */
@Component
public class MessageText {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private final MessageSource messageSource;
    private final SettingsService settings;

    public MessageText(MessageSource messageSource, SettingsService settings) {
        this.messageSource = messageSource;
        this.settings = settings;
    }

    /** Raw message, empty if the key is missing. */
    public Optional<String> find(String code, com.saadat.common.domain.Locale locale) {
        String raw = messageSource.getMessage(code, null, null, toJava(locale));
        return Optional.ofNullable(raw);
    }

    /** Message with {@code {key}} placeholders filled from {@code args}; "" if the key is missing. */
    public String get(String code, com.saadat.common.domain.Locale locale, Map<String, ?> args) {
        return find(code, locale).map(raw -> format(raw, formatValues(args, locale))).orElse("");
    }

    /** Copy of {@code args} with display formatting applied (Instant → localized date-time, etc.). */
    public Map<String, Object> formatValues(Map<String, ?> args, com.saadat.common.domain.Locale locale) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (args == null) {
            return out;
        }
        for (Map.Entry<String, ?> e : args.entrySet()) {
            out.put(e.getKey(), formatValue(e.getValue(), locale));
        }
        return out;
    }

    public Object formatValue(Object value, com.saadat.common.domain.Locale locale) {
        if (value instanceof Instant instant) {
            return formatInstant(instant, locale);
        }
        if (value instanceof OffsetDateTime odt) {
            return formatInstant(odt.toInstant(), locale);
        }
        if (value instanceof ZonedDateTime zdt) {
            return formatInstant(zdt.toInstant(), locale);
        }
        if (value instanceof LocalDate date) {
            return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(toJava(locale)).format(date);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value;
    }

    public String formatInstant(Instant instant, com.saadat.common.domain.Locale locale) {
        ZonedDateTime zoned = instant.atZone(zone());
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(toJava(locale))
                .format(zoned);
    }

    /** Fills {@code {key}} placeholders; unknown keys become "". */
    public static String format(String pattern, Map<String, ?> args) {
        if (pattern == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(pattern);
        StringBuilder sb = new StringBuilder(pattern.length() + 16);
        while (m.find()) {
            Object value = args == null ? null : args.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static java.util.Locale toJava(com.saadat.common.domain.Locale locale) {
        return (locale == null ? com.saadat.common.domain.Locale.AR : locale).toJavaLocale();
    }

    private ZoneId zone() {
        String id = settings.getString(SettingKeys.SCHEDULE_TIME_ZONE, null);
        if (id == null || id.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
