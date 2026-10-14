package com.saadat.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.auth.service.AuthService;
import com.saadat.auth.service.MagicLinkService;
import com.saadat.auth.service.MagicLinkService.IssuedMagicLink;
import com.saadat.common.api.ApiPaths;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Google sign-ins are capped, so an account opened with the e-mail code stays on the e-mail code: Google is refused
 * for it (409 USE_MAGIC_LINK). An account opened with Google keeps working with Google, and with the e-mail code too.
 */
class GoogleAfterMagicLinkIntegrationTest extends IntegrationTestBase {

    @Autowired
    MagicLinkService magicLinkService;

    @Test
    void googleIsRefusedForAnAccountOpenedWithTheEmailCode() throws Exception {
        String email = "code-first-" + UUID.randomUUID() + "@example.com";
        signInWithCode(email).andExpect(status().isOk());

        google(email)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AuthService.CODE_USE_MAGIC_LINK));
        // the same e-mail in other letter case is the same account
        google(email.toUpperCase())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(AuthService.CODE_USE_MAGIC_LINK));

        // the e-mail code still works
        signInWithCode(email).andExpect(status().isOk());
    }

    @Test
    void anAccountOpenedWithGoogleKeepsBothWays() throws Exception {
        String email = "google-first-" + UUID.randomUUID() + "@example.com";
        google(email).andExpect(status().isOk()).andExpect(jsonPath("$.user.providers[0]").value("GOOGLE"));
        signInWithCode(email).andExpect(status().isOk());
        google(email).andExpect(status().isOk()).andExpect(jsonPath("$.user.email").value(email));
    }

    private ResultActions google(String email) throws Exception {
        return mvc.perform(post(ApiPaths.Auth.GOOGLE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"mock:" + email + "\"}"));
    }

    private ResultActions signInWithCode(String email) throws Exception {
        IssuedMagicLink link = magicLinkService.issue(email, "127.0.0.1");
        return mvc.perform(post(ApiPaths.Auth.MAGIC_VERIFY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"code\":\"" + link.code() + "\"}"));
    }
}
