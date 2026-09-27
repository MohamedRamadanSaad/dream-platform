package com.saadat.users.domain;

import java.time.LocalDate;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Row of {@code users}. {@code email} is always stored lower-case (DB CHECK). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "name", nullable = false, length = 200)
    private String name = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", length = 16)
    private Gender gender;

    /** Date of birth (age is derived; null until the user fills it in). */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "locale", nullable = false, length = 8)
    private Locale locale = Locale.AR;

    @Column(name = "country_code", columnDefinition = "char(2)")
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "country_source", length = 16)
    private CountrySource countrySource;

    @Column(name = "onboarded", nullable = false)
    private boolean onboarded;

    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Normalizes and sets the e-mail (trim + lower-case). */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
