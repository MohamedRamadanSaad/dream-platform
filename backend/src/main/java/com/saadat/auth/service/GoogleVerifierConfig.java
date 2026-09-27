package com.saadat.auth.service;

import com.saadat.config.props.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Picks the Google verifier: the real one, wrapped by the mock one when {@code app.auth.allow-mock=true}. */
@Slf4j
@Configuration
public class GoogleVerifierConfig {

    @Bean
    public GoogleTokenVerifier googleTokenVerifier(AppProperties properties) {
        GoogleTokenVerifier real = new GoogleApiTokenVerifier(properties.getGoogleClientId());
        if (properties.getAuth().isAllowMock()) {
            log.warn("Mock Google login is ENABLED (app.auth.allow-mock=true) — never use this in production");
            return new MockGoogleTokenVerifier(real, properties.getAuth().getMockEmail());
        }
        return real;
    }
}
