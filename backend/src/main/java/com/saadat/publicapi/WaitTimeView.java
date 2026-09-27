package com.saadat.publicapi;

import com.saadat.common.domain.Locale;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps the {@code wait.*} settings to the public {@link WaitTime} DTO in the requested locale. Busy mode is
 * considered off once {@code wait.auto_reset_at} has passed (even before a job flips the setting).
 */
@Component
public class WaitTimeView {

    private final SettingsService settings;
    private final Clock clock;

    public WaitTimeView(SettingsService settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public WaitTime current(Locale locale) {
        return new WaitTime(
                isBusy(),
                settings.getInt(SettingKeys.WAIT_BUSY_MIN_DAYS),
                settings.getInt(SettingKeys.WAIT_BUSY_MAX_DAYS),
                settings.getInt(SettingKeys.WAIT_NORMAL_HOURS),
                message(locale));
    }

    public boolean isBusy() {
        if (!settings.getBool(SettingKeys.WAIT_BUSY, false)) {
            return false;
        }
        Optional<Instant> resetAt = settings.getInstant(SettingKeys.WAIT_AUTO_RESET_AT);
        return resetAt.isEmpty() || resetAt.get().isAfter(clock.instant());
    }

    public String message(Locale locale) {
        String key = locale == Locale.EN ? SettingKeys.WAIT_MESSAGE_EN : SettingKeys.WAIT_MESSAGE_AR;
        return settings.getString(key, "");
    }
}
