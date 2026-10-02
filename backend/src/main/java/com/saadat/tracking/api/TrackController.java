package com.saadat.tracking.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.common.web.CountryResolver;
import com.saadat.tracking.service.TrackingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /public/track — open route (rate limited per IP); a valid Bearer token attaches the user. Always 204,
 * also when the view is ignored (interpreter paths, bots).
 */
@RestController
@RequiredArgsConstructor
public class TrackController {

    private final TrackingService trackingService;
    private final CountryResolver countryResolver;

    @PostMapping(ApiPaths.Public.TRACK)
    public ResponseEntity<Void> track(@Valid @RequestBody TrackRequest body, HttpServletRequest request) {
        UUID userId = AuthPrincipal.currentOptional().map(AuthPrincipal::userId).orElse(null);
        trackingService.track(body, request.getHeader(HttpHeaders.USER_AGENT), countryResolver.resolve(request),
                userId);
        return ResponseEntity.noContent().build();
    }
}
