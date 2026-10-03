package com.saadat.youtube.service;

import com.saadat.auth.service.SecureTokens;
import com.saadat.config.props.AppProperties;
import com.saadat.users.repo.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "stop these e-mails" link of the new-video e-mail: {@code <site>/unsubscribe?u=<userId>&t=<token>}, where the
 * token is an HMAC of the user id (no sign-in needed, cannot be guessed for another user).
 */
@Service
public class ChannelMailOptOut {

    private static final String PURPOSE = "channel-mail-optout:";

    private final AppProperties properties;
    private final UserRepository userRepository;

    public ChannelMailOptOut(AppProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    public String token(UUID userId) {
        return SecureTokens.hmacSha256(properties.getAuth().getJwtSecret(), PURPOSE + userId);
    }

    /** Link placed at the bottom of the e-mail. */
    public String link(UUID userId) {
        String site = properties.getFrontendUrl() == null ? "" : properties.getFrontendUrl().replaceAll("/+$", "");
        return site + "/unsubscribe?u=" + userId + "&t=" + token(userId);
    }

    /** @return false when the token does not match the user (nothing changes). */
    @Transactional
    public boolean stop(UUID userId, String token) {
        if (userId == null || token == null || !SecureTokens.equalsConstantTime(token(userId), token.trim())) {
            return false;
        }
        userRepository.findById(userId).ifPresent(u -> {
            u.setMarketingOptIn(false);
            userRepository.save(u);
        });
        return true;
    }
}
