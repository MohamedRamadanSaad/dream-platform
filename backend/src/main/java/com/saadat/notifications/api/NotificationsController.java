package com.saadat.notifications.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.notifications.service.NotificationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@link ApiPaths.Notifications} routes (newest first). */
@RestController
public class NotificationsController {

    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping(ApiPaths.Notifications.ROOT)
    public PageResponse<NotificationDto> list(@RequestParam(value = "page", required = false) Integer page,
                                              @RequestParam(value = "size", required = false) Integer size) {
        UUID userId = AuthPrincipal.current().userId();
        return PageResponse.from(notificationService.list(userId, Pages.of(page, size)), NotificationDto::from);
    }

    @PostMapping(ApiPaths.Notifications.READ_ALL)
    public ResponseEntity<Void> readAll() {
        notificationService.markAllRead(AuthPrincipal.current().userId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Notifications.READ)
    public ResponseEntity<Void> read(@PathVariable("id") UUID id) {
        notificationService.markRead(AuthPrincipal.current().userId(), id);
        return ResponseEntity.noContent().build();
    }
}
