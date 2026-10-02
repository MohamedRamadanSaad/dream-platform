package com.saadat.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Device;
import com.saadat.common.domain.Role;
import com.saadat.tracking.domain.PageView;
import com.saadat.tracking.repo.PageViewRepository;
import com.saadat.users.domain.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class TrackIntegrationTest extends IntegrationTestBase {

    static final String UA_PHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    static final String UA_TABLET = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0 Safari/537.36";
    static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/126.0 Safari/537.36";
    static final String UA_BOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    @Autowired
    PageViewRepository pageViewRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void storesTheViewWithServerSideCountryDeviceAndReferrerHost() throws Exception {
        String session = "s-" + UUID.randomUUID();
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_PHONE)
                        .header("CF-IPCountry", "EG")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/me/dreams/?tab=all", "https://www.google.com/search?q=dream", session)))
                .andExpect(status().isNoContent());

        List<PageView> rows = pageViewRepository.findBySessionIdOrderByCreatedAtAsc(session);
        assertThat(rows).singleElement().satisfies(v -> {
            assertThat(v.getPath()).isEqualTo("/me/dreams");
            assertThat(v.getCountryCode()).isEqualTo("EG");
            assertThat(v.getDevice()).isEqualTo(Device.MOBILE);
            assertThat(v.getReferrerHost()).isEqualTo("google.com");
            assertThat(v.getUserId()).isNull();
            assertThat(v.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void attachesTheSignedInUserAndFallsBackToTheirCountry() throws Exception {
        User user = createUser("tracked", Role.USER);
        String session = "s-" + UUID.randomUUID();
        // no CF-IPCountry: only the default is known → the user's stored country (SA) is used
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header(HttpHeaders.USER_AGENT, UA_TABLET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/", "http://localhost:5173/me", session)))
                .andExpect(status().isNoContent());

        assertThat(pageViewRepository.findBySessionIdOrderByCreatedAtAsc(session)).singleElement().satisfies(v -> {
            assertThat(v.getUserId()).isEqualTo(user.getId());
            assertThat(v.getCountryCode()).isEqualTo("SA");
            assertThat(v.getDevice()).isEqualTo(Device.TABLET);
            assertThat(v.getReferrerHost()).as("own site counts as direct").isNull();
        });
    }

    @Test
    void interpreterPathsAndBotsAreNotStoredButStillAnswer204() throws Exception {
        String session = "s-" + UUID.randomUUID();
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_DESKTOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/admin/dreams", null, session)))
                .andExpect(status().isNoContent());
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_BOT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/", null, session)))
                .andExpect(status().isNoContent());

        assertThat(pageViewRepository.findBySessionIdOrderByCreatedAtAsc(session)).isEmpty();
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_DESKTOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("no-leading-slash", null, "s-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_DESKTOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/" + "x".repeat(300), null, "s-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest());
        mvc.perform(post(ApiPaths.Public.TRACK)
                        .header(HttpHeaders.USER_AGENT, UA_DESKTOP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("/", null, " ")))
                .andExpect(status().isBadRequest());
    }

    private String body(String path, String referrer, String sessionId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", path);
        body.put("referrer", referrer);
        body.put("sessionId", sessionId);
        return objectMapper.writeValueAsString(body);
    }
}
