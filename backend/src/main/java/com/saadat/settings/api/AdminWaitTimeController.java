package com.saadat.settings.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.ValidationException;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET/PUT /admin/wait-time — typed view over the {@code wait.*} settings for the interpreter's "reply time" screen
 * (ROLE_INTERPRETER via SecurityConfig). {@code busy} is returned as stored (not auto-reset-adjusted) so the form
 * round-trips; the public endpoint applies {@code autoResetAt}.
 */
@RestController
@RequiredArgsConstructor
public class AdminWaitTimeController {

    /** Mirrors frontend {@code WaitTimeSettings}. */
    public record WaitTimeSettings(boolean busy, int normalHours, int busyMinDays, int busyMaxDays,
                                   String messageAr, String messageEn, Instant autoResetAt) {
    }

    private final SettingsService settings;

    @GetMapping(ApiPaths.Admin.WAIT_TIME)
    public WaitTimeSettings get() {
        return new WaitTimeSettings(
                settings.getBool(SettingKeys.WAIT_BUSY, false),
                settings.getInt(SettingKeys.WAIT_NORMAL_HOURS),
                settings.getInt(SettingKeys.WAIT_BUSY_MIN_DAYS),
                settings.getInt(SettingKeys.WAIT_BUSY_MAX_DAYS),
                settings.getString(SettingKeys.WAIT_MESSAGE_AR, ""),
                settings.getString(SettingKeys.WAIT_MESSAGE_EN, ""),
                settings.getInstant(SettingKeys.WAIT_AUTO_RESET_AT).orElse(null));
    }

    @PutMapping(ApiPaths.Admin.WAIT_TIME)
    public WaitTimeSettings update(@RequestBody WaitTimeSettings body) {
        if (body.normalHours() < 1) {
            throw new ValidationException("normalHours must be at least 1", "INVALID_WAIT_TIME");
        }
        if (body.busyMinDays() < 1 || body.busyMaxDays() < body.busyMinDays()) {
            throw new ValidationException("busy days must satisfy 1 <= min <= max", "INVALID_WAIT_TIME");
        }
        Map<String, String> values = new HashMap<>();
        values.put(SettingKeys.WAIT_BUSY, Boolean.toString(body.busy()));
        values.put(SettingKeys.WAIT_NORMAL_HOURS, Integer.toString(body.normalHours()));
        values.put(SettingKeys.WAIT_BUSY_MIN_DAYS, Integer.toString(body.busyMinDays()));
        values.put(SettingKeys.WAIT_BUSY_MAX_DAYS, Integer.toString(body.busyMaxDays()));
        values.put(SettingKeys.WAIT_MESSAGE_AR, body.messageAr() == null ? "" : body.messageAr().trim());
        values.put(SettingKeys.WAIT_MESSAGE_EN, body.messageEn() == null ? "" : body.messageEn().trim());
        values.put(SettingKeys.WAIT_AUTO_RESET_AT, body.autoResetAt() == null ? null : body.autoResetAt().toString());
        settings.putAll(values, AuthPrincipal.current().userId());
        return get();
    }
}
