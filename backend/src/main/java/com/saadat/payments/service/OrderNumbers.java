package com.saadat.payments.service;

import com.saadat.payments.repo.OrderRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Readable order numbers (V38): {@code <prefix>-<n>}, n from sequence {@code order_number_seq} (starts at 15001),
 * prefix from setting {@code orders.number_prefix} (default {@value #DEFAULT_PREFIX}). The full string is stored on
 * the order, so changing the prefix never renames old orders.
 */
@Component
@RequiredArgsConstructor
public class OrderNumbers {

    public static final String DEFAULT_PREFIX = "RN";
    /** Keeps {@code <prefix>-<n>} within orders.order_number varchar(32). */
    static final int PREFIX_MAX = 16;
    private static final Pattern PREFIX_CHARS = Pattern.compile("[^A-Za-z0-9]");
    /** What an admin may type to find an order: "15001", "rn-15001", "RN 15001", "#RN-15001". */
    private static final Pattern QUERY =
            Pattern.compile("#?\\s*(?:([A-Za-z][A-Za-z0-9]*?)\\s*-?\\s*)?(\\d{1,18})");

    private final OrderRepository orderRepository;
    private final SettingsService settings;

    /** The next number, e.g. RN-15001 (consumes a sequence value; gaps are fine). */
    public String next() {
        Long n = orderRepository.nextOrderNumber();
        return prefix() + "-" + n;
    }

    /** The configured prefix: letters and digits only, upper case; blank → {@value #DEFAULT_PREFIX}. */
    public String prefix() {
        String raw = settings.getString(SettingKeys.ORDERS_NUMBER_PREFIX, DEFAULT_PREFIX);
        String clean = raw == null ? "" : PREFIX_CHARS.matcher(raw).replaceAll("").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return DEFAULT_PREFIX;
        }
        return clean.length() > PREFIX_MAX ? clean.substring(0, PREFIX_MAX) : clean;
    }

    /**
     * A search text that looks like an order number → (full number in upper case, digits): "rn-15001" →
     * (RN-15001, 15001); "15001" → (15001, 15001) without a prefix (search by the digits). Empty otherwise.
     */
    public static Optional<Query> parseQuery(String q) {
        if (q == null || q.isBlank()) {
            return Optional.empty();
        }
        Matcher m = QUERY.matcher(q.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String digits = m.group(2);
        String prefix = m.group(1);
        String number = prefix == null ? digits : prefix.toUpperCase(Locale.ROOT) + "-" + digits;
        return Optional.of(new Query(number, digits));
    }

    /** Parsed admin search: {@code number} upper-case full number, {@code digits} its numeric part. */
    public record Query(String number, String digits) {
        /** False when only digits were typed. */
        public boolean hasPrefix() {
            return !number.equals(digits);
        }
    }
}
