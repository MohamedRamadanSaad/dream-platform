package com.saadat.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class MeControllerIntegrationTest extends IntegrationTestBase {

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get(ApiPaths.Me.ROOT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get(ApiPaths.Me.DASHBOARD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheCallerWithBearer() throws Exception {
        User user = createUser("me", Role.USER);

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.locale").value("ar"))
                .andExpect(jsonPath("$.countryCode").value("SA"))
                .andExpect(jsonPath("$.countryName").isNotEmpty())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.onboarded").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void preferencesUpdateLocaleAndName() throws Exception {
        User user = createUser("prefs", Role.USER);

        mvc.perform(put(ApiPaths.Me.PREFERENCES)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"en\",\"name\":\"Sara Ahmed\",\"marketingOptIn\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.name").value("Sara Ahmed"));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getLocale()).isEqualTo(Locale.EN);
        assertThat(reloaded.isMarketingOptIn()).isTrue();

        mvc.perform(put(ApiPaths.Me.PREFERENCES)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dashboardCountsAndWaitTime() throws Exception {
        User user = createUser("dash", Role.USER);

        mvc.perform(get(ApiPaths.Me.DASHBOARD)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts").value(0))
                .andExpect(jsonPath("$.inReview").value(0))
                .andExpect(jsonPath("$.awaitingReply").value(0))
                .andExpect(jsonPath("$.interpreted").value(0))
                .andExpect(jsonPath("$.credits").value(0))
                .andExpect(jsonPath("$.unreadNotifications").value(0))
                .andExpect(jsonPath("$.waitTime.hours").isNumber())
                .andExpect(jsonPath("$.waitTime.busy").isBoolean())
                .andExpect(jsonPath("$.waitTime.message").isNotEmpty());
    }

    @Test
    void deleteAnonymisesAndLocksTheAccount() throws Exception {
        User user = createUser("delete", Role.USER);
        String bearer = bearer(user);

        mvc.perform(delete(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNoContent());

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("deleted-" + user.getId() + "@invalid");
        assertThat(reloaded.getName()).isEmpty();
        assertThat(reloaded.getDeletedAt()).isNotNull();

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isUnauthorized());
    }
}
