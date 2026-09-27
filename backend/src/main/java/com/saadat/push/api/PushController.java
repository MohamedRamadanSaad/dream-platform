package com.saadat.push.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.push.service.PushSubscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@link ApiPaths.Push} routes. */
@RestController
public class PushController {

    private final PushSubscriptionService service;

    public PushController(PushSubscriptionService service) {
        this.service = service;
    }

    @PostMapping(ApiPaths.Push.SUBSCRIPTIONS)
    public ResponseEntity<Void> subscribe(@Valid @RequestBody PushSubscriptionRequest body) {
        service.subscribe(AuthPrincipal.current().userId(), body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(ApiPaths.Push.SUBSCRIPTIONS)
    public ResponseEntity<Void> unsubscribe(@RequestParam("endpoint") String endpoint) {
        service.unsubscribe(AuthPrincipal.current().userId(), endpoint);
        return ResponseEntity.noContent().build();
    }
}
