package com.saadat.admin.analytics;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Device;
import com.saadat.common.domain.Role;
import com.saadat.tracking.domain.PageView;
import com.saadat.tracking.repo.PageViewRepository;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/**
 * Seeds page views in March 2024 (no other test writes there) and checks the report for 2024-03-10..12 against the
 * comparison range 2024-03-07..09. Business time zone (schedule.time_zone) = Africa/Cairo = UTC+2 in March 2024.
 */
class TrafficIntegrationTest extends IntegrationTestBase {

    private static boolean seeded;

    @Autowired
    PageViewRepository pageViewRepository;

    @BeforeEach
    void seed() {
        if (seeded) {
            return;
        }
        List<PageView> rows = new ArrayList<>();
        // current range: 2024-03-10 (12:00 Cairo) session A ×2 (EG, desktop, google), session B (SA, mobile, direct)
        rows.add(view("/", "t-A", "EG", Device.DESKTOP, "google.com", "2024-03-10T10:00:00Z"));
        rows.add(view("/me/packages", "t-A", "EG", Device.DESKTOP, "google.com", "2024-03-10T10:05:00Z"));
        rows.add(view("/", "t-B", "SA", Device.MOBILE, null, "2024-03-10T10:10:00Z"));
        // 2024-03-12 20:30 Cairo: session C (EG, mobile, direct)
        rows.add(view("/", "t-C", "EG", Device.MOBILE, null, "2024-03-12T18:30:00Z"));
        // comparison range: 2024-03-07 one view, 2024-03-09 two views of one session
        rows.add(view("/", "t-D", "EG", Device.DESKTOP, null, "2024-03-07T09:00:00Z"));
        rows.add(view("/", "t-E", "SA", Device.MOBILE, null, "2024-03-09T09:00:00Z"));
        rows.add(view("/me", "t-E", "SA", Device.MOBILE, null, "2024-03-09T09:01:00Z"));
        pageViewRepository.saveAll(rows);
        seeded = true;
    }

    @Test
    void reportShapeAndCurrentVersusPreviousNumbers() throws Exception {
        User interpreter = createUser("traffic", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC)
                        .param("from", "2024-03-10").param("to", "2024-03-12")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2024-03-10"))
                .andExpect(jsonPath("$.to").value("2024-03-12"))
                .andExpect(jsonPath("$.compareFrom").value("2024-03-07"))
                .andExpect(jsonPath("$.compareTo").value("2024-03-09"))
                .andExpect(jsonPath("$.current.views").value(4))
                .andExpect(jsonPath("$.current.visitors").value(3))
                .andExpect(jsonPath("$.current.visits").value(3))
                .andExpect(jsonPath("$.current.signups").value(0))
                .andExpect(jsonPath("$.current.dreams").value(0))
                .andExpect(jsonPath("$.current.paidOrders").value(0))
                .andExpect(jsonPath("$.current.conversionRate").value(0.0))
                .andExpect(jsonPath("$.previous.views").value(3))
                .andExpect(jsonPath("$.previous.visitors").value(2))
                .andExpect(jsonPath("$.daily", hasSize(3)))
                .andExpect(jsonPath("$.daily[0].date").value("2024-03-10"))
                .andExpect(jsonPath("$.daily[0].views").value(3))
                .andExpect(jsonPath("$.daily[0].visitors").value(2))
                .andExpect(jsonPath("$.daily[0].visits").value(2))
                .andExpect(jsonPath("$.daily[0].previousViews").value(1))
                .andExpect(jsonPath("$.daily[1].views").value(0))
                .andExpect(jsonPath("$.daily[1].previousViews").value(0))
                .andExpect(jsonPath("$.daily[2].views").value(1))
                .andExpect(jsonPath("$.daily[2].previousViews").value(2))
                .andExpect(jsonPath("$.hourly", hasSize(24)))
                .andExpect(jsonPath("$.hourly[12].hour").value(12))
                .andExpect(jsonPath("$.hourly[12].views").value(3))
                .andExpect(jsonPath("$.hourly[20].views").value(1))
                .andExpect(jsonPath("$.hourly[0].dreams").value(0))
                .andExpect(jsonPath("$.topPages[0].path").value("/"))
                .andExpect(jsonPath("$.topPages[0].views").value(3))
                .andExpect(jsonPath("$.topPages[0].visitors").value(3))
                .andExpect(jsonPath("$.topPages[1].path").value("/me/packages"))
                .andExpect(jsonPath("$.topCountries[0].countryCode").value("EG"))
                .andExpect(jsonPath("$.topCountries[0].countryName").value("Egypt"))
                .andExpect(jsonPath("$.topCountries[0].views").value(3))
                .andExpect(jsonPath("$.topCountries[0].visitors").value(2))
                .andExpect(jsonPath("$.topCountries[1].countryCode").value("SA"))
                .andExpect(jsonPath("$.devices", hasSize(3)))
                .andExpect(jsonPath("$.devices[?(@.device == 'MOBILE')].views").value(hasItem(2)))
                .andExpect(jsonPath("$.devices[?(@.device == 'DESKTOP')].views").value(hasItem(2)))
                .andExpect(jsonPath("$.devices[?(@.device == 'TABLET')].views").value(hasItem(0)))
                .andExpect(jsonPath("$.referrers[?(@.host == '')].views").value(hasItem(2)))
                .andExpect(jsonPath("$.referrers[?(@.host == 'google.com')].views").value(hasItem(2)))
                .andExpect(jsonPath("$.records.totalViews").value(greaterThanOrEqualTo(7)))
                .andExpect(jsonPath("$.records.totalVisitors").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.records.totalVisits").value(greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.records.bestDay.date").isNotEmpty())
                .andExpect(jsonPath("$.records.bestMonth.month").isNotEmpty());
    }

    @Test
    void filtersApplyToPageViewsAndCountryNamesFollowTheLanguage() throws Exception {
        User interpreter = createUser("traffic", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC)
                        .param("from", "2024-03-10").param("to", "2024-03-12").param("country", "eg")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.views").value(3))
                .andExpect(jsonPath("$.current.visitors").value(2))
                .andExpect(jsonPath("$.previous.views").value(1))
                .andExpect(jsonPath("$.topCountries", hasSize(1)))
                .andExpect(jsonPath("$.topCountries[0].countryName").value("مصر"));

        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC)
                        .param("from", "2024-03-10").param("to", "2024-03-12").param("device", "MOBILE")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.views").value(2))
                .andExpect(jsonPath("$.previous.views").value(2));

        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC)
                        .param("from", "2024-03-10").param("to", "2024-03-12").param("path", "/me/packages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.views").value(1))
                .andExpect(jsonPath("$.topPages", hasSize(1)));
    }

    @Test
    void oneBrowserComingBackIsOneVisitorWithSeveralVisits() throws Exception {
        // 2023-06-15 (no other test writes there): browser V1 comes three times (three visits), browser V2 once
        pageViewRepository.saveAll(List.of(
                view("/", "t-v1-a", "V1", "SA", Device.MOBILE, "2023-06-15T08:00:00Z"),
                view("/terms", "t-v1-a", "V1", "SA", Device.MOBILE, "2023-06-15T08:02:00Z"),
                view("/", "t-v1-b", "V1", "SA", Device.MOBILE, "2023-06-15T12:00:00Z"),
                view("/", "t-v1-c", "V1", "SA", Device.MOBILE, "2023-06-15T18:00:00Z"),
                view("/", "t-v2-a", "V2", "SA", Device.DESKTOP, "2023-06-15T09:00:00Z")));
        User interpreter = createUser("traffic", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC)
                        .param("from", "2023-06-15").param("to", "2023-06-15")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.views").value(5))
                .andExpect(jsonPath("$.current.visitors").value(2))
                .andExpect(jsonPath("$.current.visits").value(4))
                .andExpect(jsonPath("$.daily[0].visitors").value(2))
                .andExpect(jsonPath("$.daily[0].visits").value(4))
                .andExpect(jsonPath("$.topPages[0].path").value("/"))
                .andExpect(jsonPath("$.topPages[0].visitors").value(2))
                .andExpect(jsonPath("$.topCountries[0].countryCode").value("SA"))
                .andExpect(jsonPath("$.topCountries[0].visitors").value(2));
    }

    @Test
    void topCountriesCountPageVisitsNotSignIns() throws Exception {
        // Oman (no other test uses it), within the last 7 days: one browser, six visits, the first of two pages
        List<PageView> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            rows.add(view("/", "t-om-" + i, "OMV1", "OM", Device.MOBILE,
                    Instant.now().minusSeconds(3600L * (i + 1)).toString()));
        }
        rows.add(view("/terms", "t-om-0", "OMV1", "OM", Device.MOBILE, Instant.now().minusSeconds(3500).toString()));
        pageViewRepository.saveAll(rows);
        User interpreter = createUser("traffic", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_COUNTRIES).param("period", "7d")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topVisits[?(@.countryCode == 'OM')].visits").value(hasItem(6)));
    }

    @Test
    void defaultRangeIsTheCurrentMonthAndBadInputIsRejected() throws Exception {
        User interpreter = createUser("traffic", Role.INTERPRETER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC).header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value(org.hamcrest.Matchers.endsWith("-01")))
                .andExpect(jsonPath("$.hourly", hasSize(24)))
                .andExpect(jsonPath("$.records").exists());

        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC).param("from", "2024-03-12").param("to", "2024-03-10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC).param("device", "WATCH")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void usersCannotReadAnalytics() throws Exception {
        User user = createUser("plain", Role.USER);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_TRAFFIC).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_INSIGHTS).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }

    private static PageView view(String path, String session, String country, Device device, String referrer,
                                 String at) {
        PageView v = new PageView();
        v.setPath(path);
        v.setSessionId(session);
        v.setCountryCode(country);
        v.setDevice(device);
        v.setReferrerHost(referrer);
        v.setCreatedAt(Instant.parse(at));
        return v;
    }

    private static PageView view(String path, String session, String visitor, String country, Device device,
                                 String at) {
        PageView v = view(path, session, country, device, null, at);
        v.setVisitorId(visitor);
        return v;
    }
}
