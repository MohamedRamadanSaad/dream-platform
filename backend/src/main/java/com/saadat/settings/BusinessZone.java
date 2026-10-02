package com.saadat.settings;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The business time zone (setting {@code schedule.time_zone}) used for days, hours and months of analytics and
 * reports; UTC if the setting is empty or invalid. {@link ZoneId#getId()} is also valid for PostgreSQL
 * {@code AT TIME ZONE}.
 */
@Component
public class BusinessZone {

    static final ZoneId FALLBACK = ZoneId.of("UTC");

    private final SettingsService settings;

    public BusinessZone(SettingsService settings) {
        this.settings = settings;
    }

    public ZoneId zone() {
        String id = settings.getString(SettingKeys.SCHEDULE_TIME_ZONE, null);
        if (id == null || id.isBlank()) {
            return FALLBACK;
        }
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            return FALLBACK;
        }
    }
}
