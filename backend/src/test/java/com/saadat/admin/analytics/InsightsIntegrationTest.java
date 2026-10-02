package com.saadat.admin.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.admin.analytics.InsightsDtos.InsightKind;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class InsightsIntegrationTest extends IntegrationTestBase {

    private static final String DREAM_TEXT = "رأيت أنني أسير في طريق طويل تحت المطر ثم أشرقت الشمس فجأة.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Autowired
    DreamRepository dreamRepository;

    @Test
    void overdueDreamProducesAWarningAndMyActivityIsComplete() throws Exception {
        User user = createUser("insight-user", Role.USER);
        User interpreter = createUser("insight-interp", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID dreamId = createDraft(user);
        dreamService.submit(user.getId(), List.of(dreamId), null);
        Dream dream = dreamRepository.findById(dreamId).orElseThrow();
        dream.setExpectedBy(Instant.now().minus(2, ChronoUnit.HOURS));
        dreamRepository.save(dream);

        MvcResult result = mvc.perform(get(ApiPaths.Admin.ANALYTICS_INSIGHTS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.myActivity.interpretedThisMonth").isNumber())
                .andExpect(jsonPath("$.myActivity.interpretedLastMonth").isNumber())
                .andExpect(jsonPath("$.myActivity.avgResponseHours").isNumber())
                .andExpect(jsonPath("$.myActivity.slaHours").isNumber())
                .andExpect(jsonPath("$.myActivity.onTimeRate").isNumber())
                .andExpect(jsonPath("$.myActivity.myBusiestHours").isArray())
                .andExpect(jsonPath("$.myActivity.usersPeakHours").isArray())
                .andExpect(jsonPath("$.myActivity.streakDays").isNumber())
                .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items.size()).isBetween(1, 8);
        List<Integer> ranks = new ArrayList<>();
        JsonNode overdue = null;
        for (JsonNode item : items) {
            ranks.add(InsightKind.valueOf(item.get("kind").asText()).ordinal());
            assertThat(item.get("title").asText()).isNotBlank().doesNotContain("{").doesNotStartWith("insight.");
            assertThat(item.get("body").asText()).isNotBlank().doesNotContain("{");
            assertThat(item.has("link")).isTrue();
            if ("overdue".equals(item.get("id").asText())) {
                overdue = item;
            }
        }
        assertThat(ranks).as("sorted WARNING, TIP, SUCCESS, INFO").isSorted();
        assertThat(overdue).isNotNull();
        assertThat(overdue.get("kind").asText()).isEqualTo("WARNING");
        assertThat(overdue.get("link").asText()).isEqualTo("/admin/queue");
    }

    @Test
    void textsFollowTheRequestedLanguage() throws Exception {
        User interpreter = createUser("insight-ar", Role.INTERPRETER);
        MvcResult result = mvc.perform(get(ApiPaths.Admin.ANALYTICS_INSIGHTS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            assertThat(item.get("title").asText()).matches(".*[\\u0600-\\u06FF].*");
        }
    }

    private UUID createDraft(User user) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", DREAM_TEXT, "gender", "MALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }
}
