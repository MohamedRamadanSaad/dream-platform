package com.saadat.passkeys.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.Locale;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.passkeys.api.PasskeyDtos.PasskeyDto;
import com.saadat.passkeys.api.PasskeyDtos.PasskeyRegistrationRequest;
import com.saadat.passkeys.api.PasskeyDtos.RegistrationOptionsResponse;
import com.saadat.passkeys.service.PasskeyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's passkeys (docs/PASSKEYS_CONTRACT.md), for both roles; every route needs a signed-in caller:
 * <ul>
 *   <li>POST /me/passkeys/registration/options — WebAuthn creation options + requestId</li>
 *   <li>POST /me/passkeys/registration — verifies and stores the new passkey; 201 PasskeyDto
 *       (422 PASSKEY_INVALID when it cannot be verified)</li>
 *   <li>GET /me/passkeys — newest first</li>
 *   <li>DELETE /me/passkeys/{id} — 204; 404 when it is not one of the caller's</li>
 * </ul>
 */
@RestController
public class MePasskeysController {

    private final PasskeyService passkeyService;

    public MePasskeysController(PasskeyService passkeyService) {
        this.passkeyService = passkeyService;
    }

    @PostMapping(ApiPaths.Me.PASSKEY_REGISTRATION_OPTIONS)
    public RegistrationOptionsResponse registrationOptions(
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        Locale requestLocale = acceptLanguage == null || acceptLanguage.isBlank() ? null : Locale.fromTag(acceptLanguage);
        return passkeyService.registrationOptions(AuthPrincipal.current().userId(), requestLocale);
    }

    @PostMapping(ApiPaths.Me.PASSKEY_REGISTRATION)
    public ResponseEntity<PasskeyDto> register(@Valid @RequestBody PasskeyRegistrationRequest body,
                                               @RequestHeader(value = HttpHeaders.USER_AGENT, required = false)
                                               String userAgent) {
        PasskeyDto created = passkeyService.register(AuthPrincipal.current().userId(), body, userAgent);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping(ApiPaths.Me.PASSKEYS)
    public List<PasskeyDto> list() {
        return passkeyService.list(AuthPrincipal.current().userId());
    }

    @DeleteMapping(ApiPaths.Me.PASSKEY)
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        passkeyService.delete(AuthPrincipal.current().userId(), id);
        return ResponseEntity.noContent().build();
    }
}
