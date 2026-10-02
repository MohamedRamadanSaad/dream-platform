package com.saadat.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.auth.service.InterpreterRoleSync;
import com.saadat.auth.service.MagicLinkService;
import com.saadat.auth.service.MagicLinkService.IssuedMagicLink;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Access-control regression suite: every interpreter route is closed to anonymous callers (401) and to regular
 * users (403), every signed-in route is closed to anonymous callers, and roles are always the CURRENT ones in the
 * database (revoked roles and deleted accounts stop working on the next request).
 * Routes are discovered from the live handler mappings, so a new endpoint is covered automatically.
 */
class AccessControlIntegrationTest extends IntegrationTestBase {

    private static final List<String> SIGNED_IN_PREFIXES = List.of(ApiPaths.Me.ROOT, ApiPaths.Dreams.ROOT,
            ApiPaths.Notifications.ROOT, ApiPaths.Checkout.ROOT, ApiPaths.Push.ROOT, ApiPaths.Youtube.ROOT);
    private static final int MIN_ADMIN_ROUTES = 30;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Autowired
    InterpreterRoleSync interpreterRoleSync;

    @Autowired
    MagicLinkService magicLinkService;

    @Autowired
    SettingsService settings;

    record Route(HttpMethod method, String uri) {
    }

    private List<Route> routes(Predicate<String> pathFilter) {
        List<Route> out = new ArrayList<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pathFilter.test(pattern)) {
                    continue;
                }
                String uri = pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
                if (methods.isEmpty()) {
                    out.add(new Route(HttpMethod.GET, uri));
                }
                for (RequestMethod method : methods) {
                    out.add(new Route(HttpMethod.valueOf(method.name()), uri));
                }
            }
        });
        return out;
    }

    private static MockHttpServletRequestBuilder call(Route route) {
        return MockMvcRequestBuilders.request(route.method(), route.uri())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    private static boolean under(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private User saveUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setName("acl");
        user.setRole(role);
        user.setOnboarded(true);
        user.setCountryCode("SA");
        return userRepository.save(user);
    }

    private String interpreterDomain() {
        String domain = settings.getString(SettingKeys.INTERPRETER_EMAIL_DOMAIN, "saadatu-aldarein.com").trim();
        return domain.startsWith("@") ? domain.substring(1) : domain;
    }

    @Test
    void everyAdminRouteRejectsAnonymousCallersAndRegularUsers() throws Exception {
        List<Route> adminRoutes = routes(path -> under(path, ApiPaths.Admin.ROOT));
        assertThat(adminRoutes).hasSizeGreaterThan(MIN_ADMIN_ROUTES);
        String regularUser = bearer(createUser("acl-user", Role.USER));

        for (Route route : adminRoutes) {
            int anonymous = mvc.perform(call(route)).andReturn().getResponse().getStatus();
            assertThat(anonymous).as("anonymous %s %s", route.method(), route.uri()).isEqualTo(401);

            int asUser = mvc.perform(call(route).header(HttpHeaders.AUTHORIZATION, regularUser))
                    .andReturn().getResponse().getStatus();
            assertThat(asUser).as("USER %s %s", route.method(), route.uri()).isEqualTo(403);
        }
    }

    @Test
    void signedInRoutesRejectAnonymousCallers() throws Exception {
        List<Route> signedIn = routes(path -> SIGNED_IN_PREFIXES.stream().anyMatch(root -> under(path, root)));
        assertThat(signedIn).isNotEmpty();
        for (Route route : signedIn) {
            int status = mvc.perform(call(route)).andReturn().getResponse().getStatus();
            assertThat(status).as("anonymous %s %s", route.method(), route.uri()).isEqualTo(401);
        }
    }

    @Test
    void revokedInterpreterRoleStopsWorkingOnTheNextRequest() throws Exception {
        User interpreter = createUser("acl-revoked", Role.INTERPRETER);
        String token = bearer(interpreter);
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        interpreter.setRole(Role.USER);
        userRepository.save(interpreter);

        mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenOfADeletedAccountIsNotAccepted() throws Exception {
        User user = createUser("acl-deleted", Role.USER);
        String token = bearer(user);
        user.setDeletedAt(Instant.now());
        userRepository.save(user);

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleSyncRevokesInterpretersThatMatchNoRule() {
        User stray = createUser("acl-stray", Role.INTERPRETER); // @example.com: no interpreter rule matches
        User onDomain = saveUser("acl-" + UUID.randomUUID() + "@" + interpreterDomain(), Role.INTERPRETER);

        interpreterRoleSync.syncAll();

        assertThat(userRepository.findById(stray.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(userRepository.findById(onDomain.getId()).orElseThrow().getRole()).isEqualTo(Role.INTERPRETER);
    }

    @Test
    void signingInAppliesTheRulesBothWays() throws Exception {
        User stray = createUser("acl-login", Role.INTERPRETER);
        IssuedMagicLink link = magicLinkService.issue(stray.getEmail(), "127.0.0.1");

        mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + stray.getEmail() + "\",\"code\":\"" + link.code() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("USER"));

        assertThat(userRepository.findById(stray.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
    }
}
