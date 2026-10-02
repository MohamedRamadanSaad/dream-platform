package com.saadat.admin.analytics;

import com.saadat.admin.analytics.AnalyticsQueries.CountryCount;
import com.saadat.admin.analytics.AnalyticsQueries.OnTime;
import com.saadat.admin.analytics.AnalyticsQueries.ViewFilter;
import com.saadat.admin.analytics.InsightsDtos.Insight;
import com.saadat.admin.analytics.InsightsDtos.InsightKind;
import com.saadat.admin.analytics.InsightsDtos.InsightsResponse;
import com.saadat.admin.analytics.InsightsDtos.MyActivity;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Locale;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.mail.FrontendPaths;
import com.saadat.mail.MessageText;
import com.saadat.publicapi.WaitTimeView;
import com.saadat.settings.BusinessZone;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /admin/analytics/insights (contract §3): the interpreter's own activity plus rule-based insights. Texts come
 * from messages {@code insight.<rule>.title/.body} in the requested locale; thresholds are settings
 * ({@code insights.*}, {@code wait.*}). Items are sorted WARNING, TIP, SUCCESS, INFO (rule order inside a kind),
 * at most {@link #MAX_ITEMS}. The product has a single interpreter, so "my" activity counts every interpretation.
 */
@Service
@RequiredArgsConstructor
public class InsightsService {

    static final int MAX_ITEMS = 8;
    static final int TOP_HOURS = 3;
    static final int HOURS_PER_DAY = 24;
    static final double PERCENT = 100.0;
    static final double HALF = 2.0;
    /** Windows defined by the contract. */
    static final Duration RESPONSE_WINDOW = Duration.ofDays(30);
    static final Duration BUSIEST_WINDOW = Duration.ofDays(60);
    static final Duration PEAK_WINDOW = Duration.ofDays(30);
    static final Duration COUNTRY_WINDOW = Duration.ofDays(30);
    static final int OUTPUT_WINDOW_DAYS = 7;
    static final int STREAK_LOOKBACK_DAYS = 400;

    static final String TITLE_KEY = "insight.%s.title";
    static final String BODY_KEY = "insight.%s.body";

    // rule ids = Insight.id and the message key segment
    static final String OVERDUE = "overdue";
    static final String AWAITING_REPLY = "awaiting-reply";
    static final String BUSY_ON = "busy-on";
    static final String BUSY_OFF = "busy-off";
    static final String TESTIMONIALS_PENDING = "testimonials-pending";
    static final String TRAFFIC_UP = "traffic-up";
    static final String TRAFFIC_DOWN = "traffic-down";
    static final String PEAK_HOURS = "peak-hours";
    static final String YOUTUBE_TIME = "youtube-time";
    static final String COUNTRY_PRICE = "country-price";
    static final String FAST_RESPONSE = "fast-response";
    static final String STREAK = "streak";
    static final String INTERPRETED_UP = "interpreted-up";

    private final AnalyticsQueries queries;
    private final DreamRepository dreamRepository;
    private final SettingsService settings;
    private final BusinessZone businessZone;
    private final WaitTimeView waitTimeView;
    private final MessageText messageText;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InsightsResponse insights(Locale locale) {
        Instant now = clock.instant();
        ZoneId zone = businessZone.zone();
        String tz = zone.getId();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate monthFirst = today.withDayOfMonth(1);
        LocalDate lastMonthFirst = monthFirst.minusMonths(1);
        Instant monthStart = TrafficService.startOf(monthFirst, zone);
        Instant nextMonthStart = TrafficService.startOf(monthFirst.plusMonths(1), zone);
        Instant lastMonthStart = TrafficService.startOf(lastMonthFirst, zone);
        Instant tomorrow = TrafficService.startOf(today.plusDays(1), zone);

        // ------------------------------------------------------------ my activity
        long interpretedThisMonth = queries.interpretedBetween(monthStart, nextMonthStart);
        long interpretedLastMonth = queries.interpretedBetween(lastMonthStart, monthStart);
        Instant responseSince = now.minus(RESPONSE_WINDOW);
        OnTime onTime = queries.onTime(responseSince, tomorrow);
        double avgResponseHours = TrafficService.round1(dreamRepository.averageResponseHoursSince(responseSince));
        boolean busy = waitTimeView.isBusy();
        int normalHours = settings.getInt(SettingKeys.WAIT_NORMAL_HOURS);
        int slaHours = busy ? settings.getInt(SettingKeys.WAIT_BUSY_MAX_DAYS) * HOURS_PER_DAY : normalHours;
        double onTimeRate = onTime.total() == 0 ? 0.0
                : TrafficService.round1(onTime.onTime() * PERCENT / onTime.total());
        List<Integer> myBusiestHours = topHours(queries.interpretationsByHour(now.minus(BUSIEST_WINDOW), tomorrow, tz));
        Instant peakSince = now.minus(PEAK_WINDOW);
        List<Integer> usersPeakHours = topHours(sum(
                queries.viewsByHour(peakSince, tomorrow, ViewFilter.NONE, tz),
                queries.dreamsByHour(peakSince, tomorrow, tz)));
        int streakDays = streak(queries.interpretationDays(
                TrafficService.startOf(today.minusDays(STREAK_LOOKBACK_DAYS), zone), tz), today);
        MyActivity myActivity = new MyActivity(interpretedThisMonth, interpretedLastMonth, avgResponseHours, slaHours,
                onTimeRate, myBusiestHours, usersPeakHours, streakDays);

        // ------------------------------------------------------------ rules
        List<Insight> items = new ArrayList<>();

        long overdue = dreamRepository.countByStatusAndExpectedByBefore(DreamStatus.IN_REVIEW, now);
        if (overdue > 0) {
            items.add(insight(OVERDUE, InsightKind.WARNING, FrontendPaths.ADMIN_QUEUE, Map.of("count", overdue), locale));
        }

        int awaitingDays = settings.getInt(SettingKeys.INSIGHTS_AWAITING_REPLY_DAYS);
        long awaiting = queries.awaitingReplySince(now.minus(Duration.ofDays(awaitingDays)));
        if (awaiting > 0) {
            items.add(insight(AWAITING_REPLY, InsightKind.INFO, null,
                    Map.of("count", awaiting, "days", awaitingDays), locale));
        }

        long queue = dreamRepository.countByStatus(DreamStatus.IN_REVIEW);
        double dailyOutput = queries.interpretedBetween(now.minus(Duration.ofDays(OUTPUT_WINDOW_DAYS)), tomorrow)
                / (double) OUTPUT_WINDOW_DAYS;
        if (!busy && queue > 0 && queue > dailyOutput * slaHours / HOURS_PER_DAY) {
            items.add(insight(BUSY_ON, InsightKind.TIP, FrontendPaths.ADMIN_WAIT_TIME, Map.of("count", queue), locale));
        }
        if (busy && queue <= dailyOutput * normalHours / HOURS_PER_DAY) {
            items.add(insight(BUSY_OFF, InsightKind.TIP, FrontendPaths.ADMIN_WAIT_TIME, Map.of("count", queue), locale));
        }

        long pending = queries.pendingTestimonials();
        if (pending > 0) {
            items.add(insight(TESTIMONIALS_PENDING, InsightKind.TIP, FrontendPaths.ADMIN_TESTIMONIALS,
                    Map.of("count", pending), locale));
        }

        int sameDays = Math.min(today.getDayOfMonth(), lastMonthFirst.lengthOfMonth());
        long visitsNow = queries.viewCounts(monthStart, tomorrow, ViewFilter.NONE).visitors();
        long visitsBefore = queries.viewCounts(lastMonthStart,
                TrafficService.startOf(lastMonthFirst.plusDays(sameDays), zone), ViewFilter.NONE).visitors();
        if (visitsBefore > 0) {
            double change = (visitsNow - visitsBefore) * PERCENT / visitsBefore;
            int threshold = settings.getInt(SettingKeys.INSIGHTS_TRAFFIC_CHANGE_PERCENT);
            if (change >= threshold) {
                items.add(insight(TRAFFIC_UP, InsightKind.SUCCESS, null, Map.of("percent", Math.round(change)), locale));
            } else if (change <= -threshold) {
                items.add(insight(TRAFFIC_DOWN, InsightKind.WARNING, null, Map.of("percent", Math.round(-change)),
                        locale));
            }
        }

        if (!usersPeakHours.isEmpty() && !myBusiestHours.isEmpty() && !myBusiestHours.contains(usersPeakHours.get(0))) {
            items.add(insight(PEAK_HOURS, InsightKind.TIP, null, Map.of("hour", hour(usersPeakHours.get(0))), locale));
        }

        DayOfWeek peakDay = peakWeekday(sum(queries.viewsByWeekday(peakSince, tomorrow, tz),
                queries.dreamsByWeekday(peakSince, tomorrow, tz)));
        if (!usersPeakHours.isEmpty() && peakDay != null) {
            items.add(insight(YOUTUBE_TIME, InsightKind.TIP, null, Map.of(
                    "hour", hour(usersPeakHours.get(0)),
                    "day", peakDay.getDisplayName(TextStyle.FULL, MessageText.toJava(locale))), locale));
        }

        Instant countrySince = now.minus(COUNTRY_WINDOW);
        CountryCount topCountry = queries.topCountrySince(countrySince, tomorrow);
        if (topCountry != null && topCountry.views() > 0
                && queries.paidOrdersInCountry(topCountry.code(), countrySince, tomorrow) == 0) {
            items.add(insight(COUNTRY_PRICE, InsightKind.TIP, FrontendPaths.ADMIN_PRICING,
                    Map.of("country", TrafficService.countryName(topCountry, locale)), locale));
        }

        if (onTime.total() > 0 && avgResponseHours < slaHours / HALF) {
            items.add(insight(FAST_RESPONSE, InsightKind.SUCCESS, null,
                    Map.of("hours", plain(avgResponseHours), "sla", slaHours), locale));
        }

        if (streakDays >= settings.getInt(SettingKeys.INSIGHTS_STREAK_MIN_DAYS)) {
            items.add(insight(STREAK, InsightKind.SUCCESS, null, Map.of("days", streakDays), locale));
        }

        if (interpretedThisMonth > interpretedLastMonth) {
            items.add(insight(INTERPRETED_UP, InsightKind.SUCCESS, null,
                    Map.of("count", interpretedThisMonth, "previous", interpretedLastMonth), locale));
        }

        items.sort(Comparator.comparingInt(i -> i.kind().ordinal()));
        List<Insight> limited = items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
        return new InsightsResponse(List.copyOf(limited), myActivity);
    }

    // ------------------------------------------------------------------ helpers

    private Insight insight(String id, InsightKind kind, String link, Map<String, ?> args, Locale locale) {
        return new Insight(id, kind,
                messageText.get(String.format(TITLE_KEY, id), locale, args),
                messageText.get(String.format(BODY_KEY, id), locale, args),
                link);
    }

    /** Hours with activity, most active first (ties: earlier hour first), at most {@link #TOP_HOURS}. */
    static List<Integer> topHours(long[] counts) {
        List<Integer> hours = new ArrayList<>();
        for (int h = 0; h < counts.length; h++) {
            if (counts[h] > 0) {
                hours.add(h);
            }
        }
        hours.sort(Comparator.comparingLong((Integer h) -> counts[h]).reversed());
        return List.copyOf(hours.subList(0, Math.min(TOP_HOURS, hours.size())));
    }

    /** The busiest ISO weekday (index 0 = Monday), null without activity. */
    static DayOfWeek peakWeekday(long[] counts) {
        int best = -1;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0 && (best < 0 || counts[i] > counts[best])) {
                best = i;
            }
        }
        return best < 0 ? null : DayOfWeek.of(best + 1);
    }

    /** Consecutive days with an interpretation, ending today or yesterday. */
    static int streak(List<LocalDate> daysNewestFirst, LocalDate today) {
        if (daysNewestFirst.isEmpty()) {
            return 0;
        }
        LocalDate expected = daysNewestFirst.get(0);
        if (!expected.equals(today) && !expected.equals(today.minusDays(1))) {
            return 0;
        }
        int days = 0;
        for (LocalDate day : daysNewestFirst) {
            if (!day.equals(expected)) {
                break;
            }
            days++;
            expected = expected.minusDays(1);
        }
        return days;
    }

    static long[] sum(long[] a, long[] b) {
        long[] out = new long[Math.max(a.length, b.length)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (i < a.length ? a[i] : 0) + (i < b.length ? b[i] : 0);
        }
        return out;
    }

    /** "21:00". */
    static String hour(int hour) {
        return String.format(java.util.Locale.ROOT, "%02d:00", hour);
    }

    /** 5.5 → "5.5", 24.0 → "24". */
    static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
