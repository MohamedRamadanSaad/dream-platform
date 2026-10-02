package com.saadat.admin.analytics;

import com.saadat.admin.analytics.InsightsDtos.InsightsResponse;
import com.saadat.admin.analytics.TrafficDtos.TrafficReport;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Traffic analytics and insights for the interpreter (ROLE_INTERPRETER via SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class AdminTrafficController {

    private final TrafficService trafficService;
    private final InsightsService insightsService;

    @GetMapping(ApiPaths.Admin.ANALYTICS_TRAFFIC)
    public TrafficReport traffic(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String path,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return trafficService.report(from, to, country, device, path, Locale.fromTag(acceptLanguage));
    }

    @GetMapping(ApiPaths.Admin.ANALYTICS_INSIGHTS)
    public InsightsResponse insights(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return insightsService.insights(Locale.fromTag(acceptLanguage));
    }
}
