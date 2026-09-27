package com.saadat.settings.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.settings.SettingsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** GET/PUT /admin/settings — raw key/value access for the interpreter (ROLE_INTERPRETER via SecurityConfig). */
@RestController
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SettingsService settingsService;

    @GetMapping(ApiPaths.Admin.SETTINGS)
    public Map<String, String> getAll() {
        return settingsService.getAll();
    }

    /** Partial update: only the keys present in the body change. Unknown keys / bad types → 422. */
    @PutMapping(ApiPaths.Admin.SETTINGS)
    public Map<String, String> update(@RequestBody Map<String, String> body) {
        settingsService.putAll(body, AuthPrincipal.current().userId());
        return settingsService.getAll();
    }
}
