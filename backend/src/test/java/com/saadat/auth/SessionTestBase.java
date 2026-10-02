package com.saadat.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.auth.service.MagicLinkService;
import com.saadat.auth.service.MagicLinkService.IssuedMagicLink;
import com.saadat.auth.service.SecureTokens;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.JwtService;
import com.saadat.common.web.CountryResolver;
import com.saadat.config.props.AppProperties;
import com.saadat.users.domain.RefreshToken;
import com.saadat.users.repo.RefreshTokenRepository;
import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Sign-ins over HTTP as a browser makes them (User-Agent, country header, "remember me"), for the remember-me,
 * devices and new-sign-in tests. Each {@link SignIn} is one signed-in device: its access token (with {@code sid}),
 * its refresh-token cookie and its family id.
 */
public abstract class SessionTestBase extends IntegrationTestBase {

    protected static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    /** Same browser + system as {@link #CHROME_WINDOWS}, a later version. */
    protected static final String CHROME_WINDOWS_NEWER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.59 Safari/537.36";
    protected static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1";
    protected static final String FIREFOX_LINUX =
            "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0";
    protected static final String SAFARI_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15";
    protected static final String EDGE_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.2792.79";

    @Autowired
    protected MagicLinkService magicLinkService;

    @Autowired
    protected AppProperties properties;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    /** One signed-in device as the browser holds it. */
    protected record SignIn(String accessToken, String refreshToken, String setCookie, UUID familyId) {
        public String bearer() {
            return "Bearer " + accessToken;
        }
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** POST /auth/magic/verify with a fresh link; {@code rememberMe} null = the field is left out. */
    protected SignIn signIn(String email, String userAgent, String country, Boolean rememberMe) throws Exception {
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", link.token());
        if (rememberMe != null) {
            body.put("rememberMe", rememberMe);
        }
        MockHttpServletRequestBuilder request = post(ApiPaths.Auth.MAGIC_VERIFY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        withDevice(request, userAgent, country);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return read(result.getResponse());
    }

    /** POST /auth/refresh with the device's cookie (same browser). */
    protected SignIn refresh(SignIn device, String userAgent) throws Exception {
        MockHttpServletRequestBuilder request = post(ApiPaths.Auth.REFRESH).cookie(cookie(device.refreshToken()));
        withDevice(request, userAgent, null);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return read(result.getResponse());
    }

    protected SignIn read(MockHttpServletResponse response) throws Exception {
        String accessToken = objectMapper.readTree(response.getContentAsString()).get("accessToken").asText();
        String setCookie = refreshSetCookie(response);
        String sid = JWT.decode(accessToken).getClaim(JwtService.CLAIM_SESSION_ID).asString();
        if (sid == null) {
            throw new AssertionError("The access token has no sid claim");
        }
        return new SignIn(accessToken, cookieValue(setCookie), setCookie, UUID.fromString(sid));
    }

    /** The Set-Cookie header of the refresh token. */
    protected String refreshSetCookie(MockHttpServletResponse response) {
        String name = properties.getAuth().getRefreshCookieName();
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        throw new AssertionError("No refresh-token cookie in response");
    }

    protected Cookie cookie(String rawToken) {
        return new Cookie(properties.getAuth().getRefreshCookieName(), rawToken);
    }

    /** The refresh_tokens row of a raw token. */
    protected RefreshToken row(String rawToken) {
        return refreshTokenRepository.findByTokenHash(SecureTokens.sha256(rawToken)).orElseThrow();
    }

    private String cookieValue(String setCookie) {
        String name = properties.getAuth().getRefreshCookieName();
        int end = setCookie.indexOf(';');
        String value = setCookie.substring(name.length() + 1, end < 0 ? setCookie.length() : end);
        if (value.isEmpty()) {
            throw new AssertionError("Empty refresh-token cookie: " + setCookie);
        }
        return value;
    }

    private static void withDevice(MockHttpServletRequestBuilder request, String userAgent, String country) {
        if (userAgent != null) {
            request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        if (country != null) {
            request.header(CountryResolver.HEADER_DEV_COUNTRY, country);
        }
    }
}
