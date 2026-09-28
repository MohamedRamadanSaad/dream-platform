package com.saadat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.auth.service.AuthService;
import com.saadat.auth.service.MagicLinkService;
import com.saadat.auth.service.MagicLinkService.IssuedMagicLink;
import com.saadat.auth.service.RefreshTokenService;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.auth.service.RefreshTokenService.Rotation;
import com.saadat.auth.service.SecureTokens;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.config.props.AppProperties;
import com.saadat.users.domain.MagicLink;
import com.saadat.users.domain.User;
import com.saadat.users.repo.MagicLinkRepository;
import com.saadat.users.repo.UserSessionRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

class AuthFlowIntegrationTest extends IntegrationTestBase {

    @Autowired
    MagicLinkService magicLinkService;

    @Autowired
    AuthService authService;

    @Autowired
    MagicLinkRepository magicLinkRepository;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    UserSessionRepository sessionRepository;

    @Autowired
    AppProperties properties;

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    // ------------------------------------------------------------------ magic link

    @Test
    void magicTokenIsSingleUse() {
        String email = uniqueEmail("single");
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");

        assertThat(magicLinkService.verifyToken(link.token())).isEqualTo(email);

        assertThatThrownBy(() -> magicLinkService.verifyToken(link.token()))
                .isInstanceOf(UnauthorizedException.class);
        // the code of a consumed link is dead too
        assertThatThrownBy(() -> magicLinkService.verifyCode(email, link.code()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void magicCodeWorksOnceAndIsCaseInsensitiveOnEmail() {
        String email = uniqueEmail("code");
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");

        assertThat(magicLinkService.verifyCode(email.toUpperCase(), link.code())).isEqualTo(email);
        assertThatThrownBy(() -> magicLinkService.verifyCode(email, link.code()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void expiredMagicLinkIsRejected() {
        String email = uniqueEmail("expired");
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        MagicLink row = magicLinkRepository.findByTokenHash(SecureTokens.sha256(link.token())).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        magicLinkRepository.save(row);

        assertThatThrownBy(() -> magicLinkService.verifyToken(link.token()))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.getCode()).isEqualTo(MagicLinkService.CODE_EXPIRED));
        assertThatThrownBy(() -> magicLinkService.verifyCode(email, link.code()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void wrongCodesBurnTheLinkAfterMaxAttempts() {
        String email = uniqueEmail("attempts");
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        String wrong = "000000".equals(link.code()) ? "111111" : "000000";

        for (int i = 0; i < MagicLinkService.MAX_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> magicLinkService.verifyCode(email, wrong))
                    .isInstanceOf(UnauthorizedException.class);
        }

        MagicLink row = magicLinkRepository.findByTokenHash(SecureTokens.sha256(link.token())).orElseThrow();
        assertThat(row.getAttempts()).isEqualTo(MagicLinkService.MAX_CODE_ATTEMPTS);
        assertThat(row.getUsedAt()).isNotNull();

        // even the right code no longer works, and neither does the link
        assertThatThrownBy(() -> magicLinkService.verifyCode(email, link.code()))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> magicLinkService.verifyToken(link.token()))
                .isInstanceOf(UnauthorizedException.class);
    }

    // ------------------------------------------------------------------ refresh tokens

    @Test
    void refreshRotationAndReuseDetectionRevokesFamily() {
        User user = createUser("rotation", Role.USER);
        IssuedRefreshToken first = refreshTokenService.issue(user.getId(), "junit");

        Rotation second = refreshTokenService.rotate(first.rawToken(), "junit");
        assertThat(second.userId()).isEqualTo(user.getId());
        assertThat(second.next().familyId()).isEqualTo(first.familyId());
        assertThat(second.next().rawToken()).isNotEqualTo(first.rawToken());

        Rotation third = refreshTokenService.rotate(second.next().rawToken(), "junit");

        // replaying an already-rotated token → reuse → the whole family is revoked
        assertThatThrownBy(() -> refreshTokenService.rotate(first.rawToken(), "attacker"))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.getCode()).isEqualTo(RefreshTokenService.CODE_REUSED));
        assertThatThrownBy(() -> refreshTokenService.rotate(third.next().rawToken(), "junit"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        assertThatThrownBy(() -> refreshTokenService.rotate(SecureTokens.randomToken(), "junit"))
                .isInstanceOfSatisfying(UnauthorizedException.class,
                        e -> assertThat(e.getCode()).isEqualTo(RefreshTokenService.CODE_INVALID));
    }

    // ------------------------------------------------------------------ HTTP flow

    @Test
    void magicVerifyOverHttpThenRefreshRotatesCookieAndReuseIs401() throws Exception {
        String email = uniqueEmail("http");
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");

        MvcResult login = mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Country", "EG")
                        .content("{\"token\":\"" + link.token() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(15 * 60))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.onboarded").value(false))
                .andExpect(jsonPath("$.user.locale").value("ar"))
                .andExpect(jsonPath("$.user.countryCode").value("EG"))
                .andExpect(jsonPath("$.user.providers[0]").value("MAGIC_LINK"))
                .andReturn();
        String rt1 = refreshCookie(login.getResponse());
        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(sessionRepository.countByUserId(user.getId())).isEqualTo(1);

        MvcResult refreshed = mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(rt1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();
        String rt2 = refreshCookie(refreshed.getResponse());
        assertThat(rt2).isNotEqualTo(rt1);
        assertThat(sessionRepository.countByUserId(user.getId())).isEqualTo(2);

        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(rt1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(RefreshTokenService.CODE_REUSED));
        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(rt2)))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(ApiPaths.Auth.REFRESH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onboardingRequiresTokenAndSetsProfile() throws Exception {
        User user = createUser("onboard", Role.USER);
        user.setOnboarded(false);
        userRepository.save(user);

        mvc.perform(post(ApiPaths.Auth.ONBOARDING)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sara\",\"gender\":\"FEMALE\",\"birthDate\":\"1990-05-14\",\"acceptedTerms\":true}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(ApiPaths.Auth.ONBOARDING)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sara\",\"gender\":\"FEMALE\",\"birthDate\":\"1990-05-14\",\"acceptedTerms\":false}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post(ApiPaths.Auth.ONBOARDING)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  Sara  \",\"gender\":\"FEMALE\",\"birthDate\":\"1990-05-14\",\"acceptedTerms\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sara"))
                .andExpect(jsonPath("$.gender").value("FEMALE"))
                .andExpect(jsonPath("$.onboarded").value(true));
    }

    @Test
    void mockGoogleLoginCreatesTheMockUserWithGoogleIdentity() throws Exception {
        mvc.perform(post(ApiPaths.Auth.GOOGLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"mock\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(properties.getAuth().getMockEmail()))
                .andExpect(jsonPath("$.user.providers[0]").value("GOOGLE"));
    }

    @Test
    void interpreterEmailGetsInterpreterRoleOnLogin() throws Exception {
        String email = "fatema@saadatu-aldarein.com"; // seeded setting interpreter.emails
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + link.code() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INTERPRETER"));
    }

    @Test
    void anyEmailOnTheInterpreterDomainIsAnInterpreter() throws Exception {
        String email = "assistant@saadatu-aldarein.com"; // not in interpreter.emails, but on interpreter.email_domain
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + link.code() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INTERPRETER"));
    }

    @Test
    void interpreterDomainCannotSignInWithGoogle() {
        assertThat(authService.isInterpreterDomain("x@saadatu-aldarein.com")).isTrue();
        assertThat(authService.isInterpreterDomain("x@gmail.com")).isFalse();
    }

    @Test
    void magicRequestAlwaysAnswers204() throws Exception {
        mvc.perform(post(ApiPaths.Auth.MAGIC_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("request") + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutRevokesTheFamily() throws Exception {
        User user = createUser("logout", Role.USER);
        IssuedRefreshToken token = refreshTokenService.issue(user.getId(), "junit");

        mvc.perform(post(ApiPaths.Auth.LOGOUT).cookie(cookie(token.rawToken())))
                .andExpect(status().isNoContent());

        assertThatThrownBy(() -> refreshTokenService.rotate(token.rawToken(), "junit"))
                .isInstanceOf(UnauthorizedException.class);
    }

    // ------------------------------------------------------------------ helpers

    private Cookie cookie(String value) {
        return new Cookie(properties.getAuth().getRefreshCookieName(), value);
    }

    private String refreshCookie(MockHttpServletResponse response) {
        String name = properties.getAuth().getRefreshCookieName();
        Cookie c = response.getCookie(name);
        if (c != null && c.getValue() != null && !c.getValue().isEmpty()) {
            return c.getValue();
        }
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(name + "=")) {
                int end = header.indexOf(';');
                String value = header.substring(name.length() + 1, end < 0 ? header.length() : end);
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        throw new AssertionError("No refresh-token cookie in response");
    }
}
