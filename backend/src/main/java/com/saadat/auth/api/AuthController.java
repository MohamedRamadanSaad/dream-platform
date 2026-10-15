package com.saadat.auth.api;

import com.saadat.auth.api.AuthDtos.AuthResponse;
import com.saadat.auth.api.AuthDtos.CodeVerifyRequest;
import com.saadat.auth.api.AuthDtos.EmailCodeRequest;
import com.saadat.auth.api.AuthDtos.GoogleLoginRequest;
import com.saadat.auth.api.AuthDtos.MagicRequest;
import com.saadat.auth.api.AuthDtos.MagicVerifyRequest;
import com.saadat.auth.api.AuthDtos.OnboardingRequest;
import com.saadat.auth.api.AuthDtos.PasswordLoginRequest;
import com.saadat.auth.api.AuthDtos.PasswordResetRequest;
import com.saadat.auth.api.AuthDtos.SignupCompleteRequest;
import com.saadat.auth.api.AuthDtos.TicketResponse;
import com.saadat.auth.service.AuthService;
import com.saadat.auth.service.AuthService.LoginContext;
import com.saadat.auth.service.AuthService.LoginResult;
import com.saadat.auth.service.PasswordService;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.auth.web.CookieUtil;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.common.web.ClientIp;
import com.saadat.common.web.CountryResolver;
import com.saadat.passkeys.api.PasskeyDtos.PasskeySignInRequest;
import com.saadat.passkeys.api.PasskeyDtos.SignInOptionsResponse;
import com.saadat.passkeys.service.PasskeyService;
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
    private final PasskeyService passkeyService;
    private final PasswordService passwordService;

    public AuthController(AuthService authService, CookieUtil cookieUtil, CountryResolver countryResolver,
                          PasskeyService passkeyService, PasswordService passwordService) {
        this.authService = authService;
        this.cookieUtil = cookieUtil;
        this.countryResolver = countryResolver;
        this.passkeyService = passkeyService;
        this.passwordService = passwordService;
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

    /** WebAuthn request options for a passkey sign-in (docs/PASSKEYS_CONTRACT.md). */
    @PostMapping(ApiPaths.Auth.PASSKEY_OPTIONS)
    public SignInOptionsResponse passkeyOptions() {
        return passkeyService.signInOptions();
    }

    /** Passkey sign-in: same response and refresh cookie as {@link #magicVerify}; 401 PASSKEY_INVALID on failure. */
    @PostMapping(ApiPaths.Auth.PASSKEY_VERIFY)
    public AuthResponse passkeyVerify(@RequestBody PasskeySignInRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        return withCookie(passkeyService.signIn(body, context(request)), response);
    }

    // ------------------------------------------------------------------ e-mail + password (V37)

    /** 204: a SIGN_UP code is e-mailed (silently throttled); 409 ACCOUNT_EXISTS when the account has a password. */
    @PostMapping(ApiPaths.Auth.SIGNUP_REQUEST)
    public ResponseEntity<Void> signupRequest(@Valid @RequestBody EmailCodeRequest body, HttpServletRequest request) {
        passwordService.requestSignup(body.email(), context(request));
        return ResponseEntity.noContent().build();
    }

    /** Always 204 (a RESET_PASSWORD code only when the account exists). */
    @PostMapping(ApiPaths.Auth.PASSWORD_FORGOT)
    public ResponseEntity<Void> passwordForgot(@Valid @RequestBody EmailCodeRequest body,
                                               HttpServletRequest request) {
        passwordService.forgot(body.email(), context(request));
        return ResponseEntity.noContent().build();
    }

    /** A SIGN_UP / RESET_PASSWORD code → {ticket, expiresIn}; 401 MAGIC_LINK_* like the magic code. */
    @PostMapping(ApiPaths.Auth.CODE_VERIFY)
    public TicketResponse codeVerify(@Valid @RequestBody CodeVerifyRequest body) {
        return passwordService.verifyCode(body);
    }

    @PostMapping(ApiPaths.Auth.SIGNUP_COMPLETE)
    public AuthResponse signupComplete(@Valid @RequestBody SignupCompleteRequest body, HttpServletRequest request,
                                       HttpServletResponse response) {
        return withCookie(passwordService.completeSignup(body, context(request)), response);
    }

    @PostMapping(ApiPaths.Auth.PASSWORD_RESET)
    public AuthResponse passwordReset(@Valid @RequestBody PasswordResetRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        return withCookie(passwordService.reset(body, context(request)), response);
    }

    @PostMapping(ApiPaths.Auth.PASSWORD_LOGIN)
    public AuthResponse passwordLogin(@Valid @RequestBody PasswordLoginRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        return withCookie(passwordService.login(body, context(request)), response);
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
