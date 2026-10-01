package com.saadat.settings;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.settings.api.AdminWaitTimeController.WaitTimeSettings;
import com.saadat.users.domain.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AdminWaitTimeIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void interpreterReadsAndUpdatesWaitTime() throws Exception {
        User interpreter = createUser("wait-admin", Role.INTERPRETER);
        String auth = bearer(interpreter);

        String original = mvc.perform(get(ApiPaths.Admin.WAIT_TIME).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.normalHours").isNumber())
                .andExpect(jsonPath("$.messageAr").isString())
                .andReturn().getResponse().getContentAsString();

        WaitTimeSettings busy = new WaitTimeSettings(true, 24, 4, 6, "رسالة", "Message",
                Instant.parse("2099-01-01T00:00:00Z"));
        mvc.perform(put(ApiPaths.Admin.WAIT_TIME).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(busy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.busyMinDays").value(4))
                .andExpect(jsonPath("$.busyMaxDays").value(6))
                .andExpect(jsonPath("$.autoResetAt").value("2099-01-01T00:00:00Z"));

        mvc.perform(get(ApiPaths.Public.WAIT_TIME).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.maxDays").value(6))
                .andExpect(jsonPath("$.message").value("Message"));

        WaitTimeSettings invalid = new WaitTimeSettings(true, 24, 5, 2, "", "", null);
        mvc.perform(put(ApiPaths.Admin.WAIT_TIME).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isUnprocessableEntity());

        // restore for other tests sharing the database
        mvc.perform(put(ApiPaths.Admin.WAIT_TIME).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk());
    }

    @Test
    void regularUserIsForbidden() throws Exception {
        User user = createUser("wait-user", Role.USER);
        mvc.perform(get(ApiPaths.Admin.WAIT_TIME).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
    }
}
