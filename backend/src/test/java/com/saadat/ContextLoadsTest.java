package com.saadat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.audit.repo.AuditLogRepository;
import com.saadat.common.domain.Role;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.pricing.repo.PriceRuleRepository;
import com.saadat.pricing.repo.PromotionRepository;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ContextLoadsTest extends IntegrationTestBase {

    @Autowired
    SettingsService settingsService;

    @Autowired
    CountryRepository countryRepository;

    @Autowired
    DreamPackageRepository packageRepository;

    @Autowired
    PriceRuleRepository priceRuleRepository;

    @Autowired
    PromotionRepository promotionRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Test
    void contextLoadsAndSeedsAreApplied() {
        assertThat(settingsService.getString(SettingKeys.PRICING_DEFAULT_COUNTRY)).isEqualTo("SA");
        assertThat(settingsService.getInt(SettingKeys.DREAMS_MIN_CHARS)).isEqualTo(20);
        assertThat(settingsService.getBool(SettingKeys.WAIT_BUSY)).isFalse();
        for (String key : SettingKeys.ALL) {
            assertThat(settingsService.exists(key)).as("setting %s seeded", key).isTrue();
        }
        assertThat(countryRepository.count()).isGreaterThanOrEqualTo(60);
        assertThat(packageRepository.findByActiveTrueOrderBySortOrderAsc()).hasSize(3);
        assertThat(priceRuleRepository.count()).isEqualTo(12);
        assertThat(promotionRepository.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getPackageIds()).hasSize(1));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get(ApiPaths.Infra.HEALTH))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void protectedRouteWithoutTokenIs401Problem() throws Exception {
        mvc.perform(get(ApiPaths.Me.ROOT))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.saadatu-aldarein.com/errors/unauthenticated"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void adminRouteIs403ForUserAnd200ForInterpreter() throws Exception {
        User user = createUser("user", Role.USER);
        User interpreter = createUser("interpreter", Role.INTERPRETER);

        mvc.perform(get(ApiPaths.Admin.SETTINGS).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://api.saadatu-aldarein.com/errors/forbidden"));

        mvc.perform(get(ApiPaths.Admin.SETTINGS).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['pricing.default_country']").value("SA"));
    }

    @Test
    void adminCanUpdateSettingsAndItIsAudited() throws Exception {
        User interpreter = createUser("interpreter", Role.INTERPRETER);
        long auditBefore = auditLogRepository.count();

        mvc.perform(put(ApiPaths.Admin.SETTINGS)
                        .header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stats.videos\":\"231+\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['stats.videos']").value("231+"));

        assertThat(settingsService.getString(SettingKeys.STATS_VIDEOS)).isEqualTo("231+");
        assertThat(auditLogRepository.count()).isEqualTo(auditBefore + 1);

        // wrong type → 422
        mvc.perform(put(ApiPaths.Admin.SETTINGS)
                        .header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dreams.min_chars\":\"abc\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SETTING"));

        // unknown key → 422
        mvc.perform(put(ApiPaths.Admin.SETTINGS)
                        .header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"no.such.key\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unknownRouteIs404ProblemForAuthenticatedUser() throws Exception {
        User user = createUser("user", Role.USER);
        mvc.perform(get("/does-not-exist").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
