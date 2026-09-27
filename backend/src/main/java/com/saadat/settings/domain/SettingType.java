package com.saadat.settings.domain;

/** app_settings.type — governs validation on PUT /admin/settings. */
public enum SettingType {
    STRING,
    INT,
    BOOL,
    JSON
}
