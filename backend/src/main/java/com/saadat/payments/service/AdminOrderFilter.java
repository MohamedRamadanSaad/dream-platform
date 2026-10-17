package com.saadat.payments.service;

import com.saadat.common.domain.Currency;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.domain.PaymentProviderType;
import com.saadat.common.error.ApiException;
import com.saadat.payments.domain.Order;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

/**
 * The optional filters of {@code GET /admin/orders} and {@code /admin/orders/counts}
 * (docs/PAYMENTS_FILTERS_CONTRACT.md §3), parsed from the query string and turned into a JPA Specification that is
 * combined with the tab ({@code scope}) and the order-number search. An invalid value → 400 {@code INVALID_FILTER}
 * with {@code field}.
 *
 * <p>An INITIATED order past {@code expires_at} counts as EXPIRED (the expiry job may not have run yet): the status
 * filter EXPIRED includes it and INITIATED leaves it out.
 */
public record AdminOrderFilter(
        Set<OrderStatus> statuses,
        PaymentProviderType provider,
        String country,
        Currency currency,
        LocalDate from,
        LocalDate to,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        UUID packageId,
        Flag flag) {

    /** Day bounds of {@code from} / {@code to} (the business is in Riyadh). */
    public static final ZoneId ZONE = ZoneId.of("Asia/Riyadh");
    public static final String CODE_INVALID = "INVALID_FILTER";
    private static final Pattern COUNTRY = Pattern.compile("[A-Za-z]{2}");

    /** Special cases. */
    public enum Flag {
        /** The approval was taken back at least once (V40). */
        REVOKED,
        /** Its unused credits were withdrawn (V41). */
        LEFTOVER_WITHDRAWN,
        /** Taken back, then sent again by the buyer and waiting for review. */
        RESENT
    }

    public static AdminOrderFilter none() {
        return new AdminOrderFilter(Set.of(), null, null, null, null, null, null, null, null, null);
    }

    /** Parses the raw query values (all optional; {@code status} may repeat or be a comma list). */
    public static AdminOrderFilter parse(Collection<String> status, String provider, String country, String currency,
                                         String from, String to, String minAmount, String maxAmount,
                                         String packageId, String flag) {
        Set<OrderStatus> statuses = EnumSet.noneOf(OrderStatus.class);
        if (status != null) {
            for (String raw : status) {
                for (String part : (raw == null ? "" : raw).split(",")) {
                    if (!part.isBlank()) {
                        statuses.add(enumValue(OrderStatus.class, part, "status"));
                    }
                }
            }
        }
        String cc = blank(country) ? null : country.trim();
        if (cc != null && !COUNTRY.matcher(cc).matches()) {
            throw invalid("country");
        }
        LocalDate fromDate = date(from, "from");
        LocalDate toDate = date(to, "to");
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate)) {
            throw invalid("to");
        }
        BigDecimal min = amount(minAmount, "minAmount");
        BigDecimal max = amount(maxAmount, "maxAmount");
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw invalid("maxAmount");
        }
        UUID pkg = null;
        if (!blank(packageId)) {
            try {
                pkg = UUID.fromString(packageId.trim());
            } catch (IllegalArgumentException e) {
                throw invalid("packageId");
            }
        }
        return new AdminOrderFilter(statuses,
                blank(provider) ? null : enumValue(PaymentProviderType.class, provider, "provider"),
                cc == null ? null : cc.toUpperCase(Locale.ROOT),
                blank(currency) ? null : enumValue(Currency.class, currency, "currency"),
                fromDate, toDate, min, max, pkg,
                blank(flag) ? null : enumValue(Flag.class, flag, "flag"));
    }

    /** The filters as one Specification ({@code now} decides which INITIATED orders count as EXPIRED). */
    public Specification<Order> toSpec(Instant now) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (!statuses.isEmpty()) {
                List<Predicate> any = new ArrayList<>();
                for (OrderStatus s : statuses) {
                    if (s == OrderStatus.EXPIRED) {
                        any.add(cb.equal(root.get("status"), OrderStatus.EXPIRED));
                        any.add(cb.and(cb.equal(root.get("status"), OrderStatus.INITIATED),
                                cb.lessThanOrEqualTo(root.<Instant>get("expiresAt"), now)));
                    } else if (s == OrderStatus.INITIATED) {
                        any.add(cb.and(cb.equal(root.get("status"), OrderStatus.INITIATED),
                                cb.greaterThan(root.<Instant>get("expiresAt"), now)));
                    } else {
                        any.add(cb.equal(root.get("status"), s));
                    }
                }
                all.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (provider != null) {
                all.add(cb.equal(root.get("provider"), provider));
            }
            if (country != null) {
                all.add(cb.equal(root.get("countryCode"), country));
            }
            if (currency != null) {
                all.add(cb.equal(root.get("currency"), currency));
            }
            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"), from.atStartOfDay(ZONE).toInstant()));
            }
            if (to != null) {
                all.add(cb.lessThan(root.<Instant>get("createdAt"), to.plusDays(1).atStartOfDay(ZONE).toInstant()));
            }
            if (minAmount != null) {
                all.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("amount"), minAmount));
            }
            if (maxAmount != null) {
                all.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("amount"), maxAmount));
            }
            if (packageId != null) {
                all.add(cb.equal(root.get("packageId"), packageId));
            }
            if (flag != null) {
                switch (flag) {
                    case REVOKED -> all.add(cb.isNotNull(root.get("approvalRevokedAt")));
                    case LEFTOVER_WITHDRAWN -> all.add(cb.isNotNull(root.get("leftoverWithdrawnAt")));
                    case RESENT -> all.add(cb.and(cb.isNotNull(root.get("approvalRevokedAt")),
                            cb.equal(root.get("status"), OrderStatus.PENDING_REVIEW)));
                }
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** 400 INVALID_FILTER naming the field. */
    static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad-request", CODE_INVALID, "Invalid filter: " + field)
                .with("field", field);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(field);
        }
    }

    private static LocalDate date(String raw, String field) {
        if (blank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw invalid(field);
        }
    }

    private static BigDecimal amount(String raw, String field) {
        if (blank(raw)) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            if (v.signum() < 0) {
                throw invalid(field);
            }
            return v;
        } catch (NumberFormatException e) {
            throw invalid(field);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
