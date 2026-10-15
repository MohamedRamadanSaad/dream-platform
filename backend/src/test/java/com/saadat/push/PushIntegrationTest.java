package com.saadat.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.config.props.AppProperties;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import com.saadat.users.domain.User;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.util.BigIntegers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Web Push: a subscription endpoint belongs to the last account that registered it (upsert by endpoint), and
 * {@code POST /push/test} reports what it could do and sends a real, encrypted RFC 8030 request (TTL 24 h, urgency
 * high, aes128gcm + VAPID) to the caller's own devices only. A local HTTP server plays the browser's push service.
 */
class PushIntegrationTest extends IntegrationTestBase {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    PushSubscriptionRepository pushRepository;

    @Autowired
    AppProperties properties;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void subscribingAnEndpointAgainMovesItToTheLatestAccount() throws Exception {
        User first = createUser("push-first", Role.USER);
        User second = createUser("push-second", Role.USER);
        String endpoint = "https://push.example.com/send/" + UUID.randomUUID();
        String body = subscription(endpoint, "p256-a", "auth-a");

        subscribe(first, body);
        assertThat(pushRepository.findByEndpoint(endpoint).orElseThrow().getUserId()).isEqualTo(first.getId());

        // same phone, another account signed in: the row is updated, not duplicated
        subscribe(second, subscription(endpoint, "p256-b", "auth-b"));
        var row = pushRepository.findByEndpoint(endpoint).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(second.getId());
        assertThat(row.getP256dh()).isEqualTo("p256-b");
        assertThat(row.getAuth()).isEqualTo("auth-b");
        assertThat(pushRepository.findByUserId(first.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from push_subscriptions where endpoint = ? and not deleted",
                Long.class, endpoint)).isEqualTo(1);

        // the same account again (start-up re-sync) changes nothing
        subscribe(second, subscription(endpoint, "p256-b", "auth-b"));
        assertThat(pushRepository.findByEndpoint(endpoint).orElseThrow().getId()).isEqualTo(row.getId());
    }

    @Test
    void testPushReportsNotConfiguredAndNoDevice() throws Exception {
        User user = createUser("push-none", Role.USER);
        mvc.perform(post(ApiPaths.Push.TEST).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").value(0))
                .andExpect(jsonPath("$.sent").value(0))
                .andExpect(jsonPath("$.configured").value(false));

        subscribe(user, subscription("https://push.example.com/send/" + UUID.randomUUID(), "p256", "auth"));
        mvc.perform(post(ApiPaths.Push.TEST).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").value(1))
                .andExpect(jsonPath("$.sent").value(0))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void testPushIsSentToTheCallersDevicesWithTtlUrgencyAndAes128gcm() throws Exception {
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
            headers.put(":path", exchange.getRequestURI().getPath());
            headers.put(":bodyLength", String.valueOf(exchange.getRequestBody().readAllBytes().length));
            received.add(headers);
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        AppProperties.Push push = properties.getPush();
        String oldPublic = push.getPublicKey();
        String oldPrivate = push.getPrivateKey();
        String oldSubject = push.getSubject();
        try {
            KeyPair vapid = p256();
            push.setPublicKey(B64URL.encodeToString(((ECPublicKey) vapid.getPublic()).getQ().getEncoded(false)));
            push.setPrivateKey(B64URL.encodeToString(
                    BigIntegers.asUnsignedByteArray(32, ((ECPrivateKey) vapid.getPrivate()).getD())));
            push.setSubject("mailto:test@example.com");

            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            User interpreter = createUser("push-interpreter", Role.INTERPRETER);
            User other = createUser("push-other", Role.USER);
            subscribe(interpreter, browserSubscription(base + "/send/" + UUID.randomUUID()));
            subscribe(other, browserSubscription(base + "/send/other-" + UUID.randomUUID()));

            mvc.perform(post(ApiPaths.Push.TEST).header(HttpHeaders.AUTHORIZATION, bearer(interpreter)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.subscriptions").value(1))
                    .andExpect(jsonPath("$.sent").value(1))
                    .andExpect(jsonPath("$.configured").value(true));

            assertThat(received).singleElement().satisfies(h -> {
                assertThat(h.get(":path")).doesNotContain("other-");
                assertThat(h.get("ttl")).isEqualTo("86400");
                assertThat(h.get("urgency")).isEqualTo("high");
                assertThat(h.get("content-encoding")).isEqualTo("aes128gcm");
                assertThat(h.get("authorization")).startsWith("vapid t=").contains(", k=");
                assertThat(Integer.parseInt(h.get(":bodyLength"))).isPositive();
            });
            assertThat(pushRepository.findByUserId(interpreter.getId())).singleElement()
                    .satisfies(s -> assertThat(s.getLastSuccessAt()).isNotNull());
        } finally {
            push.setPublicKey(oldPublic);
            push.setPrivateKey(oldPrivate);
            push.setSubject(oldSubject);
            server.stop(0);
        }
    }

    private void subscribe(User user, String body) throws Exception {
        mvc.perform(post(ApiPaths.Push.SUBSCRIPTIONS).header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    private String subscription(String endpoint, String p256dh, String auth) throws Exception {
        return objectMapper.writeValueAsString(Map.of("endpoint", endpoint,
                "keys", Map.of("p256dh", p256dh, "auth", auth), "userAgent", "JUnit"));
    }

    /** A subscription with real keys, as a browser makes it (the server must be able to encrypt for it). */
    private String browserSubscription(String endpoint) throws Exception {
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        String p256dh = B64URL.encodeToString(((ECPublicKey) p256().getPublic()).getQ().getEncoded(false));
        return subscription(endpoint, p256dh, B64URL.encodeToString(auth));
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        return generator.generateKeyPair();
    }
}
