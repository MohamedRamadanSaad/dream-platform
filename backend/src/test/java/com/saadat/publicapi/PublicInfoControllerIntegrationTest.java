package com.saadat.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.publicapi.api.PublicInfoController;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

class PublicInfoControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    SettingsService settings;

    @Test
    void waitTimeUsesAcceptLanguage() throws Exception {
        mvc.perform(get(ApiPaths.Public.WAIT_TIME).header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(settings.getInt(SettingKeys.WAIT_NORMAL_HOURS)))
                .andExpect(jsonPath("$.minDays").value(settings.getInt(SettingKeys.WAIT_BUSY_MIN_DAYS)))
                .andExpect(jsonPath("$.maxDays").value(settings.getInt(SettingKeys.WAIT_BUSY_MAX_DAYS)))
                .andExpect(jsonPath("$.busy").isBoolean())
                .andExpect(jsonPath("$.message").value(settings.getString(SettingKeys.WAIT_MESSAGE_EN)));

        mvc.perform(get(ApiPaths.Public.WAIT_TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(settings.getString(SettingKeys.WAIT_MESSAGE_AR)));
    }

    @Test
    void statsTestimonialsAndPushKeyArePublic() throws Exception {
        mvc.perform(get(ApiPaths.Public.STATS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscribers").isString())
                .andExpect(jsonPath("$.views").isString())
                .andExpect(jsonPath("$.videos").isString());

        mvc.perform(get(ApiPaths.Public.TESTIMONIALS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        mvc.perform(get(ApiPaths.Public.PUSH_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").isString());
    }

    @Test
    void firstNameOnly() {
        assertThat(PublicInfoController.firstName("  أم محمد  ")).isEqualTo("أم محمد");
        assertThat(PublicInfoController.firstName("Sara Ahmed")).isEqualTo("Sara");
        assertThat(PublicInfoController.firstName("Sara")).isEqualTo("Sara");
        assertThat(PublicInfoController.firstName(null)).isEmpty();
    }
}
