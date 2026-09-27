package com.saadat.admin.analytics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.saadat.admin.analytics.AnalyticsDtos.AdminCountryDashboardDto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminSummaryDto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminUserRowDto;
import com.saadat.admin.analytics.AnalyticsDtos.CountryStatDto;
import com.saadat.admin.analytics.AnalyticsDtos.RevenueDto;
import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.OrderStatus;
import com.saadat.common.error.ValidationException;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.domain.Country;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.repo.UserRepository;
import com.saadat.users.repo.UserSessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interpreter dashboards (spec §9). Revenue is converted to the base currency USD with the static rates in
 * setting {@code pricing.fx_to_usd}.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    static final Currency BASE_CURRENCY = Currency.USD;
    static final int TOP_N = 8;
    /** Window of the summary's revenue and average response time. */
    static final Duration SUMMARY_WINDOW = Duration.ofDays(30);

    public static final String LIST_TOP_VISITS = "top-visits";
    public static final String LIST_TOP_PAYING = "top-paying";
    public static final String LIST_HAS_DRAFTS = "has-drafts";
    public static final String LIST_HIGH_RATING = "high-rating";
    public static final String LIST_LOW_RATING = "low-rating";
    public static final String LIST_ALL = "all";
    static final double HIGH_RATING_MIN = 4.0;
    static final double LOW_RATING_MAX = 3.0;

    private final DreamRepository dreamRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final UserSessionRepository sessionRepository;
    private final CountryRepository countryRepository;
    private final AnalyticsRepository analyticsRepository;
    private final SettingsService settingsService;
    private final Clock clock;

    /** Analytics periods (types.ts AdminCountryDashboard.period). */
    enum Period {
        D7("7d", Duration.ofDays(7)),
        D30("30d", Duration.ofDays(30)),
        Y1("1y", Duration.ofDays(365)),
        ALL("all", null);

        final String code;
        final Duration length;

        Period(String code, Duration length) {
            this.code = code;
            this.length = length;
        }

        static Period parse(String code) {
            if (code == null || code.isBlank()) {
                return D30;
            }
            for (Period p : values()) {
                if (p.code.equalsIgnoreCase(code.trim())) {
                    return p;
                }
            }
            throw new ValidationException("Unknown period: " + code, "INVALID_PERIOD");
        }

        Instant since(Instant now) {
            return length == null ? Instant.EPOCH : now.minus(length);
        }
    }

    // ================================================================== summary

    @Transactional(readOnly = true)
    public AdminSummaryDto summary() {
        Instant now = clock.instant();
        ZoneId zone = ZoneId.of(settingsService.getString(SettingKeys.SCHEDULE_TIME_ZONE).trim());
        Instant startOfToday = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant windowStart = now.minus(SUMMARY_WINDOW);

        List<RevenueDto> revenue = new ArrayList<>();
        for (Object[] row : orderRepository.sumAmountByCurrencySince(OrderStatus.SUCCESS, windowStart)) {
            revenue.add(new RevenueDto(toCurrency(row[0]), Rows.decimal(row[1]).setScale(2, RoundingMode.HALF_UP)));
        }
        revenue.sort(Comparator.comparing(r -> r.currency().name()));
        double avg = dreamRepository.averageResponseHoursSince(windowStart);
        return new AdminSummaryDto(
                dreamRepository.countByStatus(DreamStatus.IN_REVIEW),
                dreamRepository.countByStatus(DreamStatus.AWAITING_USER_REPLY),
                dreamRepository.countByStatusAndExpectedByBefore(DreamStatus.IN_REVIEW, now),
                dreamRepository.countByStatusAndInterpretedAtAfter(DreamStatus.INTERPRETED, startOfToday),
                revenue,
                round1(avg));
    }

    // ================================================================== countries

    @Transactional(readOnly = true)
    public AdminCountryDashboardDto countries(String periodCode, Locale locale) {
        Period period = Period.parse(periodCode);
        Instant since = period.since(clock.instant());
        Map<String, BigDecimal> fx = fxToUsd();

        Map<String, Long> visits = countMap(sessionRepository.countVisitsByCountrySince(since));
        Map<String, Long> dreams = countMap(dreamRepository.countSubmittedByCountrySince(since));
        Map<String, Long> users = countMap(userRepository.countUsersByCountry());
        Map<String, BigDecimal> revenue = new HashMap<>();
        for (Object[] row : orderRepository.sumAmountByCountryAndCurrencySince(OrderStatus.SUCCESS, since)) {
            String cc = Rows.string(row[0]);
            if (cc == null || cc.isEmpty()) {
                continue;
            }
            revenue.merge(cc, toBase(Rows.decimal(row[2]), toCurrency(row[1]), fx), BigDecimal::add);
        }

        Set<String> codes = new LinkedHashSet<>();
        codes.addAll(visits.keySet());
        codes.addAll(dreams.keySet());
        codes.addAll(revenue.keySet());
        codes.addAll(users.keySet());
        Map<String, Country> countries = codes.isEmpty() ? Map.of() : countryRepository.findByCodeIn(codes).stream()
                .collect(Collectors.toMap(Country::getCode, Function.identity()));

        List<CountryStatDto> stats = new ArrayList<>();
        for (String cc : codes) {
            Country c = countries.get(cc);
            String name = c == null ? cc : (locale == Locale.EN ? c.getNameEn() : c.getNameAr());
            stats.add(new CountryStatDto(cc, name, visits.getOrDefault(cc, 0L), dreams.getOrDefault(cc, 0L),
                    revenue.getOrDefault(cc, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                    users.getOrDefault(cc, 0L)));
        }
        return new AdminCountryDashboardDto(period.code, BASE_CURRENCY,
                top(stats, Comparator.comparingLong(CountryStatDto::visits)),
                top(stats, Comparator.comparingLong(CountryStatDto::dreams)),
                top(stats, Comparator.comparingDouble(CountryStatDto::revenueBase)));
    }

    private static List<CountryStatDto> top(List<CountryStatDto> stats, Comparator<CountryStatDto> by) {
        return stats.stream().sorted(by.reversed()).limit(TOP_N).toList();
    }

    // ================================================================== users

    @Transactional(readOnly = true)
    public PageResponse<AdminUserRowDto> users(String list, String q, Integer page, Integer size) {
        String kind = list == null || list.isBlank() ? LIST_ALL : list.trim();
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        Map<UUID, BigDecimal> paid = paidBaseByUser();

        List<AdminUserRowDto> rows = new ArrayList<>();
        for (Object[] r : analyticsRepository.userRows()) {
            AdminUserRowDto row = toRow(r, paid);
            if (!needle.isEmpty() && !contains(row.name(), needle) && !contains(row.email(), needle)) {
                continue;
            }
            rows.add(row);
        }
        List<AdminUserRowDto> selected = switch (kind) {
            case LIST_TOP_VISITS -> rows.stream()
                    .sorted(Comparator.comparingLong(AdminUserRowDto::visits).reversed()).toList();
            case LIST_TOP_PAYING -> rows.stream()
                    .sorted(Comparator.comparingDouble(AdminUserRowDto::totalPaidBase).reversed()).toList();
            case LIST_HAS_DRAFTS -> rows.stream().filter(r -> r.drafts() > 0)
                    .sorted(Comparator.comparingLong(AdminUserRowDto::drafts).reversed()).toList();
            case LIST_HIGH_RATING -> rows.stream().filter(r -> r.avgRating() != null && r.avgRating() >= HIGH_RATING_MIN)
                    .sorted(Comparator.comparing(AdminUserRowDto::avgRating).reversed()).toList();
            case LIST_LOW_RATING -> rows.stream().filter(r -> r.avgRating() != null && r.avgRating() < LOW_RATING_MAX)
                    .sorted(Comparator.comparing(AdminUserRowDto::avgRating)).toList();
            case LIST_ALL -> rows.stream()
                    .sorted(Comparator.comparing(AdminUserRowDto::lastSeenAt,
                            Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed()).toList();
            default -> throw new ValidationException("Unknown list: " + kind, "INVALID_LIST");
        };
        Pageable pageable = Pages.of(page, size);
        int from = (int) Math.min((long) pageable.getPageNumber() * pageable.getPageSize(), selected.size());
        int to = Math.min(from + pageable.getPageSize(), selected.size());
        return PageResponse.of(selected.subList(from, to), pageable.getPageNumber(), pageable.getPageSize(),
                selected.size());
    }

    /** The row fields of one user (any role), or null if unknown. */
    @Transactional(readOnly = true)
    public AdminUserRowDto userRow(UUID userId) {
        List<Object[]> rows = analyticsRepository.userRow(userId);
        if (rows.isEmpty()) {
            return null;
        }
        return toRow(rows.get(0), paidBaseByUser());
    }

    private AdminUserRowDto toRow(Object[] r, Map<UUID, BigDecimal> paid) {
        UUID id = Rows.uuid(r[0]);
        Double avg = Rows.doubleOrNull(r[7]);
        return new AdminUserRowDto(id, Rows.string(r[1]), Rows.string(r[2]), Rows.string(r[3]), Rows.longValue(r[4]),
                Rows.longValue(r[5]), Rows.longValue(r[6]),
                paid.getOrDefault(id, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP).doubleValue(),
                avg == null ? null : round1(avg), Rows.instant(r[8]));
    }

    private Map<UUID, BigDecimal> paidBaseByUser() {
        Map<String, BigDecimal> fx = fxToUsd();
        Map<UUID, BigDecimal> paid = new HashMap<>();
        for (Object[] row : orderRepository.sumAmountByUserAndCurrency(List.of(OrderStatus.SUCCESS))) {
            UUID userId = Rows.uuid(row[0]);
            paid.merge(userId, toBase(Rows.decimal(row[2]), toCurrency(row[1]), fx), BigDecimal::add);
        }
        return paid;
    }

    // ================================================================== fx & helpers

    Map<String, BigDecimal> fxToUsd() {
        return settingsService.getJson(SettingKeys.PRICING_FX_TO_USD, new TypeReference<Map<String, BigDecimal>>() {
        });
    }

    static BigDecimal toBase(BigDecimal amount, Currency currency, Map<String, BigDecimal> fx) {
        if (amount == null || currency == null) {
            return BigDecimal.ZERO;
        }
        if (currency == BASE_CURRENCY) {
            return amount;
        }
        BigDecimal rate = fx.get(currency.name());
        return rate == null ? BigDecimal.ZERO : amount.multiply(rate);
    }

    private static Currency toCurrency(Object o) {
        if (o instanceof Currency c) {
            return c;
        }
        return o == null ? null : Currency.valueOf(o.toString().trim());
    }

    private static Map<String, Long> countMap(List<Object[]> rows) {
        Map<String, Long> m = new HashMap<>();
        for (Object[] row : rows) {
            String cc = Rows.string(row[0]);
            if (cc != null && !cc.isEmpty()) {
                m.merge(cc, Rows.longValue(row[1]), Long::sum);
            }
        }
        return m;
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
