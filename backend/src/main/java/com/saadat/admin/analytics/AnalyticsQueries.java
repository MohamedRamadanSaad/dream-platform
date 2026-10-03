package com.saadat.admin.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Aggregates for the traffic report and the insights (native PostgreSQL, grouped in the database — rows are never
 * loaded into memory). Day/hour buckets use {@code AT TIME ZONE :tz} (the business time zone); ranges are
 * half-open {@code [start, end)}. Grouping uses ordinals ({@code group by 1}) because a bound parameter repeated in
 * GROUP BY is a different parameter for PostgreSQL.
 */
@Repository
public class AnalyticsQueries {

    /** Filters of the page-view metrics; an empty string means "no filter". */
    public record ViewFilter(String country, String device, String path) {
        public static final ViewFilter NONE = new ViewFilter("", "", "");
    }

    /** visitors = distinct browsers (visitor id cookie), visits = distinct sessions (30 minutes of inactivity). */
    public record Counts(long views, long visitors, long visits) {
    }

    public record DayCount(LocalDate day, long views, long visitors, long visits) {
    }

    public record KeyCount(String key, long views, long visitors) {
    }

    public record CountryCount(String code, String nameAr, String nameEn, long views, long visitors) {
    }

    public record SideCounts(long signups, long dreams, long paidOrders) {
    }

    public record DayRecord(LocalDate day, long views) {
    }

    public record MonthRecord(String month, long views) {
    }

    public record MonthRank(Long currentViews, long better) {
    }

    public record OnTime(long total, long onTime) {
    }

    /**
     * One visitor = one browser: the long-lived visitor id cookie. Rows stored before that cookie existed have no
     * visitor id, so each of their sessions counts as its own visitor.
     */
    private static final String VISITOR = "coalesce(pv.visitor_id, pv.session_id)";

    private static final String VIEWS_IN_RANGE = """
            pv.created_at >= :start and pv.created_at < :end
              and (:country = '' or pv.country_code = :country)
              and (:device = '' or pv.device = :device)
              and (:path = '' or pv.path = :path)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ================================================================== page views (filtered)

    public Counts viewCounts(Instant start, Instant end, ViewFilter filter) {
        String sql = "select count(*) as views, count(distinct " + VISITOR + ") as visitors, "
                + "count(distinct pv.session_id) as visits from page_views pv where " + VIEWS_IN_RANGE;
        return jdbc.queryForObject(sql, views(start, end, filter),
                (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    public List<DayCount> viewsByDay(Instant start, Instant end, ViewFilter filter, String tz) {
        String sql = "select cast(pv.created_at at time zone :tz as date) as d, count(*) as views, "
                + "count(distinct " + VISITOR + ") as visitors, count(distinct pv.session_id) as visits "
                + "from page_views pv where " + VIEWS_IN_RANGE + " group by 1";
        return jdbc.query(sql, views(start, end, filter).addValue("tz", tz),
                (rs, i) -> new DayCount(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4)));
    }

    /** views per hour of day (0..23) as an array of 24. */
    public long[] viewsByHour(Instant start, Instant end, ViewFilter filter, String tz) {
        String sql = "select cast(extract(hour from pv.created_at at time zone :tz) as int) as h, count(*) "
                + "from page_views pv where " + VIEWS_IN_RANGE + " group by 1";
        return buckets(sql, views(start, end, filter).addValue("tz", tz), 24, 0);
    }

    /** views per ISO day of week (index 0 = Monday … 6 = Sunday). */
    public long[] viewsByWeekday(Instant start, Instant end, String tz) {
        String sql = "select cast(extract(isodow from pv.created_at at time zone :tz) as int) as d, count(*) "
                + "from page_views pv where " + VIEWS_IN_RANGE + " group by 1";
        return buckets(sql, views(start, end, ViewFilter.NONE).addValue("tz", tz), 7, 1);
    }

    public List<KeyCount> topPages(Instant start, Instant end, ViewFilter filter, int limit) {
        String sql = "select pv.path, count(*) as views, count(distinct " + VISITOR + ") as visitors "
                + "from page_views pv where " + VIEWS_IN_RANGE
                + " group by pv.path order by views desc, pv.path asc limit :limit";
        return jdbc.query(sql, views(start, end, filter).addValue("limit", limit),
                (rs, i) -> new KeyCount(rs.getString(1), rs.getLong(2), rs.getLong(3)));
    }

    public List<CountryCount> topCountries(Instant start, Instant end, ViewFilter filter, int limit) {
        String sql = "select pv.country_code, max(c.name_ar), max(c.name_en), count(*) as views, "
                + "count(distinct " + VISITOR + ") as visitors "
                + "from page_views pv left join countries c on c.code = pv.country_code where " + VIEWS_IN_RANGE
                + " and pv.country_code is not null "
                + "group by pv.country_code order by views desc, pv.country_code asc limit :limit";
        return jdbc.query(sql, views(start, end, filter).addValue("limit", limit),
                (rs, i) -> new CountryCount(trim(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getLong(5)));
    }

    public List<KeyCount> devices(Instant start, Instant end, ViewFilter filter) {
        String sql = "select pv.device, count(*) as views from page_views pv where " + VIEWS_IN_RANGE
                + " group by pv.device";
        return jdbc.query(sql, views(start, end, filter), (rs, i) -> new KeyCount(rs.getString(1), rs.getLong(2), 0));
    }

    /** Referrer hosts; '' = direct. */
    public List<KeyCount> referrers(Instant start, Instant end, ViewFilter filter, int limit) {
        String sql = "select coalesce(pv.referrer_host, '') as host, count(*) as views from page_views pv where "
                + VIEWS_IN_RANGE + " group by 1 order by views desc, host asc limit :limit";
        return jdbc.query(sql, views(start, end, filter).addValue("limit", limit),
                (rs, i) -> new KeyCount(rs.getString(1), rs.getLong(2), 0));
    }

    /**
     * [countryCode, visits] since {@code start}: distinct visits (sessions) of the site's pages, the same "visits"
     * as the traffic report. Views without a known country are left out.
     */
    public List<Object[]> visitsByCountrySince(Instant start) {
        String sql = "select pv.country_code, count(distinct pv.session_id) from page_views pv "
                + "where pv.created_at >= :start and pv.country_code is not null group by pv.country_code";
        return jdbc.query(sql, new MapSqlParameterSource("start", ts(start)),
                (rs, i) -> new Object[]{trim(rs.getString(1)), rs.getLong(2)});
    }

    /** The top country by views since {@code start} (null when no view has a country). */
    public CountryCount topCountrySince(Instant start, Instant end) {
        List<CountryCount> top = topCountries(start, end, ViewFilter.NONE, 1);
        return top.isEmpty() ? null : top.get(0);
    }

    // ================================================================== records (all time, unfiltered)

    public DayRecord bestDay(String tz) {
        String sql = "select cast(pv.created_at at time zone :tz as date) as d, count(*) as views from page_views pv "
                + "group by 1 order by views desc, d asc limit 1";
        List<DayRecord> rows = jdbc.query(sql, new MapSqlParameterSource("tz", tz),
                (rs, i) -> new DayRecord(rs.getObject(1, LocalDate.class), rs.getLong(2)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MonthRecord bestMonth(String tz) {
        String sql = "select to_char(pv.created_at at time zone :tz, 'YYYY-MM') as m, count(*) as views "
                + "from page_views pv group by 1 order by views desc, m asc limit 1";
        List<MonthRecord> rows = jdbc.query(sql, new MapSqlParameterSource("tz", tz),
                (rs, i) -> new MonthRecord(rs.getString(1), rs.getLong(2)));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Counts totals() {
        String sql = "select count(*), count(distinct " + VISITOR + "), count(distinct pv.session_id) "
                + "from page_views pv";
        return jdbc.queryForObject(sql, new MapSqlParameterSource(),
                (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** Views of {@code month} (YYYY-MM, null when none) and the number of months with more views. */
    public MonthRank monthRank(String month, String tz) {
        String sql = """
                with months as (
                    select to_char(pv.created_at at time zone :tz, 'YYYY-MM') as m, count(*) as views
                    from page_views pv group by 1)
                select (select views from months where m = :month) as current_views,
                       (select count(*) from months
                         where views > coalesce((select views from months where m = :month), 0)) as better
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("tz", tz).addValue("month", month),
                (rs, i) -> {
                    long current = rs.getLong(1);
                    Long currentViews = rs.wasNull() ? null : current;
                    return new MonthRank(currentViews, rs.getLong(2));
                });
    }

    // ================================================================== other tables (never filtered)

    public SideCounts sideCounts(Instant start, Instant end) {
        String sql = """
                select (select count(*) from users u
                         where u.role = 'USER' and u.created_at >= :start and u.created_at < :end) as signups,
                       (select count(*) from dreams d
                         where d.submitted_at >= :start and d.submitted_at < :end) as dreams,
                       (select count(*) from orders o
                         where o.status = 'SUCCESS' and o.paid_at >= :start and o.paid_at < :end) as paid
                """;
        return jdbc.queryForObject(sql, range(start, end),
                (rs, i) -> new SideCounts(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
    }

    /** dreams submitted per hour of day (0..23). */
    public long[] dreamsByHour(Instant start, Instant end, String tz) {
        String sql = "select cast(extract(hour from d.submitted_at at time zone :tz) as int) as h, count(*) "
                + "from dreams d where d.submitted_at >= :start and d.submitted_at < :end group by 1";
        return buckets(sql, range(start, end).addValue("tz", tz), 24, 0);
    }

    /** dreams submitted per ISO day of week (index 0 = Monday … 6 = Sunday). */
    public long[] dreamsByWeekday(Instant start, Instant end, String tz) {
        String sql = "select cast(extract(isodow from d.submitted_at at time zone :tz) as int) as w, count(*) "
                + "from dreams d where d.submitted_at >= :start and d.submitted_at < :end group by 1";
        return buckets(sql, range(start, end).addValue("tz", tz), 7, 1);
    }

    /** interpretations per hour of day (0..23). */
    public long[] interpretationsByHour(Instant start, Instant end, String tz) {
        String sql = "select cast(extract(hour from d.interpreted_at at time zone :tz) as int) as h, count(*) "
                + "from dreams d where d.status = 'INTERPRETED' and d.interpreted_at >= :start "
                + "and d.interpreted_at < :end group by 1";
        return buckets(sql, range(start, end).addValue("tz", tz), 24, 0);
    }

    public long interpretedBetween(Instant start, Instant end) {
        String sql = "select count(*) from dreams d where d.status = 'INTERPRETED' "
                + "and d.interpreted_at >= :start and d.interpreted_at < :end";
        Long n = jdbc.queryForObject(sql, range(start, end), Long.class);
        return n == null ? 0 : n;
    }

    /** Interpreted since {@code start}: total and how many before their expected time. */
    public OnTime onTime(Instant start, Instant end) {
        String sql = "select count(*), count(*) filter (where d.expected_by is not null "
                + "and d.interpreted_at <= d.expected_by) from dreams d where d.status = 'INTERPRETED' "
                + "and d.interpreted_at >= :start and d.interpreted_at < :end";
        return jdbc.queryForObject(sql, range(start, end), (rs, i) -> new OnTime(rs.getLong(1), rs.getLong(2)));
    }

    /** Distinct local dates with at least one interpretation since {@code start}, newest first. */
    public List<LocalDate> interpretationDays(Instant start, String tz) {
        String sql = "select distinct cast(d.interpreted_at at time zone :tz as date) as local_day from dreams d "
                + "where d.status = 'INTERPRETED' and d.interpreted_at >= :start order by local_day desc";
        return jdbc.query(sql, new MapSqlParameterSource("tz", tz).addValue("start", ts(start)),
                (rs, i) -> rs.getObject(1, LocalDate.class));
    }

    public long awaitingReplySince(Instant pausedBefore) {
        String sql = "select count(*) from dreams d where d.status = 'AWAITING_USER_REPLY' "
                + "and d.sla_paused_at < :before";
        Long n = jdbc.queryForObject(sql, new MapSqlParameterSource("before", ts(pausedBefore)), Long.class);
        return n == null ? 0 : n;
    }

    public long pendingTestimonials() {
        Long n = jdbc.queryForObject("select count(*) from testimonials t where t.approved = false",
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    public long paidOrdersInCountry(String countryCode, Instant start, Instant end) {
        String sql = "select count(*) from orders o where o.status = 'SUCCESS' and o.paid_at >= :start "
                + "and o.paid_at < :end and o.country_code = :country";
        Long n = jdbc.queryForObject(sql, range(start, end).addValue("country", countryCode), Long.class);
        return n == null ? 0 : n;
    }

    // ================================================================== helpers

    private long[] buckets(String sql, MapSqlParameterSource params, int size, int firstKey) {
        long[] out = new long[size];
        List<long[]> rows = new ArrayList<>();
        RowCallbackHandler collect = rs -> rows.add(new long[]{rs.getInt(1), rs.getLong(2)});
        jdbc.query(sql, params, collect);
        for (long[] row : rows) {
            int index = (int) row[0] - firstKey;
            if (index >= 0 && index < size) {
                out[index] += row[1];
            }
        }
        return out;
    }

    private static MapSqlParameterSource views(Instant start, Instant end, ViewFilter filter) {
        ViewFilter f = filter == null ? ViewFilter.NONE : filter;
        return range(start, end)
                .addValue("country", f.country())
                .addValue("device", f.device())
                .addValue("path", f.path());
    }

    private static MapSqlParameterSource range(Instant start, Instant end) {
        return new MapSqlParameterSource().addValue("start", ts(start)).addValue("end", ts(end));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
