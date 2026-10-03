package com.saadat.admin.analytics;

import com.saadat.common.domain.Device;
import java.time.LocalDate;
import java.util.List;

/** GET /admin/analytics/traffic shapes (docs/ANALYTICS_REPORTS_CONTRACT.md §2). Dates are business-zone days. */
public final class TrafficDtos {

    private TrafficDtos() {
    }

    /**
     * visitors = distinct browsers (one person coming back counts once), visits = distinct sessions (a new visit
     * starts after 30 minutes without a page view). conversionRate = paidOrders / visitors × 100, one decimal.
     */
    public record TrafficKpis(long views, long visitors, long visits, long signups, long dreams, long paidOrders,
                              double conversionRate) {
    }

    /** previousViews = views on the aligned day of the comparison range. */
    public record DailyTraffic(LocalDate date, long views, long visitors, long visits, long previousViews) {
    }

    public record HourlyTraffic(int hour, long views, long dreams) {
    }

    public record PageTraffic(String path, long views, long visitors) {
    }

    public record CountryTraffic(String countryCode, String countryName, long views, long visitors) {
    }

    public record DeviceTraffic(Device device, long views) {
    }

    /** host '' = direct. */
    public record ReferrerTraffic(String host, long views) {
    }

    public record BestDay(LocalDate date, long views) {
    }

    /** month = YYYY-MM. */
    public record BestMonth(String month, long views) {
    }

    /** All-time records; thisMonthRank: 1 = the current month is the best month ever (null when it has no views). */
    public record TrafficRecords(BestDay bestDay, BestMonth bestMonth, long totalViews, long totalVisitors,
                                 long totalVisits, Integer thisMonthRank) {
    }

    public record TrafficReport(
            LocalDate from,
            LocalDate to,
            LocalDate compareFrom,
            LocalDate compareTo,
            TrafficKpis current,
            TrafficKpis previous,
            List<DailyTraffic> daily,
            List<HourlyTraffic> hourly,
            List<PageTraffic> topPages,
            List<CountryTraffic> topCountries,
            List<DeviceTraffic> devices,
            List<ReferrerTraffic> referrers,
            TrafficRecords records) {
    }
}
