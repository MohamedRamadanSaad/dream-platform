package com.saadat.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import com.saadat.youtube.service.ChannelMailOptOut;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** The "stop new-video e-mails" link: works with the right token only, without signing in. */
class ChannelMailOptOutIntegrationTest extends IntegrationTestBase {

    @Autowired
    ChannelMailOptOut optOut;

    @Test
    void theLinkStopsChannelEmailsAndAWrongTokenChangesNothing() throws Exception {
        User user = createUser("yt-stop", Role.USER);
        user.setMarketingOptIn(true);
        user = userRepository.save(user);
        User other = createUser("yt-other", Role.USER);

        assertThat(optOut.link(user.getId())).contains("/unsubscribe?u=" + user.getId() + "&t=");

        // another user's token does not work
        mvc.perform(post(ApiPaths.Public.UNSUBSCRIBE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"u\":\"" + user.getId() + "\",\"t\":\"" + optOut.token(other.getId()) + "\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(userRepository.findById(user.getId()).orElseThrow().isMarketingOptIn()).isTrue();

        mvc.perform(post(ApiPaths.Public.UNSUBSCRIBE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"u\":\"" + user.getId() + "\",\"t\":\"" + optOut.token(user.getId()) + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(userRepository.findById(user.getId()).orElseThrow().isMarketingOptIn()).isFalse();
    }
}
