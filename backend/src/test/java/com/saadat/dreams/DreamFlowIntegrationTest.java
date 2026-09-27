package com.saadat.dreams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Role;
import com.saadat.common.error.PaymentRequiredException;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.domain.Dream;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamService;
import com.saadat.users.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class DreamFlowIntegrationTest extends IntegrationTestBase {

    private static final String DREAM_TEXT = "رأيت أنني أمشي على شاطئ هادئ ثم وجدت خاتماً ذهبياً بين الرمال.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Autowired
    DreamRepository dreamRepository;

    @Test
    void submitWithoutCreditsIs402AndNothingIsSubmitted() throws Exception {
        User user = createUser("nocredit", Role.USER);
        UUID draft = createDraft(user);

        mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dreamIds\":[\"" + draft + "\"]}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value(PaymentRequiredException.CODE))
                .andExpect(jsonPath("$.missing").value(1))
                .andExpect(jsonPath("$.status").value(402));

        assertThat(dreamRepository.findById(draft).orElseThrow().getStatus()).isEqualTo(DreamStatus.DRAFT);
        assertThat(creditService.balance(user.getId())).isZero();
    }

    @Test
    void submittingSomeoneElsesOrNonDraftDreamIs409() throws Exception {
        User owner = createUser("owner", Role.USER);
        User other = createUser("other", Role.USER);
        creditService.add(other.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID draft = createDraft(owner);

        mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dreamIds\":[\"" + draft + "\"]}"))
                .andExpect(status().isConflict());
        assertThat(creditService.balance(other.getId())).isEqualTo(1);
    }

    @Test
    void concurrentSubmitsWithOneCreditExactlyOneSucceeds() throws Exception {
        User user = createUser("race", Role.USER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID a = createDraft(user);
        UUID b = createDraft(user);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (UUID id : List.of(a, b)) {
                Callable<Boolean> task = () -> {
                    start.await();
                    try {
                        dreamService.submit(user.getId(), List.of(id), null);
                        return true;
                    } catch (PaymentRequiredException e) {
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> f : results) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(creditService.balance(user.getId())).isZero();
        long inReview = dreamRepository.findAllById(List.of(a, b)).stream()
                .filter(d -> d.getStatus() == DreamStatus.IN_REVIEW).count();
        assertThat(inReview).isEqualTo(1);
    }

    @Test
    void questionReplyInterpretationTestimonialStatusMachine() throws Exception {
        User user = createUser("flow", Role.USER);
        User interpreter = createUser("interp", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID id = createDraft(user);

        // submit → IN_REVIEW with SLA snapshot
        mvc.perform(post(ApiPaths.Dreams.SUBMIT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dreamIds\":[\"" + id + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitted[0]").value(id.toString()))
                .andExpect(jsonPath("$.remainingCredits").value(0));
        Dream submitted = dreamRepository.findById(id).orElseThrow();
        assertThat(submitted.getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(submitted.getSlaHoursSnapshot()).isNotNull().isPositive();
        assertThat(submitted.getLedgerEntryId()).isNotNull();

        // user cannot reply while IN_REVIEW
        reply(user, id).andExpect(status().isConflict());

        // interpreter asks → AWAITING_USER_REPLY (SLA paused)
        ask(interpreter, id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.senderRole").value("INTERPRETER"));
        Dream awaiting = dreamRepository.findById(id).orElseThrow();
        assertThat(awaiting.getStatus()).isEqualTo(DreamStatus.AWAITING_USER_REPLY);
        assertThat(awaiting.getSlaPausedAt()).isNotNull();

        // a second question is only possible from IN_REVIEW
        ask(interpreter, id).andExpect(status().isConflict());

        // the owner sees the question; opening the dream marks it read
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_USER_REPLY"))
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.unreadMessages").value(0))
                .andExpect(jsonPath("$.credit.ledgerEntryId").value(submitted.getLedgerEntryId().toString()));

        // reply → IN_REVIEW, SLA resumed and not earlier than before
        reply(user, id).andExpect(status().isCreated()).andExpect(jsonPath("$.senderRole").value("USER"));
        Dream resumed = dreamRepository.findById(id).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        assertThat(resumed.getSlaPausedAt()).isNull();
        assertThat(resumed.getExpectedBy()).isAfterOrEqualTo(submitted.getExpectedBy());
        reply(user, id).andExpect(status().isConflict());

        // testimonial not yet possible
        testimonial(user, id).andExpect(status().isConflict());

        // interpretation → INTERPRETED
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/interpretation").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"رؤيا مبشرة والله أعلم.\"}"))
                .andExpect(status().isNoContent());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.INTERPRETED);

        // no more questions / replies / interpretations / cancellation
        ask(interpreter, id).andExpect(status().isConflict());
        reply(user, id).andExpect(status().isConflict());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict());

        // testimonial once
        testimonial(user, id).andExpect(status().isCreated());
        testimonial(user, id).andExpect(status().isConflict());

        // admin detail carries user{}
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + id).header("Authorization", bearer(interpreter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.interpretation.text").exists())
                .andExpect(jsonPath("$.testimonial.approved").value(false));
    }

    @Test
    void cancelRefundsTheCreditOnce() throws Exception {
        User user = createUser("cancel", Role.USER);
        User interpreter = createUser("interp", Role.INTERPRETER);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID id = createDraft(user);
        dreamService.submit(user.getId(), List.of(id), null);
        assertThat(creditService.balance(user.getId())).isZero();

        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate\"}"))
                .andExpect(status().isNoContent());
        assertThat(dreamRepository.findById(id).orElseThrow().getStatus()).isEqualTo(DreamStatus.CANCELLED);
        assertThat(creditService.balance(user.getId())).isEqualTo(1);

        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/cancel").header("Authorization", bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        assertThat(creditService.balance(user.getId())).isEqualTo(1);
    }

    @Test
    void userCannotReachAdminDreamRoutes() throws Exception {
        User user = createUser("plain", Role.USER);
        mvc.perform(get(ApiPaths.Admin.DREAMS).header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + UUID.randomUUID() + "/messages")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void draftValidationAndEditRules() throws Exception {
        User user = createUser("drafts", Role.USER);
        mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"قصير\",\"gender\":\"FEMALE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.text").exists());

        UUID id = createDraft(user);
        mvc.perform(get(ApiPaths.Dreams.ROOT).param("status", "DRAFT").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()));
    }

    // ------------------------------------------------------------------ helpers

    private UUID createDraft(User user) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("text", DREAM_TEXT, "gender", "FEMALE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions ask(User interpreter, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + id + "/messages").header("Authorization", bearer(interpreter))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"هل البيت القديم هو بيت أهلك؟\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions reply(User user, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + id + "/messages").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"نعم، بيت أهلي.\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions testimonial(User user, UUID id) throws Exception {
        return mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + id + "/testimonial").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5,\"comment\":\"جزاكم الله خيراً\"}"));
    }
}
