package com.saadat.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code app_settings}. The Java field is {@code settingKey} (column {@code key}) to avoid HQL keywords. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(name = "key", nullable = false, updatable = false, length = 100)
    private String settingKey;

    @Column(name = "value", columnDefinition = "text")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private SettingType type;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PrePersist
    @PreUpdate
    void touch() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }
}
