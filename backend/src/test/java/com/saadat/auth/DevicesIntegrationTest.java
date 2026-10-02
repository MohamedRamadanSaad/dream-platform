package com.saadat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.saadat.auth.service.RefreshTokenService;
import com.saadat.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.pricing.repo.CountryRepository;
import com.saadat.users.domain.RefreshToken;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Devices = the caller's active refresh-token families (docs/SESSIONS_PROFILE_CONTRACT.md §2): the list, signing one
 * device out, signing the others out, and the {@code sid} check that makes a signed-out device's access token
 * anonymous on its next request.
 */
class DevicesIntegrationTest extends SessionTestBase {

    @Autowired
    CountryRepository countryRepository;

    @Autowired
    RefreshTokenService refreshTokenService;

    private JsonNode devices(SignIn caller, String acceptLanguage) throws Exception {
        MvcResult result = mvc.perform(get(ApiPaths.Me.DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, caller.bearer())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode byId(JsonNode list, UUID id) {
        for (JsonNode device : list) {
            if (device.get("id").asText().equals(id.toString())) {
                return device;
            }
        }
        throw new AssertionError("Device " + id + " is not in " + list);
    }

    private static List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(d -> out.add(d.get("id").asText()));
        return out;
    }

    @Test
    void accessTokensCarryTheFamilyAsSid() throws Exception {
        SignIn device = signIn(uniqueEmail("sid"), CHROME_WINDOWS, "EG", null);

        assertThat(jwtService.verify(device.accessToken()).sessionId()).isEqualTo(device.familyId());
        assertThat(row(device.refreshToken()).getFamilyId()).isEqualTo(device.familyId());

        SignIn refreshed = refresh(device, CHROME_WINDOWS);
        assertThat(refreshed.familyId()).isEqualTo(device.familyId());
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, refreshed.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void listsTheActiveFamiliesWithTheCurrentOneMarked() throws Exception {
        String email = uniqueEmail("devices-list");
        SignIn laptop = signIn(email, CHROME_WINDOWS, "EG", null);
        SignIn phone = signIn(email, SAFARI_IPHONE, "SA", false);

        JsonNode list = devices(laptop, "en");
        assertThat(list.size()).isEqualTo(2);
        // most recently active first: the phone signed in after the laptop
        assertThat(ids(list)).containsExactly(phone.familyId().toString(), laptop.familyId().toString());

        JsonNode l = byId(list, laptop.familyId());
        assertThat(l.get("browser").asText()).isEqualTo("Chrome");
        assertThat(l.get("os").asText()).isEqualTo("Windows");
        assertThat(l.get("deviceType").asText()).isEqualTo("DESKTOP");
        assertThat(l.get("countryCode").asText()).isEqualTo("EG");
        assertThat(l.get("countryName").asText()).isEqualTo(countryRepository.findById("EG").orElseThrow().getNameEn());
        assertThat(l.get("current").asBoolean()).isTrue();
        assertThat(l.get("persistent").asBoolean()).isTrue();
        Instant signedIn = Instant.parse(l.get("signedInAt").asText());
        Instant lastActive = Instant.parse(l.get("lastActiveAt").asText());
        assertThat(lastActive).isAfterOrEqualTo(signedIn);

        JsonNode p = byId(list, phone.familyId());
        assertThat(p.get("browser").asText()).isEqualTo("Safari");
        assertThat(p.get("os").asText()).isEqualTo("iOS");
        assertThat(p.get("deviceType").asText()).isEqualTo("MOBILE");
        assertThat(p.get("countryCode").asText()).isEqualTo("SA");
        assertThat(p.get("current").asBoolean()).isFalse();
        assertThat(p.get("persistent").asBoolean()).isFalse();

        // the country name follows Accept-Language
        JsonNode arabic = devices(laptop, "ar");
        assertThat(byId(arabic, laptop.familyId()).get("countryName").asText())
                .isEqualTo(countryRepository.findById("EG").orElseThrow().getNameAr());

        // seen from the phone, the phone is the current device
        JsonNode fromPhone = devices(phone, "en");
        assertThat(byId(fromPhone, phone.familyId()).get("current").asBoolean()).isTrue();
        assertThat(byId(fromPhone, laptop.familyId()).get("current").asBoolean()).isFalse();

        // a refresh makes the laptop the most recently active device; signedInAt stays the first sign-in
        SignIn laptopLater = refresh(laptop, CHROME_WINDOWS_NEWER);
        JsonNode afterRefresh = devices(laptopLater, "en");
        assertThat(ids(afterRefresh)).containsExactly(laptop.familyId().toString(), phone.familyId().toString());
        JsonNode refreshed = byId(afterRefresh, laptop.familyId());
        assertThat(Instant.parse(refreshed.get("signedInAt").asText())).isEqualTo(signedIn);
        assertThat(Instant.parse(refreshed.get("lastActiveAt").asText())).isAfter(lastActive);
    }

    @Test
    void aDeviceWithoutACountryHasNullCountryFields() throws Exception {
        User user = createUser("devices-nocountry", Role.USER);
        IssuedRefreshToken issued = refreshTokenService.issue(user.getId(), FIREFOX_LINUX); // no sign-in country

        MvcResult result = mvc.perform(get(ApiPaths.Me.DEVICES)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode d = byId(objectMapper.readTree(result.getResponse().getContentAsString()), issued.familyId());
        assertThat(d.get("countryCode").isNull()).isTrue();
        assertThat(d.get("countryName").isNull()).isTrue();
        assertThat(d.get("browser").asText()).isEqualTo("Firefox");
        assertThat(d.get("os").asText()).isEqualTo("Linux");
        assertThat(d.get("deviceType").asText()).isEqualTo("DESKTOP");
        assertThat(d.get("current").asBoolean()).isFalse();
        assertThat(d.get("persistent").asBoolean()).isTrue();
    }

    @Test
    void signingADeviceOutStopsItsAccessAndRefreshTokens() throws Exception {
        String email = uniqueEmail("devices-revoke");
        SignIn laptop = signIn(email, CHROME_WINDOWS, "EG", null);
        SignIn phone = signIn(email, SAFARI_IPHONE, "EG", null);
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, phone.bearer()))
                .andExpect(status().isOk());

        mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + phone.familyId())
                        .header(HttpHeaders.AUTHORIZATION, laptop.bearer()))
                .andExpect(status().isNoContent());

        // the phone's still-unexpired access token is anonymous on its next request
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, phone.bearer()))
                .andExpect(status().isUnauthorized());
        // and its refresh token is dead
        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(phone.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(RefreshTokenService.CODE_INVALID));
        // the laptop is untouched
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, laptop.bearer()))
                .andExpect(status().isOk());
        assertThat(ids(devices(laptop, "en"))).containsExactly(laptop.familyId().toString());

        // signing out an already signed-out device of one's own is idempotent
        mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + phone.familyId())
                        .header(HttpHeaders.AUTHORIZATION, laptop.bearer()))
                .andExpect(status().isNoContent());
    }

    @Test
    void signingTheCurrentDeviceOutClearsTheCookie() throws Exception {
        SignIn device = signIn(uniqueEmail("devices-self"), CHROME_WINDOWS, "EG", null);

        MvcResult result = mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + device.familyId())
                        .header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(refreshSetCookie(result.getResponse())).contains("Max-Age=0");

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherUsersDeviceIs404AndStaysSignedIn() throws Exception {
        SignIn mine = signIn(uniqueEmail("devices-mine"), CHROME_WINDOWS, "EG", null);
        SignIn theirs = signIn(uniqueEmail("devices-theirs"), SAFARI_MAC, "SA", null);

        mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + theirs.familyId())
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, mine.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, theirs.bearer()))
                .andExpect(status().isOk());
        assertThat(row(theirs.refreshToken()).isRevoked()).isFalse();
    }

    @Test
    void signOutOthersKeepsOnlyTheCurrentDevice() throws Exception {
        String email = uniqueEmail("devices-others");
        SignIn laptop = signIn(email, CHROME_WINDOWS, "EG", null);
        SignIn phone = signIn(email, SAFARI_IPHONE, "EG", false);
        SignIn tablet = signIn(email, EDGE_WINDOWS, "SA", null);
        assertThat(devices(laptop, "en").size()).isEqualTo(3);

        mvc.perform(post(ApiPaths.Me.DEVICES_SIGN_OUT_OTHERS).header(HttpHeaders.AUTHORIZATION, laptop.bearer()))
                .andExpect(status().isNoContent());

        JsonNode left = devices(laptop, "en");
        assertThat(ids(left)).containsExactly(laptop.familyId().toString());
        assertThat(left.get(0).get("current").asBoolean()).isTrue();
        for (SignIn other : List.of(phone, tablet)) {
            mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, other.bearer()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(other.refreshToken())))
                    .andExpect(status().isUnauthorized());
        }
        // the current device keeps working, refresh included
        SignIn refreshed = refresh(laptop, CHROME_WINDOWS);
        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, refreshed.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void logoutMakesTheAccessTokenAnonymousToo() throws Exception {
        SignIn device = signIn(uniqueEmail("devices-logout"), CHROME_WINDOWS, "EG", null);

        mvc.perform(post(ApiPaths.Auth.LOGOUT).cookie(cookie(device.refreshToken())))
                .andExpect(status().isNoContent());

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredFamilyMakesTheAccessTokenAnonymous() throws Exception {
        SignIn device = signIn(uniqueEmail("devices-expired"), CHROME_WINDOWS, "EG", false);
        RefreshToken row = row(device.refreshToken());
        row.setExpiresAt(Instant.now().minusSeconds(1));
        refreshTokenRepository.save(row);

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokensWithoutSidStayValid() throws Exception {
        User user = createUser("devices-nosid", Role.USER);
        String oldToken = bearer(user); // issued without sid, like the tokens from before this change

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isOk());
        SignIn device = signIn(user.getEmail(), CHROME_WINDOWS, "EG", null);
        MvcResult list = mvc.perform(get(ApiPaths.Me.DEVICES).header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode devices = objectMapper.readTree(list.getResponse().getContentAsString());
        assertThat(byId(devices, device.familyId()).get("current").asBoolean()).isFalse();
    }

    @Test
    void theInterpreterUsesTheSameProfileRoutes() throws Exception {
        // any e-mail on the interpreter domain (setting interpreter.email_domain) is an interpreter account
        String email = "devices-" + UUID.randomUUID() + "@saadatu-aldarein.com";
        SignIn device = signIn(email, SAFARI_MAC, "SA", null);

        mvc.perform(get(ApiPaths.Me.ROOT).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("INTERPRETER"));

        mvc.perform(put(ApiPaths.Me.PREFERENCES)
                        .header(HttpHeaders.AUTHORIZATION, device.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Fatema\",\"gender\":\"FEMALE\",\"birthDate\":\"1988-03-06\",\"locale\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Fatema"))
                .andExpect(jsonPath("$.birthDate").value("1988-03-06"))
                .andExpect(jsonPath("$.age").isNumber())
                .andExpect(jsonPath("$.role").value("INTERPRETER"));

        JsonNode list = devices(device, "en");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("current").asBoolean()).isTrue();
        assertThat(list.get(0).get("os").asText()).isEqualTo("macOS");

        mvc.perform(post(ApiPaths.Me.DEVICES_SIGN_OUT_OTHERS).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(get(ApiPaths.Admin.ANALYTICS_SUMMARY).header(HttpHeaders.AUTHORIZATION, device.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void devicesRoutesNeedASignedInCaller() throws Exception {
        mvc.perform(get(ApiPaths.Me.DEVICES)).andExpect(status().isUnauthorized());
        mvc.perform(delete(ApiPaths.Me.DEVICES + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.Me.DEVICES_SIGN_OUT_OTHERS)).andExpect(status().isUnauthorized());
    }
}
