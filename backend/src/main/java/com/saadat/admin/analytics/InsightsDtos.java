package com.saadat.admin.analytics;

import java.util.List;

/** GET /admin/analytics/insights shapes (docs/ANALYTICS_REPORTS_CONTRACT.md §3). */
public final class InsightsDtos {

    private InsightsDtos() {
    }

    /** Declared in display order: items are sorted WARNING, TIP, SUCCESS, INFO. */
    public enum InsightKind {
        WARNING,
        TIP,
        SUCCESS,
        INFO
    }

    /** {@code link} is a frontend route (e.g. /admin/queue) or null. */
    public record Insight(String id, InsightKind kind, String title, String body, String link) {
    }

    public record MyActivity(
            long interpretedThisMonth,
            long interpretedLastMonth,
            double avgResponseHours,
            int slaHours,
            double onTimeRate,
            List<Integer> myBusiestHours,
            List<Integer> usersPeakHours,
            int streakDays) {
    }

    public record InsightsResponse(List<Insight> items, MyActivity myActivity) {
    }
}
