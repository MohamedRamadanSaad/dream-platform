package com.saadat.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.admin.analytics.AnalyticsRepository;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.Role;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.notifications.domain.PushSubscription;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import com.saadat.passkeys.domain.Passkey;
import com.saadat.passkeys.domain.PasskeyChallenge;
import com.saadat.passkeys.domain.PasskeyPurpose;
import com.saadat.passkeys.repo.PasskeyChallengeRepository;
import com.saadat.passkeys.repo.PasskeyRepository;
import com.saadat.passkeys.service.PasskeyChallenges;
import com.saadat.pricing.repo.PriceRuleRepository;
import com.saadat.users.domain.AuthIdentity;
import com.saadat.users.domain.MagicLink;
import com.saadat.users.domain.RefreshToken;
import com.saadat.users.domain.User;
import com.saadat.users.repo.AuthIdentityRepository;
import com.saadat.users.repo.MagicLinkRepository;
import com.saadat.users.repo.RefreshTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Soft delete everywhere: every delete keeps the row with {@code deleted = true} (checked with plain SQL) while the
 * API behaves as if it were gone (not listed, 404 by id, natural key free to be created again).
 */
class SoftDeleteIntegrationTest extends IntegrationTestBase {

    /** Seeded package "one dream" (V3). */
    private static final String PACKAGE_ID = "22222222-2222-4222-8222-000000000001";
    private static final String DREAM_TEXT = "رأيت أنني أمشي على شاطئ هادئ ثم وجدت خاتماً ذهبياً بين الرمال.";

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PriceRuleRepository priceRuleRepository;

    @Autowired
    DreamRepository dreamRepository;

    @Autowired
    AnalyticsRepository analyticsRepository;

    @Autowired
    PasskeyRepository passkeyRepository;

    @Autowired
    PasskeyChallengeRepository challengeRepository;

    @Autowired
    PasskeyChallenges passkeyChallenges;

    @Autowired
    PushSubscriptionRepository pushRepository;

    @Autowired
    AuthIdentityRepository identityRepository;

    @Autowired
    MagicLinkRepository magicLinkRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    // ================================================================== pricing (admin API)

    @Test
    void deletedCouponStaysInTheTableAndItsCodeCanBeCreatedAgain() throws Exception {
        String auth = bearer(createUser("sd-coupon", Role.INTERPRETER));
        String code = "SOFT" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
        Map<String, Object> body = Map.of("code", code, "type", "PERCENT", "value", 10);

        UUID id = id(mvc.perform(post(ApiPaths.Admin.COUPONS).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(delete(ApiPaths.Admin.COUPON, id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        assertSoftDeleted("coupons", "id", id);
        assertThat(ids(mvc.perform(get(ApiPaths.Admin.COUPONS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn())).doesNotContain(id.toString());
        mvc.perform(put(ApiPaths.Admin.COUPON, id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":20}"))
                .andExpect(status().isNotFound());

        // same code again: the partial unique index ignores the deleted row
        UUID again = id(mvc.perform(post(ApiPaths.Admin.COUPONS).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(again).isNotEqualTo(id);
        assertThat(jdbc.queryForObject("select count(*) from coupons where code = ?", Long.class, code)).isEqualTo(2);
    }

    @Test
    void deletedPromotionStaysInTheTableAndIsGoneFromTheApi() throws Exception {
        String auth = bearer(createUser("sd-promo", Role.INTERPRETER));
        // far in the future: never active for the other tests sharing this database
        Instant start = Instant.now().plus(3000, ChronoUnit.DAYS);
        Map<String, Object> body = Map.of("name", "Soft delete test", "packageIds", List.of(PACKAGE_ID),
                "type", "PERCENT", "value", 10, "startsAt", start.toString(),
                "endsAt", start.plus(1, ChronoUnit.DAYS).toString());

        UUID id = id(mvc.perform(post(ApiPaths.Admin.PROMOTIONS).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(delete(ApiPaths.Admin.PROMOTION, id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        assertSoftDeleted("promotions", "id", id);
        assertThat(ids(mvc.perform(get(ApiPaths.Admin.PROMOTIONS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn())).doesNotContain(id.toString());
        mvc.perform(put(ApiPaths.Admin.PROMOTION, id).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":20}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ApiPaths.Admin.PROMOTION, id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedPriceRuleStaysInTheTableAndTheSameKeyCanBeCreatedAgain() throws Exception {
        String auth = bearer(createUser("sd-rule", Role.INTERPRETER));
        Map<String, Object> body = Map.of("scope", "COUNTRY", "scopeId", "TD", "packageId", PACKAGE_ID,
                "price", 5, "currency", "USD");

        UUID id = id(mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andReturn());

        mvc.perform(delete(ApiPaths.Admin.PRICE_RULE, id).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        assertSoftDeleted("price_rules", "id", id);
        assertThat(ids(mvc.perform(get(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn())).doesNotContain(id.toString());
        // price resolution reads the same repository: the deleted rule is never picked
        assertThat(priceRuleRepository.findByPackageId(UUID.fromString(PACKAGE_ID)))
                .noneMatch(r -> r.getId().equals(id));

        // same (scope, scopeId, package): a NEW rule is created (201), the deleted twin does not clash
        UUID again = id(mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(again).isNotEqualTo(id);

        mvc.perform(delete(ApiPaths.Admin.PRICE_RULE, again).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletedCountryGroupDetachesItsCountriesAndSoftDeletesItsPriceRules() throws Exception {
        String auth = bearer(createUser("sd-group", Role.INTERPRETER));
        UUID groupId = id(mvc.perform(post(ApiPaths.Admin.COUNTRY_GROUPS).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Soft delete group", "countryCodes", List.of("ER")))))
                .andExpect(status().isCreated()).andReturn());
        UUID ruleId = id(mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "GROUP", "scopeId", groupId.toString(), "packageId", PACKAGE_ID,
                                "price", 6, "currency", "USD"))))
                .andExpect(status().isCreated()).andReturn());

        mvc.perform(delete(ApiPaths.Admin.COUNTRY_GROUP, groupId).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNoContent());

        assertSoftDeleted("country_groups", "id", groupId);
        assertSoftDeleted("price_rules", "id", ruleId);
        assertThat(jdbc.queryForObject("select group_id from countries where code = 'ER'", UUID.class)).isNull();
        assertThat(ids(mvc.perform(get(ApiPaths.Admin.COUNTRY_GROUPS).header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk()).andReturn())).doesNotContain(groupId.toString());
        mvc.perform(put(ApiPaths.Admin.COUNTRY_GROUP, groupId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound());
        // a deleted group is not a valid price scope any more
        mvc.perform(post(ApiPaths.Admin.PRICE_RULES).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("scope", "GROUP", "scopeId", groupId.toString(), "packageId", PACKAGE_ID,
                                "price", 6, "currency", "USD"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ================================================================== dreams

    @Test
    void deletedDraftStaysInTheTableButIsNotFoundListedOrCounted() throws Exception {
        User user = createUser("sd-draft", Role.USER);
        MvcResult created = mvc.perform(post(ApiPaths.Dreams.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("text", DREAM_TEXT, "gender", "FEMALE"))))
                .andExpect(status().isCreated()).andReturn();
        UUID id = id(created);

        mvc.perform(delete(ApiPaths.Dreams.BY_ID, id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertSoftDeleted("dreams", "id", id);
        mvc.perform(get(ApiPaths.Dreams.BY_ID, id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ApiPaths.Dreams.BY_ID, id).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());
        assertThat(dreamRepository.findById(id)).isEmpty();
        assertThat(dreamRepository.findByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
        // native admin user row: [5] dreams, [6] drafts
        Object[] row = analyticsRepository.userRow(user.getId()).get(0);
        assertThat(((Number) row[5]).longValue()).isZero();
        assertThat(((Number) row[6]).longValue()).isZero();
    }

    // ================================================================== passkeys, push, account deletion

    @Test
    void removedPasskeyStaysInTheTableAndItsCredentialCanBeRegisteredAgain() throws Exception {
        User user = createUser("sd-passkey", Role.USER);
        byte[] credentialId = randomBytes(32);
        Passkey passkey = passkeyRepository.save(passkey(user.getId(), credentialId));

        mvc.perform(delete(ApiPaths.Me.PASSKEY, passkey.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertSoftDeleted("passkeys", "id", passkey.getId());
        assertThat(passkeyRepository.findByCredentialId(credentialId)).isEmpty();
        assertThat(passkeyRepository.countByUserId(user.getId())).isZero();
        mvc.perform(delete(ApiPaths.Me.PASSKEY, passkey.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound());

        Passkey again = passkeyRepository.saveAndFlush(passkey(user.getId(), credentialId));
        assertThat(again.getId()).isNotEqualTo(passkey.getId());
    }

    @Test
    void unsubscribedPushEndpointStaysInTheTableAndCanSubscribeAgain() throws Exception {
        User user = createUser("sd-push", Role.USER);
        String endpoint = "https://push.example.com/send/" + UUID.randomUUID();
        String body = json(Map.of("endpoint", endpoint, "keys", Map.of("p256dh", "p256", "auth", "auth")));

        mvc.perform(post(ApiPaths.Push.SUBSCRIPTIONS).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        UUID first = pushRepository.findByEndpoint(endpoint).orElseThrow().getId();

        mvc.perform(delete(ApiPaths.Push.SUBSCRIPTIONS).param("endpoint", endpoint)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertSoftDeleted("push_subscriptions", "id", first);
        assertThat(pushRepository.findByEndpoint(endpoint)).isEmpty();
        assertThat(pushRepository.findByUserId(user.getId())).isEmpty();

        mvc.perform(post(ApiPaths.Push.SUBSCRIPTIONS).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        PushSubscription second = pushRepository.findByEndpoint(endpoint).orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from push_subscriptions where endpoint = ?", Long.class,
                endpoint)).isEqualTo(2);
    }

    @Test
    void accountDeletionSoftDeletesIdentitiesPasskeysAndPushSubscriptions() throws Exception {
        User user = createUser("sd-account", Role.USER);
        String subject = "google-" + UUID.randomUUID();
        AuthIdentity identity = identityRepository.save(identity(user.getId(), subject));
        Passkey passkey = passkeyRepository.save(passkey(user.getId(), randomBytes(32)));
        PushSubscription push = new PushSubscription();
        push.setUserId(user.getId());
        push.setEndpoint("https://push.example.com/send/" + UUID.randomUUID());
        push.setP256dh("p256");
        push.setAuth("auth");
        push.setCreatedAt(Instant.now());
        push = pushRepository.save(push);

        mvc.perform(delete(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertSoftDeleted("auth_identities", "id", identity.getId());
        // passkeys go through the JPQL bulk "delete from Passkey": @SoftDelete must turn it into an update
        assertSoftDeleted("passkeys", "id", passkey.getId());
        assertSoftDeleted("push_subscriptions", "id", push.getId());

        // the same Google account can sign up again as a new account
        User fresh = createUser("sd-account-again", Role.USER);
        AuthIdentity again = identityRepository.saveAndFlush(identity(fresh.getId(), subject));
        assertThat(again.getId()).isNotEqualTo(identity.getId());
    }

    // ================================================================== housekeeping (JPQL bulk deletes)

    @Test
    void housekeepingPurgesOnlyFlagExpiredRows() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);

        PasskeyChallenge challenge = new PasskeyChallenge();
        challenge.setPurpose(PasskeyPurpose.AUTHENTICATION);
        challenge.setChallenge(randomBytes(32));
        challenge.setExpiresAt(past);
        challenge = challengeRepository.save(challenge);

        MagicLink link = new MagicLink();
        link.setEmail("sd-link-" + UUID.randomUUID() + "@example.com");
        link.setTokenHash("sd-token-" + UUID.randomUUID());
        link.setCodeHash("sd-code-" + UUID.randomUUID());
        link.setExpiresAt(past);
        link = magicLinkRepository.save(link);

        User user = createUser("sd-refresh", Role.USER);
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setTokenHash("sd-refresh-" + UUID.randomUUID());
        token.setFamilyId(UUID.randomUUID());
        token.setExpiresAt(past);
        token = refreshTokenRepository.save(token);

        assertThat(passkeyChallenges.purgeExpired()).isPositive();
        assertThat(magicLinkRepository.deleteExpiredBefore(Instant.now())).isPositive();
        assertThat(refreshTokenRepository.deleteExpiredBefore(Instant.now())).isPositive();

        assertSoftDeleted("passkey_challenges", "request_id", challenge.getRequestId());
        assertSoftDeleted("magic_links", "id", link.getId());
        assertSoftDeleted("refresh_tokens", "id", token.getId());
        assertThat(challengeRepository.findById(challenge.getRequestId())).isEmpty();
        assertThat(magicLinkRepository.findById(link.getId())).isEmpty();
        assertThat(refreshTokenRepository.findByTokenHash(token.getTokenHash())).isEmpty();
    }

    // ================================================================== helpers

    /** The row is still in the table, flagged {@code deleted = true}. */
    private void assertSoftDeleted(String table, String idColumn, UUID id) {
        List<Boolean> flags = jdbc.queryForList(
                "select deleted from " + table + " where " + idColumn + " = ?", Boolean.class, id);
        assertThat(flags).as("%s row %s kept and flagged deleted", table, id).containsExactly(true);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private UUID id(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    private List<String> ids(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.findValuesAsText("id");
    }

    private static Passkey passkey(UUID userId, byte[] credentialId) {
        Passkey p = new Passkey();
        p.setUserId(userId);
        p.setCredentialId(credentialId);
        p.setPublicKey(randomBytes(77));
        p.setLabel("Soft delete test");
        return p;
    }

    private static AuthIdentity identity(UUID userId, String subject) {
        AuthIdentity i = new AuthIdentity();
        i.setUserId(userId);
        i.setProvider(AuthProvider.GOOGLE);
        i.setProviderSubject(subject);
        return i;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }
}
