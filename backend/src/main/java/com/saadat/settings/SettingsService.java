package com.saadat.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.audit.AuditService;
import com.saadat.common.error.ValidationException;
import com.saadat.settings.domain.AppSetting;
import com.saadat.settings.domain.SettingType;
import com.saadat.settings.repo.AppSettingRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Typed access to {@code app_settings} with a 60-second in-memory cache (whole table snapshot).
 * Writes go through {@link #put}/{@link #putAll}: validated against the row's {@link SettingType}, audited,
 * and the cache is invalidated (again after commit).
 *
 * <p>Getters throw {@link IllegalStateException} for unknown keys or unparseable values — that is a
 * programming/seed error, not a client error. Use the {@code (key, default)} overloads where a missing
 * value is acceptable.
 */
@Slf4j
@Service
public class SettingsService {

    public static final String AUDIT_ACTION_UPDATE = "SETTING_UPDATE";
    public static final String AUDIT_ENTITY = "app_settings";

    static final Duration TTL = Duration.ofSeconds(60);
    private static final String SNAPSHOT = "snapshot";

    private final AppSettingRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Single-entry map holding the current snapshot (ConcurrentHashMap for safe publication). */
    private final ConcurrentHashMap<String, Snapshot> cache = new ConcurrentHashMap<>();

    public SettingsService(AppSettingRepository repository, AuditService auditService, ObjectMapper objectMapper,
                           Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** Raw value (may be null); empty if the key does not exist. */
    public Optional<String> find(String key) {
        Entry entry = snapshot().values().get(key);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.value());
    }

    public boolean exists(String key) {
        return snapshot().values().containsKey(key);
    }

    /** String value; null if the stored value is null. Throws for unknown keys. */
    public String getString(String key) {
        return require(key).value();
    }

    public String getString(String key, String defaultValue) {
        String v = find(key).orElse(null);
        return v == null ? defaultValue : v;
    }

    public int getInt(String key) {
        String v = requireNonBlank(key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Setting " + key + " is not an int: " + v, e);
        }
    }

    public int getInt(String key, int defaultValue) {
        String v = find(key).orElse(null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} is not an int, using default", key);
            return defaultValue;
        }
    }

    public long getLong(String key) {
        String v = requireNonBlank(key);
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Setting " + key + " is not a long: " + v, e);
        }
    }

    public boolean getBool(String key) {
        String v = requireNonBlank(key).trim();
        if ("true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("false".equalsIgnoreCase(v)) {
            return false;
        }
        throw new IllegalStateException("Setting " + key + " is not a boolean: " + v);
    }

    public boolean getBool(String key, boolean defaultValue) {
        String v = find(key).orElse(null);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(v.trim());
    }

    public <T> T getJson(String key, Class<T> type) {
        String v = requireNonBlank(key);
        try {
            return objectMapper.readValue(v, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Setting " + key + " is not valid JSON for " + type.getSimpleName(), e);
        }
    }

    public <T> T getJson(String key, TypeReference<T> type) {
        String v = requireNonBlank(key);
        try {
            return objectMapper.readValue(v, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Setting " + key + " is not valid JSON", e);
        }
    }

    /** ISO-8601 instant, empty when null/blank. */
    public Optional<Instant> getInstant(String key) {
        String v = find(key).orElse(null);
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(v.trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("Setting " + key + " is not an ISO instant: " + v, e);
        }
    }

    /** Comma-separated list, trimmed, blanks removed (e.g. interpreter.emails). */
    public List<String> getList(String key) {
        String v = find(key).orElse(null);
        if (v == null || v.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** All settings as key → value (sorted by key). Values may be null. */
    public Map<String, String> getAll() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Entry> e : snapshot().values().entrySet()) {
            out.put(e.getKey(), e.getValue().value());
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    /** Updates one existing key (validated by type), audits, invalidates the cache. */
    @Transactional
    public void put(String key, String value, UUID actorId) {
        AppSetting setting = repository.findById(key)
                .orElseThrow(() -> new ValidationException("Unknown setting key: " + key, "UNKNOWN_SETTING"));
        String normalized = validate(setting, value);
        String before = setting.getValue();
        setting.setValue(normalized);
        setting.setUpdatedAt(clock.instant());
        setting.setUpdatedBy(actorId);
        repository.save(setting);
        auditService.record(actorId, AUDIT_ACTION_UPDATE, AUDIT_ENTITY, key, valueMap(before), valueMap(normalized));
        invalidateNowAndAfterCommit();
    }

    /** Validates every entry first, then updates them all in one transaction. */
    @Transactional
    public void putAll(Map<String, String> values, UUID actorId) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            AppSetting setting = repository.findById(e.getKey())
                    .orElseThrow(() -> new ValidationException("Unknown setting key: " + e.getKey(), "UNKNOWN_SETTING"));
            validate(setting, e.getValue());
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            put(e.getKey(), e.getValue(), actorId);
        }
    }

    /** Drops the cache; the next read reloads from the database. */
    public void invalidate() {
        cache.clear();
    }

    // ------------------------------------------------------------------ internals

    private String validate(AppSetting setting, String value) {
        SettingType type = setting.getType();
        String key = setting.getSettingKey();
        if (type == SettingType.STRING) {
            return value;
        }
        if (value == null || value.isBlank()) {
            throw new ValidationException("Setting " + key + " requires a " + type + " value", "INVALID_SETTING");
        }
        String v = value.trim();
        switch (type) {
            case INT -> {
                try {
                    Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new ValidationException("Setting " + key + " must be an integer", "INVALID_SETTING");
                }
                return v;
            }
            case BOOL -> {
                String lower = v.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new ValidationException("Setting " + key + " must be true or false", "INVALID_SETTING");
                }
                return lower;
            }
            case JSON -> {
                try {
                    objectMapper.readTree(v);
                } catch (JsonProcessingException e) {
                    throw new ValidationException("Setting " + key + " must be valid JSON", "INVALID_SETTING");
                }
                return v;
            }
            default -> {
                return v;
            }
        }
    }

    private void invalidateNowAndAfterCommit() {
        invalidate();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // after commit AND after rollback: never keep a snapshot read inside the transaction
                    invalidate();
                }
            });
        }
    }

    private static Map<String, Object> valueMap(String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("value", value);
        return m;
    }

    private Entry require(String key) {
        Entry entry = snapshot().values().get(key);
        if (entry == null) {
            throw new IllegalStateException("Unknown setting: " + key);
        }
        return entry;
    }

    private String requireNonBlank(String key) {
        String v = require(key).value();
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Setting " + key + " has no value");
        }
        return v;
    }

    private Snapshot snapshot() {
        Instant now = clock.instant();
        Snapshot current = cache.get(SNAPSHOT);
        if (current != null && current.loadedAt().plus(TTL).isAfter(now)) {
            return current;
        }
        synchronized (this) {
            current = cache.get(SNAPSHOT);
            if (current != null && current.loadedAt().plus(TTL).isAfter(now)) {
                return current;
            }
            Map<String, Entry> values = new HashMap<>();
            for (AppSetting s : repository.findAll()) {
                values.put(s.getSettingKey(), new Entry(s.getValue(), s.getType()));
            }
            Snapshot fresh = new Snapshot(Collections.unmodifiableMap(values), now);
            cache.put(SNAPSHOT, fresh);
            return fresh;
        }
    }

    private record Entry(String value, SettingType type) {
    }

    private record Snapshot(Map<String, Entry> values, Instant loadedAt) {
    }
}
