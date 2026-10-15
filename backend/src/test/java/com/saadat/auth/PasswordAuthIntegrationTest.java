package com.saadat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.saadat.auth.service.MagicLinkService;
import com.saadat.auth.service.MagicLinkService.IssuedMagicLink;
import com.saadat.auth.service.PasswordService;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.CodePurpose;
import com.saadat.users.domain.User;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * E-mail + password accounts (owner's decision of 2026-10-15): sign-up with an e-mailed code, password sign-in with
 * lockout, forgot password, and how codes of one purpose never work for another.
 */
class PasswordAuthIntegrationTest extends SessionTestBase {

    private static final String PASSWORD = "Sabr2026dawn";
    private static final String NEW_PASSWORD = "Fajr1447light";

    // ------------------------------------------------------------------ sign-up

    @Test
    void signUpWithACodeCreatesTheAccountAndSignsIn() throws Exception {
        String email = uniqueEmail("pw-signup");

        mvc.perform(json(ApiPaths.Auth.SIGNUP_REQUEST, Map.of("email", email))).andExpect(status().isNoContent());
        String ticket = ticket(email, CodePurpose.SIGN_UP);

        MvcResult result = completeSignup(ticket, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.hasPassword").value(true))
                .andExpect(jsonPath("$.user.termsAccepted").value(true))
                .andExpect(jsonPath("$.user.onboarded").value(false))
                .andExpect(jsonPath("$.user.providers[0]").value("PASSWORD"))
                .andReturn();
        SignIn device = read(result.getResponse());
        assertThat(row(device.refreshToken()).getUserId())
                .isEqualTo(userRepository.findByEmailIgnoreCase(email).orElseThrow().getId());

        // the ticket was used once
        completeSignup(ticket, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_TICKET_INVALID));

        // the account now has a password: signing up again is refused
        mvc.perform(json(ApiPaths.Auth.SIGNUP_REQUEST, Map.of("email", email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_ACCOUNT_EXISTS));

        login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void weakPasswordIs422AndTheTicketStaysUsable() throws Exception {
        String email = uniqueEmail("pw-weak");
        String ticket = ticket(email, CodePurpose.SIGN_UP);

        for (String weak : new String[] {"short1", "onlyletters", "1234567890", "a1".repeat(65)}) {
            completeSignup(ticket, weak)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(PasswordService.CODE_WEAK_PASSWORD));
        }
        // Arabic letters and digits count
        completeSignup(ticket, "صبرجميل٢٠٢٦").andExpect(status().isOk());
    }

    @Test
    void termsMustBeAccepted() throws Exception {
        String ticket = ticket(uniqueEmail("pw-terms"), CodePurpose.SIGN_UP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        body.put("password", PASSWORD);
        body.put("acceptedTerms", false);
        mvc.perform(json(ApiPaths.Auth.SIGNUP_COMPLETE, body)).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ login

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswerAndFiveWrongLockTheAccount() throws Exception {
        String email = uniqueEmail("pw-lock");
        signUp(email, PASSWORD);

        login(uniqueEmail("pw-nobody"), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_INVALID_CREDENTIALS));

        for (int i = 1; i < PasswordService.MAX_FAILED_ATTEMPTS; i++) {
            login(email, "Wrong" + i + "password")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(PasswordService.CODE_INVALID_CREDENTIALS));
        }
        login(email, "Wrong5password")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_TOO_MANY_ATTEMPTS))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        // locked: even the right password waits
        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_TOO_MANY_ATTEMPTS));

        // a password reset clears the lock
        resetPassword(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void forgotPasswordResetsItAndSignsOutTheOtherDevices() throws Exception {
        String email = uniqueEmail("pw-reset");
        SignIn first = read(signUp(email, PASSWORD).getResponse());

        // unknown e-mail: same 204, no code
        String nobody = uniqueEmail("pw-forgot-nobody");
        mvc.perform(json(ApiPaths.Auth.PASSWORD_FORGOT, Map.of("email", nobody))).andExpect(status().isNoContent());
        mvc.perform(json(ApiPaths.Auth.PASSWORD_FORGOT, Map.of("email", email))).andExpect(status().isNoContent());

        String ticket = ticket(email, CodePurpose.RESET_PASSWORD);
        // a reset ticket never completes a sign-up (and is not used up by trying)
        completeSignup(ticket, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_TICKET_INVALID));

        MvcResult reset = mvc.perform(json(ApiPaths.Auth.PASSWORD_RESET, Map.of("ticket", ticket,
                        "password", NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();
        SignIn second = read(reset.getResponse());

        // the old device is signed out, the new one stays
        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(first.refreshToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(second.refreshToken())))
                .andExpect(status().isOk());

        login(email, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_INVALID_CREDENTIALS));
        login(email, NEW_PASSWORD).andExpect(status().isOk());

        // the ticket was used
        mvc.perform(json(ApiPaths.Auth.PASSWORD_RESET, Map.of("ticket", ticket, "password", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_TICKET_INVALID));

        // weak new password
        String again = ticket(email, CodePurpose.RESET_PASSWORD);
        mvc.perform(json(ApiPaths.Auth.PASSWORD_RESET, Map.of("ticket", again, "password", "weak")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_WEAK_PASSWORD));
    }

    // ------------------------------------------------------------------ accounts without a password

    @Test
    void anAccountOpenedWithTheEmailCodeGetsItsPasswordOnTheSameUser() throws Exception {
        String email = uniqueEmail("pw-magic-first");
        SignIn magic = signIn(email, CHROME_WINDOWS, "EG", null);
        UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();

        login(email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PasswordService.CODE_PASSWORD_NOT_SET));

        mvc.perform(json(ApiPaths.Auth.SIGNUP_REQUEST, Map.of("email", email))).andExpect(status().isNoContent());
        completeSignup(ticket(email, CodePurpose.SIGN_UP), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.hasPassword").value(true));

        login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(userId.toString()));
        // the earlier device is still signed in (sign-up does not sign other devices out)
        mvc.perform(post(ApiPaths.Auth.REFRESH).cookie(cookie(magic.refreshToken()))).andExpect(status().isOk());
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.hasPassword()).isTrue();
        assertThat(user.getPasswordHash()).doesNotContain(PASSWORD);
    }

    // ------------------------------------------------------------------ purposes never mix

    @Test
    void codesOfOnePurposeNeverWorkForAnother() throws Exception {
        String email = uniqueEmail("pw-purpose");
        IssuedMagicLink signUpCode = magicLinkService.issueCode(email, CodePurpose.SIGN_UP, "127.0.0.1");

        // a sign-up code never signs anyone in through /auth/magic/verify
        mvc.perform(json(ApiPaths.Auth.MAGIC_VERIFY, Map.of("email", email, "code", signUpCode.code())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_INVALID));
        mvc.perform(json(ApiPaths.Auth.MAGIC_VERIFY, Map.of("token", signUpCode.token())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_INVALID));
        // nor opens a password reset
        verifyCode(email, signUpCode.code(), CodePurpose.RESET_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(MagicLinkService.CODE_INVALID));
        assertThat(userRepository.findByEmailIgnoreCase(email)).isEmpty();

        // a sign-in code never opens a sign-up
        IssuedMagicLink signInCode = magicLinkService.issue(email, "127.0.0.1");
        if (!signInCode.code().equals(signUpCode.code())) { // two random codes: one chance in a million to match
            verifyCode(email, signInCode.code(), CodePurpose.SIGN_UP)
                    .andExpect(status().isUnauthorized());
        }
        verifyCode(email, signInCode.code(), CodePurpose.SIGN_IN)
                .andExpect(status().isUnauthorized());

        // the sign-up code still works for its own purpose
        verifyCode(email, signUpCode.code(), CodePurpose.SIGN_UP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value((int) MagicLinkService.TICKET_TTL.toSeconds()));
    }

    // ------------------------------------------------------------------ helpers

    /** POST with a JSON body and a browser User-Agent. */
    private MockHttpServletRequestBuilder json(String path, Map<String, ?> body) throws Exception {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.USER_AGENT, CHROME_WINDOWS)
                .content(objectMapper.writeValueAsString(body));
    }

    private ResultActions verifyCode(String email, String code, CodePurpose purpose) throws Exception {
        return mvc.perform(json(ApiPaths.Auth.CODE_VERIFY, Map.of("email", email, "code", code,
                "purpose", purpose.name())));
    }

    /** Issues a code of {@code purpose} (the raw code the e-mail would carry) and exchanges it for a ticket. */
    private String ticket(String email, CodePurpose purpose) throws Exception {
        IssuedMagicLink issued = magicLinkService.issueCode(email, purpose, "127.0.0.1");
        MvcResult result = verifyCode(email, issued.code(), purpose).andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("ticket").asText();
    }

    private ResultActions completeSignup(String ticket, String password) throws Exception {
        return mvc.perform(json(ApiPaths.Auth.SIGNUP_COMPLETE, Map.of("ticket", ticket, "password", password,
                "acceptedTerms", true)));
    }

    private MvcResult signUp(String email, String password) throws Exception {
        return completeSignup(ticket(email, CodePurpose.SIGN_UP), password).andExpect(status().isOk()).andReturn();
    }

    private ResultActions resetPassword(String email, String password) throws Exception {
        String ticket = ticket(email, CodePurpose.RESET_PASSWORD);
        return mvc.perform(json(ApiPaths.Auth.PASSWORD_RESET, Map.of("ticket", ticket, "password", password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(json(ApiPaths.Auth.PASSWORD_LOGIN, Map.of("email", email, "password", password)));
    }
}
