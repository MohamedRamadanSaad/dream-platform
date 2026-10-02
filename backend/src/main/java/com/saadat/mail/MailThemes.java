package com.saadat.mail;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Registry of e-mail themes, loaded once from the classpath file {@code mail/themes.json} (a JSON array of
 * {@link MailTheme}). Adding a theme = adding an entry to that file (+ its two images under
 * {@code frontend/public/email/themes/<key>/}). The default is {@link #PREFERRED_DEFAULT} when present, else
 * the first entry.
 */
@Component
public class MailThemes {

    static final String REGISTRY = "mail/themes.json";
    public static final String PREFERRED_DEFAULT = "crescent-night";

    private final List<MailTheme> themes;
    private final Map<String, MailTheme> byKey;
    private final String defaultKey;

    public MailThemes(ObjectMapper objectMapper) {
        List<MailTheme> loaded;
        try (InputStream in = new ClassPathResource(REGISTRY).getInputStream()) {
            loaded = objectMapper.readValue(in, new TypeReference<List<MailTheme>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the e-mail theme registry " + REGISTRY, e);
        }
        Map<String, MailTheme> map = new LinkedHashMap<>();
        for (MailTheme theme : loaded) {
            if (theme == null || theme.key() == null || theme.key().isBlank()) {
                throw new IllegalStateException("E-mail theme without a key in " + REGISTRY);
            }
            if (map.put(theme.key(), theme) != null) {
                throw new IllegalStateException("Duplicate e-mail theme key in " + REGISTRY + ": " + theme.key());
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("The e-mail theme registry " + REGISTRY + " is empty");
        }
        this.themes = List.copyOf(map.values());
        this.byKey = Collections.unmodifiableMap(map);
        this.defaultKey = map.containsKey(PREFERRED_DEFAULT) ? PREFERRED_DEFAULT : themes.get(0).key();
    }

    /** Every theme, in registry order. */
    public List<MailTheme> all() {
        return themes;
    }

    /** The theme with this key (trimmed); empty for null/blank/unknown keys. */
    public Optional<MailTheme> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key.trim()));
    }

    public boolean exists(String key) {
        return find(key).isPresent();
    }

    /** Key of the registry default (used when the settings name no known theme). */
    public String defaultKey() {
        return defaultKey;
    }

    public MailTheme defaultTheme() {
        return byKey.get(defaultKey);
    }
}
