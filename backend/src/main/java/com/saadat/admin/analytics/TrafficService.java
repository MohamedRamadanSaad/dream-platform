package com.saadat.admin.analytics;

import com.saadat.admin.analytics.AnalyticsQueries.CountryCount;
import com.saadat.admin.analytics.AnalyticsQueries.Counts;
import com.saadat.admin.analytics.AnalyticsQueries.DayCount;
import com.saadat.admin.analytics.AnalyticsQueries.DayRecord;
import com.saadat.admin.analytics.AnalyticsQueries.KeyCount;
import com.saadat.admin.analytics.AnalyticsQueries.MonthRank;
import com.saadat.admin.analytics.AnalyticsQueries.MonthRecord;
import com.saadat.admin.analytics.AnalyticsQueries.SideCounts;
import com.saadat.admin.analytics.AnalyticsQueries.ViewFilter;
import com.saadat.admin.analytics.TrafficDtos.BestDay;
import com.saadat.admin.analytics.TrafficDtos.BestMonth;
import com.saadat.admin.analytics.TrafficDtos.CountryTraffic;
import com.saadat.admin.analytics.TrafficDtos.DailyTraffic;
import com.saadat.admin.analytics.TrafficDtos.DeviceTraffic;
import com.saadat.admin.analytics.TrafficDtos.HourlyTraffic;
import com.saadat.admin.analytics.TrafficDtos.PageTraffic;
import com.saadat.admin.analytics.TrafficDtos.ReferrerTraffic;
import com.saadat.admin.analytics.TrafficDtos.TrafficKpis;
import com.saadat.admin.analytics.TrafficDtos.TrafficRecords;
import com.saadat.admin.analytics.TrafficDtos.TrafficReport;
import com.saadat.common.domain.Device;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.ValidationException;
import com.saadat.settings.BusinessZone;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /admin/analytics/traffic (contract §2). Default range = the current calendar month (business time zone) up
 * to today; the comparison range = the same number of days immediately before {@code from}. The country / device /
 * path filters apply to the page-view metrics only; signups, dreams and paid orders are site-wide. Records are
 * all-time and unfiltered.
 */
@Service
@RequiredArgsConstructor
public class TrafficService {

    static final int TOP_PAGES = 50; // the dashboard pages through them 5 at a time
    static final int TOP_COUNTRIES = 10;
    static final int TOP_REFERRERS = 8;
    static final int HOURS_PER_DAY = 24;
    static final long MAX_RANGE_DAYS = 366;
    static final double PERCENT = 100.0;
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Z]{2}$");

    private final AnalyticsQueries queries;
    private final BusinessZone businessZone;
    private final Clock clock;

    @Transactional(readOnly = true)
    public TrafficReport report(LocalDate from, LocalDate to, String country, String device, String path,
                                Locale locale) {
        ZoneId zone = businessZone.zone();
        String tz = zone.getId();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate end = to != null ? to : (from != null && from.isAfter(today) ? from : today);
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        if (start.isAfter(end)) {
            throw new ValidationException("from must not be after to", "INVALID_RANGE");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ValidationException("The range may cover at most " + MAX_RANGE_DAYS + " days", "INVALID_RANGE");
        }
        LocalDate compareTo = start.minusDays(1);
        LocalDate compareFrom = start.minusDays(days);
        ViewFilter filter = filter(country, device, path);

        Instant currentStart = startOf(start, zone);
        Instant currentEnd = startOf(end.plusDays(1), zone);
        Instant previousStart = startOf(compareFrom, zone);

        TrafficKpis current = kpis(currentStart, currentEnd, filter);
        TrafficKpis previous = kpis(previousStart, currentStart, filter);

        Map<LocalDate, DayCount> currentDays = byDay(queries.viewsByDay(currentStart, currentEnd, filter, tz));
        Map<LocalDate, DayCount> previousDays = byDay(queries.viewsByDay(previousStart, currentStart, filter, tz));
        List<DailyTraffic> daily = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            DayCount c = currentDays.get(date);
            DayCount p = previousDays.get(compareFrom.plusDays(i));
            daily.add(new DailyTraffic(date, c == null ? 0 : c.views(), c == null ? 0 : c.visitors(),
                    p == null ? 0 : p.views()));
        }

        long[] hourViews = queries.viewsByHour(currentStart, currentEnd, filter, tz);
        long[] hourDreams = queries.dreamsByHour(currentStart, currentEnd, tz);
        List<HourlyTraffic> hourly = new ArrayList<>(HOURS_PER_DAY);
        for (int h = 0; h < HOURS_PER_DAY; h++) {
            hourly.add(new HourlyTraffic(h, hourViews[h], hourDreams[h]));
        }

        List<PageTraffic> topPages = queries.topPages(currentStart, currentEnd, filter, TOP_PAGES).stream()
                .map(k -> new PageTraffic(k.key(), k.views(), k.visitors()))
                .toList();
        List<CountryTraffic> topCountries = queries.topCountries(currentStart, currentEnd, filter, TOP_COUNTRIES)
                .stream()
                .map(c -> new CountryTraffic(c.code(), countryName(c, locale), c.views(), c.visitors()))
                .toList();
        Map<String, Long> deviceViews = new HashMap<>();
        for (KeyCount k : queries.devices(currentStart, currentEnd, filter)) {
            deviceViews.merge(k.key(), k.views(), Long::sum);
        }
        List<DeviceTraffic> devices = Arrays.stream(Device.values())
                .map(d -> new DeviceTraffic(d, deviceViews.getOrDefault(d.name(), 0L)))
                .sorted(Comparator.comparingLong(DeviceTraffic::views).reversed())
                .toList();
        List<ReferrerTraffic> referrers = queries.referrers(currentStart, currentEnd, filter, TOP_REFERRERS).stream()
                .map(k -> new ReferrerTraffic(k.key() == null ? "" : k.key(), k.views()))
                .toList();

        return new TrafficReport(start, end, compareFrom, compareTo, current, previous, daily, hourly, topPages,
                topCountries, devices, referrers, records(today, tz));
    }

    // ------------------------------------------------------------------ parts

    private TrafficKpis kpis(Instant start, Instant end, ViewFilter filter) {
        Counts views = queries.viewCounts(start, end, filter);
        SideCounts side = queries.sideCounts(start, end);
        double rate = views.visitors() == 0 ? 0.0 : round1(side.paidOrders() * PERCENT / views.visitors());
        return new TrafficKpis(views.views(), views.visitors(), side.signups(), side.dreams(), side.paidOrders(),
                rate);
    }

    private TrafficRecords records(LocalDate today, String tz) {
        DayRecord bestDay = queries.bestDay(tz);
        MonthRecord bestMonth = queries.bestMonth(tz);
        Counts totals = queries.totals();
        MonthRank rank = queries.monthRank(YearMonth.from(today).toString(), tz);
        Integer thisMonthRank = rank.currentViews() == null ? null : (int) rank.better() + 1;
        return new TrafficRecords(
                bestDay == null ? null : new BestDay(bestDay.day(), bestDay.views()),
                bestMonth == null ? null : new BestMonth(bestMonth.month(), bestMonth.views()),
                totals.views(), totals.visitors(), thisMonthRank);
    }

    /** Validated filters; blank = none. Country is an ISO alpha-2 code, device one of {@link Device}. */
    static ViewFilter filter(String country, String device, String path) {
        String c = country == null ? "" : country.trim().toUpperCase(java.util.Locale.ROOT);
        if (!c.isEmpty() && !COUNTRY_CODE.matcher(c).matches()) {
            throw new ValidationException("country must be an ISO 3166 alpha-2 code", "INVALID_COUNTRY");
        }
        String d = device == null ? "" : device.trim().toUpperCase(java.util.Locale.ROOT);
        if (!d.isEmpty() && Arrays.stream(Device.values()).noneMatch(v -> v.name().equals(d))) {
            throw new ValidationException("Unknown device: " + device, "INVALID_DEVICE");
        }
        String p = path == null ? "" : path.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return new ViewFilter(c, d, p);
    }

    static String countryName(CountryCount c, Locale locale) {
        String name = locale == Locale.EN ? c.nameEn() : c.nameAr();
        return name == null || name.isBlank() ? c.code() : name;
    }

    private static Map<LocalDate, DayCount> byDay(List<DayCount> rows) {
        Map<LocalDate, DayCount> map = new HashMap<>();
        for (DayCount row : rows) {
            map.put(row.day(), row);
        }
        return map;
    }

    static Instant startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
