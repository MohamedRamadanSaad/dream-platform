package com.saadat.passkeys.service;

import com.saadat.config.props.AppProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The WebAuthn relying party of this site: RP ID = the host of {@code app.frontend-url} and the allowed origin = that
 * URL's origin, unless overridden by {@code app.passkeys.rp-id} / {@code app.passkeys.origins} (env PASSKEY_RP_ID,
 * PASSKEY_ORIGINS — infrastructure, e.g. local or test setups).
 */
@Slf4j
@Configuration
public class PasskeyConfig {

    @Bean
    public WebAuthnRelyingParty webAuthnRelyingParty(AppProperties properties) {
        AppProperties.Passkeys config = properties.getPasskeys();
        String rpId = config.getRpId() == null || config.getRpId().isBlank()
                ? WebAuthnRelyingParty.hostOf(properties.getFrontendUrl())
                : config.getRpId().trim();
        List<String> origins = config.getOrigins() == null ? List.of()
                : config.getOrigins().stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
        if (origins.isEmpty()) {
            origins = List.of(WebAuthnRelyingParty.originOf(properties.getFrontendUrl()));
        }
        WebAuthnRelyingParty relyingParty = new WebAuthnRelyingParty(rpId, origins);
        log.info("Passkeys: relying party {} for origins {}", relyingParty.rpId(), relyingParty.origins());
        return relyingParty;
    }
}
