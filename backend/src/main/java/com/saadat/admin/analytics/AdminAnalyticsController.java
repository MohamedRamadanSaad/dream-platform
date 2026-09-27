package com.saadat.admin.analytics;

import com.saadat.admin.analytics.AnalyticsDtos.AdminCountryDashboardDto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminSummaryDto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminUser360Dto;
import com.saadat.admin.analytics.AnalyticsDtos.AdminUserRowDto;
import com.saadat.admin.analytics.AnalyticsDtos.CreditAdjustRequest;
import com.saadat.admin.analytics.AnalyticsDtos.UserNotesRequest;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.domain.Locale;
import com.saadat.common.security.AuthPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Interpreter analytics and user 360 (ROLE_INTERPRETER via SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AnalyticsService analyticsService;
    private final AdminUserService adminUserService;

    @GetMapping(ApiPaths.Admin.ANALYTICS_SUMMARY)
    public AdminSummaryDto summary() {
        return analyticsService.summary();
    }

    @GetMapping(ApiPaths.Admin.ANALYTICS_COUNTRIES)
    public AdminCountryDashboardDto countries(@RequestParam(required = false) String period,
                                              @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false)
                                              String acceptLanguage) {
        return analyticsService.countries(period, Locale.fromTag(acceptLanguage));
    }

    @GetMapping(ApiPaths.Admin.ANALYTICS_USERS)
    public PageResponse<AdminUserRowDto> users(@RequestParam(required = false) String list,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return analyticsService.users(list, q, page, size);
    }

    @GetMapping(ApiPaths.Admin.USER)
    public AdminUser360Dto user(@PathVariable UUID id) {
        return adminUserService.user360(id);
    }

    @PutMapping(ApiPaths.Admin.USER_NOTES)
    public ResponseEntity<Void> notes(@PathVariable UUID id, @RequestBody UserNotesRequest body) {
        adminUserService.saveNotes(AuthPrincipal.current().userId(), id, body.notes(), body.tags());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Admin.USER_CREDITS)
    public ResponseEntity<Void> credits(@PathVariable UUID id, @Valid @RequestBody CreditAdjustRequest body) {
        adminUserService.adjustCredits(AuthPrincipal.current().userId(), id, body.delta(), body.reason());
        return ResponseEntity.noContent().build();
    }
}
