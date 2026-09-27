package com.saadat.youtube.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.audit.AuditService;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.youtube.api.YoutubeDtos.YoutubeRefreshResult;
import com.saadat.youtube.api.YoutubeDtos.YoutubeUnseen;
import com.saadat.youtube.service.YoutubeFeedService;
import com.saadat.youtube.service.YoutubeUnseenService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link ApiPaths.Youtube} routes + the interpreter's manual feed refresh. */
@RestController
public class YoutubeController {

    static final String AUDIT_ACTION_REFRESH = "YOUTUBE_REFRESH";
    static final String AUDIT_ENTITY = "youtube_videos";

    private final YoutubeUnseenService unseenService;
    private final YoutubeFeedService feedService;
    private final AuditService auditService;

    public YoutubeController(YoutubeUnseenService unseenService, YoutubeFeedService feedService,
                             AuditService auditService) {
        this.unseenService = unseenService;
        this.feedService = feedService;
        this.auditService = auditService;
    }

    @GetMapping(ApiPaths.Youtube.UNSEEN)
    public YoutubeUnseen unseen() {
        return unseenService.unseen(AuthPrincipal.current().userId());
    }

    @PostMapping(ApiPaths.Youtube.SEEN)
    public ResponseEntity<Void> seen() {
        unseenService.markSeen(AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Admin.YOUTUBE_REFRESH)
    public YoutubeRefreshResult refresh() {
        UUID actor = AuthPrincipal.current().userId();
        if (!feedService.isConfigured()) {
            throw new NotConfiguredException("YouTube channel id is not set (brand.youtube_channel_id)");
        }
        int fetched = feedService.refresh();
        YoutubeRefreshResult result = new YoutubeRefreshResult(fetched);
        auditService.record(actor, AUDIT_ACTION_REFRESH, AUDIT_ENTITY, null, null, result);
        return result;
    }
}
