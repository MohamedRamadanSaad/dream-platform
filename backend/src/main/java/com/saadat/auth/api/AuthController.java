package com.saadat.auth.api;

import com.saadat.auth.api.AuthDtos.AuthResponse;
import com.saadat.auth.api.AuthDtos.GoogleLoginRequest;
import com.saadat.auth.api.AuthDtos.MagicRequest;
import com.saadat.auth.api.AuthDtos.MagicVerifyRequest;
import com.saadat.auth.api.AuthDtos.OnboardingRequest;
import com.saadat.auth.service.AuthService;
import com.saadat.auth.service.AuthService.LoginContext;
import com.saadat.auth.service.AuthService.LoginResult;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.auth.web.CookieUtil;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.common.web.ClientIp;
import com.saadat.common.web.CountryResolver;
import com.saadat.users.api.UserDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link ApiPaths.Auth} routes. The refresh token travels only in the HttpOnly cookie: persistent for "remember me"
 * sign-ins (the default), a browser-session cookie otherwise; a refresh writes the cookie in the family's mode.
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final CookieUtil cookieUtil;
    private final CountryResolver countryResolver;

    public AuthController(AuthService authService, CookieUtil cookieUtil, CountryResolver countryResolver) {
        this.authService = authService;
        this.cookieUtil = cookieUtil;
        this.countryResolver = countryResolver;
    }

    @PostMapping(ApiPaths.Auth.GOOGLE)
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest body, HttpServletRequest request,
                               HttpServletResponse response) {
        return withCookie(authService.loginWithGoogle(body.idToken(), AuthDtos.rememberMe(body.rememberMe()),
                context(request)), response);
    }

    @PostMapping(ApiPaths.Auth.MAGIC_REQUEST)
    public ResponseEntity<Void> magicRequest(@Valid @RequestBody MagicRequest body, HttpServletRequest request) {
        authService.requestMagicLink(body.email(), context(request));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Auth.MAGIC_VERIFY)
    public AuthResponse magicVerify(@Valid @RequestBody MagicVerifyRequest body, HttpServletRequest request,
                                    HttpServletResponse response) {
        return withCookie(authService.verifyMagicLink(body, context(request)), response);
    }

    @PostMapping(ApiPaths.Auth.REFRESH)
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookieUtil.readRefreshToken(request);
        try {
            return withCookie(authService.refresh(raw, context(request)), response);
        } catch (UnauthorizedException e) {
            cookieUtil.clearRefreshToken(response);
            throw e;
        }
    }

    @PostMapping(ApiPaths.Auth.LOGOUT)
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(cookieUtil.readRefreshToken(request));
        cookieUtil.clearRefreshToken(response);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Auth.ONBOARDING)
    public UserDto onboarding(@Valid @RequestBody OnboardingRequest body) {
        return authService.onboard(AuthPrincipal.current().userId(), body);
    }

    private AuthResponse withCookie(LoginResult result, HttpServletResponse response) {
        IssuedRefreshToken token = result.refreshToken();
        cookieUtil.writeRefreshToken(response, token.rawToken(), token.ttl(), token.persistent());
        return result.response();
    }

    private LoginContext context(HttpServletRequest request) {
        return new LoginContext(
                request.getHeader(HttpHeaders.USER_AGENT),
                ClientIp.resolve(request),
                countryResolver.resolve(request),
                Locale.fromTag(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)));
    }
}
