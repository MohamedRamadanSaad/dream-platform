package com.saadat.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.credits.service.CreditService;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.dreams.service.DreamService;
import com.saadat.users.domain.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PDF downloads: bytes are a PDF, access rules hold, and sample documents are written to build/samples/ (published
 * by CI to the ci-logs branch for a visual check of Arabic shaping / RTL).
 */
class ReportsIntegrationTest extends IntegrationTestBase {

    /** Seeded package p1 (1 credit); SA → 49 SAR via the mock provider. */
    private static final String PACKAGE_ONE = "22222222-2222-4222-8222-000000000001";
    private static final Path SAMPLES = Path.of("build", "samples");

    private static final String DREAM_AR = "رأيت في المنام ليلة 27 من رمضان أنني أقف على شاطئ واسع، والبحر هادئ جداً، "
            + "ثم رأيت سفينة بيضاء مكتوباً عليها بالإنجليزية Hope 2024، فصعدت إليها مع أمي وأختي الصغرى.\n"
            + "وفي آخر الرؤيا وجدت خاتماً من ذهب فيه 3 فصوص خضراء، وكان الجو مضيئاً كأنه وقت الفجر.";
    private static final String QUESTION_AR = "هل كانت السفينة تتحرك أم كانت راسية على الشاطئ؟ وكم كان عمرك تقريباً في الرؤيا؟";
    private static final String REPLY_AR = "كانت راسية ثم تحركت ببطء، وكنت في عمري الحالي تقريباً 32 سنة.";
    private static final String INTERPRETATION_AR = "رؤيا مبشرة بإذن الله، وقد قال تعالى: ﴿ إِنِّي رَأَيْتُ أَحَدَ عَشَرَ "
            + "كَوْكَبًا وَالشَّمْسَ وَالْقَمَرَ رَأَيْتُهُمْ لِي سَاجِدِينَ ﴾. البحر الهادئ يدل على طمأنينة النفس، والسفينة "
            + "البيضاء نجاة وخير قادم، وصعودك مع أهلك اجتماع على الخير. والخاتم الذهبي بفصوصه الثلاثة قد يشير إلى نعمة "
            + "تتجدد عليك في ثلاثة أبواب من الرزق، والفجر بداية مرحلة جديدة أوضح من سابقتها.\n"
            + "نسأل الله أن يجعلها خيراً لك، والله أعلم.";
    private static final String SECOND_DREAM_AR = "رأيت أنني أكتب رسالة طويلة لصديقة قديمة ثم ضاعت الورقة في الريح.";

    private static final String DREAM_EN = "Last night I dreamed that I was walking in an old market with my brother. "
            + "A man gave me a small bottle of water and said «بارك الله فيك» twice.\n"
            + "Then I saw 7 white birds flying over the mosque at 5:30 in the morning.";
    private static final String INTERPRETATION_EN = "This is a good dream, God willing. Water given to you is a sign of "
            + "provision and ease, and walking with your brother shows support between you. The white birds over the "
            + "mosque point to good news that comes early, like the morning time you saw.\nAnd God knows best.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CreditService creditService;

    @Autowired
    DreamService dreamService;

    @Autowired
    DreamRepository dreamRepository;

    @Test
    void interpreterPdfsAreRenderedAndSamplesWritten() throws Exception {
        User interpreter = createUser("pdf-interp", Role.INTERPRETER);
        User user = arabicUser();
        UUID paid = paidDream(user, DREAM_AR);
        ask(interpreter, paid, QUESTION_AR);
        reply(user, paid, REPLY_AR);
        interpret(interpreter, paid, INTERPRETATION_AR);

        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID cancelled = draft(user, SECOND_DREAM_AR);
        dreamService.submit(user.getId(), List.of(cancelled), null);
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + cancelled + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"تكررت الرؤيا نفسها في طلب آخر.\"}"))
                .andExpect(status().isNoContent());

        MvcResult dreamPdf = mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + paid + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn();
        String disposition = dreamPdf.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        String shortId = paid.toString().substring(0, 8);
        assertThat(disposition).startsWith("attachment;")
                .contains("filename=\"dream-" + shortId + ".pdf\"")
                .contains("filename*=UTF-8''dream-" + shortId + ".pdf");
        assertPdf(dreamPdf);
        assertThat(text(dreamPdf)).as("the interpreter sees the e-mail and the order").contains("@").contains("MOCK-");
        save("dream-ar.pdf", dreamPdf);

        // the owner's copy of the same dream: no e-mail, no payment reference
        MvcResult ownPdf = mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + paid + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andReturn();
        assertPdf(ownPdf);
        assertThat(text(ownPdf)).doesNotContain("@").doesNotContain("MOCK-");
        save("my-dream-ar.pdf", ownPdf);

        MvcResult userPdf = mvc.perform(get(ApiPaths.Admin.USERS + "/" + user.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andReturn();
        assertPdf(userPdf);
        save("user-ar.pdf", userPdf);

        // English rendering of the same (Arabic) dream for the interpreter
        MvcResult english = mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + paid + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andReturn();
        assertPdf(english);

        // not for users; drafts and unknown ids are 404
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + paid + "/pdf").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ApiPaths.Admin.USERS + "/" + user.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        UUID aDraft = draft(user, SECOND_DREAM_AR);
        mvc.perform(get(ApiPaths.Admin.DREAMS + "/" + aDraft + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isNotFound());
        mvc.perform(get(ApiPaths.Admin.USERS + "/" + UUID.randomUUID() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownersDownloadTheirOwnDreamsOnly() throws Exception {
        User interpreter = createUser("pdf-interp", Role.INTERPRETER);
        User user = createUser("pdf-en", Role.USER);
        user.setName("Sara Ahmed");
        user.setLocale(Locale.EN);
        user.setBirthDate(LocalDate.of(1994, 2, 11));
        user = userRepository.save(user);
        creditService.add(user.getId(), 1, LedgerReason.BONUS, null, "test", null);
        UUID dream = draft(user, DREAM_EN);
        dreamService.submit(user.getId(), List.of(dream), null);
        interpret(interpreter, dream, INTERPRETATION_EN);

        // no Accept-Language → the user's own locale (en)
        MvcResult own = mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + dream + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn();
        assertPdf(own);
        save("dream-en.pdf", own);
        assertThat(text(own)).contains("Sara Ahmed").doesNotContain("@");

        MvcResult all = mvc.perform(get(ApiPaths.Me.DREAMS_PDF)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar"))
                .andExpect(status().isOk())
                .andReturn();
        assertPdf(all);
        assertThat(all.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("dreams-");

        User stranger = createUser("stranger", Role.USER);
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + dream + "/pdf").header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());
        UUID ownDraft = draft(user, DREAM_EN);
        mvc.perform(get(ApiPaths.Dreams.ROOT + "/" + ownDraft + "/pdf").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());
        mvc.perform(get(ApiPaths.Me.DREAMS_PDF)).andExpect(status().isUnauthorized());
        assertThat(dreamRepository.findById(ownDraft).orElseThrow().getStatus()).isEqualTo(DreamStatus.DRAFT);
    }

    // ------------------------------------------------------------------ helpers

    private User arabicUser() {
        User user = createUser("pdf-ar", Role.USER);
        user.setName("سارة أحمد");
        user.setBirthDate(LocalDate.of(1992, 4, 18));
        user.setLocale(Locale.AR);
        return userRepository.save(user);
    }

    /** Draft → checkout (mock provider) → successful webhook → auto-submitted with a paying order. */
    private UUID paidDream(User user, String text) throws Exception {
        UUID dream = draft(user, text);
        MvcResult checkout = mvc.perform(post(ApiPaths.Checkout.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("packageId", PACKAGE_ONE, "dreamIds", List.of(dream.toString())))))
                .andExpect(status().isOk())
                .andReturn();
        String orderId = objectMapper.readTree(checkout.getResponse().getContentAsString()).get("orderId").asText();
        mvc.perform(post(ApiPaths.Webhooks.ROOT + "/mock/" + orderId).param("success", "true"))
                .andExpect(status().isOk());
        assertThat(dreamRepository.findById(dream).orElseThrow().getStatus()).isEqualTo(DreamStatus.IN_REVIEW);
        return dream;
    }

    private UUID draft(User user, String text) throws Exception {
        MvcResult r = mvc.perform(post(ApiPaths.Dreams.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", text, "gender", "FEMALE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }

    private void ask(User interpreter, UUID dream, String body) throws Exception {
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + dream + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isCreated());
    }

    private void reply(User user, UUID dream, String body) throws Exception {
        mvc.perform(post(ApiPaths.Dreams.ROOT + "/" + dream + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", body))))
                .andExpect(status().isCreated());
    }

    private void interpret(User interpreter, UUID dream, String text) throws Exception {
        mvc.perform(post(ApiPaths.Admin.DREAMS + "/" + dream + "/interpretation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(interpreter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("text", text))))
                .andExpect(status().isNoContent());
    }

    private static void assertPdf(MvcResult result) {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(1000);
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    /** The visible text of a PDF (PDFBox text extraction). */
    private static String text(MvcResult result) throws IOException {
        try (PDDocument doc = Loader.loadPDF(result.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static void save(String name, MvcResult result) throws Exception {
        Files.createDirectories(SAMPLES);
        Files.write(SAMPLES.resolve(name), result.getResponse().getContentAsByteArray());
    }
}
