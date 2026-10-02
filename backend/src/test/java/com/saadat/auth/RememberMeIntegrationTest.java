package com.saadat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.auth.service.RefreshTokenService;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.RefreshToken;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Remember me (docs/SESSIONS_PROFILE_CONTRACT.md §1): a missing or true {@code rememberMe} gives a persistent
 * refresh cookie (Max-Age = auth.refresh_ttl_days), false a browser-session cookie (auth.session_ttl_hours); the mode
 * lives on the refresh-token family, survives every refresh, and each refresh slides the expiry forward.
 */
class RememberMeIntegrationTest extends SessionTestBase {

    @Autowired
    SettingsService settingsService;

    @Autowired
    RefreshTokenService refreshTokenService;

    private Duration rememberedTtl() {
        return Duration.ofDays(settingsService.getInt(SettingKeys.AUTH_REFRESH_TTL_DAYS));
    }

    private Duration sessionTtl() {
        return Duration.ofHours(settingsService.getInt(SettingKeys.AUTH_SESSION_TTL_HOURS));
    }

    @Test
    void migrationRaisedTheRememberedLifetimeAndAddedTheSessionLifetime() {
        assertThat(settingsService.getInt(SettingKeys.AUTH_REFRESH_TTL_DAYS)).isEqualTo(90);
        assertThat(settingsService.getInt(SettingKeys.AUTH_SESSION_TTL_HOURS)).isEqualTo(12);
        assertThat(settingsService.getInt(SettingKeys.AUTH_KNOWN_DEVICE_DAYS)).isEqualTo(90);
        assertThat(settingsService.getBool(SettingKeys.MAIL_EVENT_NEW_SIGN_IN)).isTrue();
    }

    @Test
    void missingRememberMeGivesAPersistentCookie() throws Exception {
        Instant before = Instant.now();
        SignIn device = signIn(uniqueEmail("remember-default"), CHROME_WINDOWS, "EG", null);

        assertPersistentCookie(device.setCookie());
        RefreshToken row = row(device.refreshToken());
        assertThat(row.isPersistent()).isTrue();
        assertThat(row.getFamilyId()).isEqualTo(device.familyId());
        assertThat(row.getCountryCode()).isEqualTo("EG");
        assertExpiresAbout(row, before, rememberedTtl());
    }

    @Test
    void rememberMeTrueGivesAPersistentCookieAndFalseASessionCookie() throws Exception {
        Instant before = Instant.now();
        SignIn remembered = signIn(uniqueEmail("remember-true"), CHROME_WINDOWS, null, true);
        assertPersistentCookie(remembered.setCookie());
        assertExpiresAbout(row(remembered.refreshToken()), before, rememberedTtl());

        SignIn forgotten = signIn(uniqueEmail("remember-false"), CHROME_WINDOWS, null, false);
        assertSessionCookie(forgotten.setCookie());
        RefreshToken row = row(forgotten.refreshToken());
        assertThat(row.isPersistent()).isFalse();
        assertExpiresAbout(row, before, sessionTtl());
    }

    @Test
    void cookieKeepsItsSecurityAttributesInBothModes() throws Exception {
        String path = properties.getAuth().getRefreshCookiePath();
        String sameSite = properties.getAuth().getRefreshCookieSameSite();
        for (Boolean rememberMe : new Boolean[] {true, false}) {
            SignIn device = signIn(uniqueEmail("remember-attrs"), CHROME_WINDOWS, null, rememberMe);
            assertThat(device.setCookie())
                    .as("rememberMe=%s", rememberMe)
                    .contains("Path=" + path)
                    .contains("HttpOnly")
                    .contains("SameSite=" + sameSite);
        }
    }

    @Test
    void refreshKeepsTheSessionModeAndSlidesTheExpiry() throws Exception {
        SignIn first = signIn(uniqueEmail("refresh-session"), FIREFOX_LINUX, "SA", false);
        // pretend the session is about to end: a refresh must give it a full lifetime again
        RefreshToken firstRow = row(first.refreshToken());
        firstRow.setExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        refreshTokenRepository.save(firstRow);

        Instant before = Instant.now();
        SignIn second = refresh(first, FIREFOX_LINUX);

        assertSessionCookie(second.setCookie());
        assertThat(second.familyId()).isEqualTo(first.familyId());
        RefreshToken secondRow = row(second.refreshToken());
        assertThat(secondRow.isPersistent()).isFalse();
        assertThat(secondRow.getCountryCode()).isEqualTo("SA");
        assertExpiresAbout(secondRow, before, sessionTtl());

        SignIn third = refresh(second, FIREFOX_LINUX);
        assertSessionCookie(third.setCookie());
        assertThat(row(third.refreshToken()).isPersistent()).isFalse();
    }

    @Test
    void refreshKeepsTheRememberedModeAndSlidesTheExpiry() throws Exception {
        SignIn first = signIn(uniqueEmail("refresh-remembered"), SAFARI_IPHONE, "EG", true);
        RefreshToken firstRow = row(first.refreshToken());
        firstRow.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        refreshTokenRepository.save(firstRow);

        Instant before = Instant.now();
        SignIn second = refresh(first, SAFARI_IPHONE);

        assertPersistentCookie(second.setCookie());
        assertThat(second.familyId()).isEqualTo(first.familyId());
        RefreshToken secondRow = row(second.refreshToken());
        assertThat(secondRow.isPersistent()).isTrue();
        assertThat(secondRow.getCountryCode()).isEqualTo("EG");
        assertExpiresAbout(secondRow, before, rememberedTtl());
    }

    @Test
    void googleSignInHonoursRememberMe() throws Exception {
        MvcResult session = mvc.perform(post(ApiPaths.Auth.GOOGLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"mock\",\"rememberMe\":false}"))
                .andExpect(status().isOk())
                .andReturn();
        SignIn forgotten = read(session.getResponse());
        assertSessionCookie(forgotten.setCookie());
        assertThat(row(forgotten.refreshToken()).isPersistent()).isFalse();

        MvcResult remembered = mvc.perform(post(ApiPaths.Auth.GOOGLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idToken\":\"mock\"}"))
                .andExpect(status().isOk())
                .andReturn();
        SignIn kept = read(remembered.getResponse());
        assertPersistentCookie(kept.setCookie());
        assertThat(row(kept.refreshToken()).isPersistent()).isTrue();
        assertThat(kept.familyId()).isNotEqualTo(forgotten.familyId());
    }

    @Test
    void cookieObjectsHaveTheRightMaxAge() throws Exception {
        String name = properties.getAuth().getRefreshCookieName();

        Cookie session = verify(uniqueEmail("cookie-session"), false).getCookie(name);
        assertThat(session).isNotNull();
        assertThat(session.getMaxAge()).as("browser-session cookie").isEqualTo(-1);
        assertThat(session.isHttpOnly()).isTrue();
        if (session instanceof MockCookie mock) {
            assertThat(mock.getExpires()).isNull();
        }

        Cookie persistent = verify(uniqueEmail("cookie-persistent"), null).getCookie(name);
        assertThat(persistent).isNotNull();
        assertThat((long) persistent.getMaxAge()).as("persistent cookie").isEqualTo(rememberedTtl().toSeconds());
        assertThat(persistent.isHttpOnly()).isTrue();
    }

    @Test
    void issueWithoutAModeStartsARememberedFamily() {
        IssuedRefreshToken issued = refreshTokenService.issue(createUser("remember-issue", Role.USER).getId(), "junit");
        assertThat(issued.persistent()).isTrue();
        assertThat(issued.ttl()).isEqualTo(rememberedTtl());
        assertThat(row(issued.rawToken()).isPersistent()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    /** POST /auth/magic/verify with {@code rememberMe} (null = left out); the raw response. */
    private MockHttpServletResponse verify(String email, Boolean rememberMe) throws Exception {
        String token = magicLinkService.issue(email, "127.0.0.1").token();
        String body = rememberMe == null
                ? "{\"token\":\"" + token + "\"}"
                : "{\"token\":\"" + token + "\",\"rememberMe\":" + rememberMe + "}";
        MvcResult result = mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse();
    }

    private void assertPersistentCookie(String setCookie) {
        assertThat(setCookie)
                .contains("Max-Age=" + rememberedTtl().toSeconds())
                .contains("Expires=");
    }

    private static void assertSessionCookie(String setCookie) {
        assertThat(setCookie).doesNotContain("Max-Age").doesNotContain("Expires");
    }

    /** expires_at = (sign-in time) + ttl, within the time the request took. */
    private static void assertExpiresAbout(RefreshToken row, Instant before, Duration ttl) {
        Instant after = Instant.now();
        assertThat(row.getExpiresAt())
                .isAfterOrEqualTo(before.plus(ttl).minus(1, ChronoUnit.SECONDS))
                .isBeforeOrEqualTo(after.plus(ttl).plus(1, ChronoUnit.SECONDS));
    }
}
