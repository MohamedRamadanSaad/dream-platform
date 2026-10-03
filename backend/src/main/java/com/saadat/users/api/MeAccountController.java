package com.saadat.users.api;

import com.saadat.auth.web.CookieUtil;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.users.api.MeDtos.DashboardSummary;
import com.saadat.users.api.MeDtos.PreferencesRequest;
import com.saadat.users.service.AccountService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@link ApiPaths.Me} routes owned by the users package (credits/orders live in payments/credits). */
@RestController
public class MeAccountController {

    private final AccountService accountService;
    private final CookieUtil cookieUtil;

    public MeAccountController(AccountService accountService, CookieUtil cookieUtil) {
        this.accountService = accountService;
        this.cookieUtil = cookieUtil;
    }

    @GetMapping(ApiPaths.Me.ROOT)
    public UserDto me() {
        return accountService.me(AuthPrincipal.current().userId());
    }

    @PutMapping(ApiPaths.Me.PREFERENCES)
    public UserDto preferences(@Valid @RequestBody PreferencesRequest body) {
        return accountService.updatePreferences(AuthPrincipal.current().userId(), body);
    }

    @DeleteMapping(ApiPaths.Me.ROOT)
    public ResponseEntity<Void> delete(HttpServletResponse response) {
        accountService.delete(AuthPrincipal.current().userId());
        cookieUtil.clearRefreshToken(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(ApiPaths.Me.DASHBOARD)
    public DashboardSummary dashboard(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @RequestParam(value = "visit", required = false) String visit) {
        Locale requestLocale = acceptLanguage == null || acceptLanguage.isBlank() ? null : Locale.fromTag(acceptLanguage);
        return accountService.dashboard(AuthPrincipal.current().userId(), requestLocale, visit);
    }
}
