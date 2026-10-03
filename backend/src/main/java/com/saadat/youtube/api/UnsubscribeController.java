package com.saadat.youtube.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.ValidationException;
import com.saadat.youtube.service.ChannelMailOptOut;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /public/unsubscribe {u, t}: stops new-video e-mails for that user (from the link in the e-mail). */
@RestController
public class UnsubscribeController {

    /** Body of the request: user id and the HMAC token from the link. */
    public record UnsubscribeRequest(UUID u, String t) {
    }

    private final ChannelMailOptOut optOut;

    public UnsubscribeController(ChannelMailOptOut optOut) {
        this.optOut = optOut;
    }

    @PostMapping(ApiPaths.Public.UNSUBSCRIBE)
    public ResponseEntity<Void> unsubscribe(@RequestBody UnsubscribeRequest body) {
        if (body == null || !optOut.stop(body.u(), body.t())) {
            throw new ValidationException("Invalid unsubscribe link", "INVALID_LINK");
        }
        return ResponseEntity.noContent().build();
    }
}
