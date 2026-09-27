package com.saadat.config;

import com.saadat.config.props.AppProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Refuses to start the {@code prod} profile with mock auth or mock payments enabled (e.g. via a stray env var). */
@Component
@Profile("prod")
public class ProductionSafetyCheck {

    private final AppProperties properties;

    public ProductionSafetyCheck(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void verify() {
        if (properties.getAuth().isAllowMock()) {
            throw new IllegalStateException("app.auth.allow-mock must be false in prod");
        }
        if (properties.getPayments().isMock()) {
            throw new IllegalStateException("app.payments.mock must be false in prod");
        }
        if (!properties.getAuth().isRefreshCookieSecure()) {
            throw new IllegalStateException("app.auth.refresh-cookie-secure must be true in prod");
        }
    }
}
