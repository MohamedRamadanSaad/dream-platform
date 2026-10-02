package com.saadat.users.api;

import com.saadat.auth.service.DeviceService;
import com.saadat.auth.web.CookieUtil;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.users.api.MeDtos.DeviceDto;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's signed-in devices (docs/SESSIONS_PROFILE_CONTRACT.md §2), for both roles:
 * <ul>
 *   <li>GET /me/devices — active refresh-token families, most recently active first</li>
 *   <li>DELETE /me/devices/{id} — signs that device out (404 when it is not the caller's); 204</li>
 *   <li>POST /me/devices/sign-out-others — signs out every device except the calling one; 204</li>
 * </ul>
 * A signed-out device loses its refresh token at once and its access token on its next request.
 */
@RestController
public class MeDevicesController {

    private final DeviceService deviceService;
    private final CookieUtil cookieUtil;

    public MeDevicesController(DeviceService deviceService, CookieUtil cookieUtil) {
        this.deviceService = deviceService;
        this.cookieUtil = cookieUtil;
    }

    @GetMapping(ApiPaths.Me.DEVICES)
    public List<DeviceDto> devices(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        AuthPrincipal me = AuthPrincipal.current();
        Locale requestLocale = acceptLanguage == null || acceptLanguage.isBlank() ? null : Locale.fromTag(acceptLanguage);
        return deviceService.list(me.userId(), me.sessionId(), requestLocale);
    }

    @DeleteMapping(ApiPaths.Me.DEVICE)
    public ResponseEntity<Void> signOut(@PathVariable UUID id, HttpServletResponse response) {
        AuthPrincipal me = AuthPrincipal.current();
        deviceService.signOut(me.userId(), id);
        if (id.equals(me.sessionId())) {
            cookieUtil.clearRefreshToken(response); // this very device signed itself out
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(ApiPaths.Me.DEVICES_SIGN_OUT_OTHERS)
    public ResponseEntity<Void> signOutOthers() {
        AuthPrincipal me = AuthPrincipal.current();
        deviceService.signOutOthers(me.userId(), me.sessionId());
        return ResponseEntity.noContent().build();
    }
}
