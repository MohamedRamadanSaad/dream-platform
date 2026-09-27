package com.saadat.users.service;

import com.saadat.auth.service.RefreshTokenService;
import com.saadat.common.domain.DreamStatus;
import com.saadat.common.domain.Locale;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.dreams.repo.DreamRepository;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.publicapi.WaitTimeView;
import com.saadat.push.service.PushSubscriptionService;
import com.saadat.users.api.MeDtos.DashboardSummary;
import com.saadat.users.api.MeDtos.PreferencesRequest;
import com.saadat.users.api.UserDto;
import com.saadat.users.domain.User;
import com.saadat.users.repo.AuthIdentityRepository;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's own account: profile, preferences, dashboard counters and deletion. */
@Slf4j
@Service
public class AccountService {

    /** Deleted accounts get {@code deleted-<userId>@invalid} (the .invalid TLD is reserved, RFC 2606). */
    static final String DELETED_EMAIL_PREFIX = "deleted-";
    static final String DELETED_EMAIL_DOMAIN = "@invalid";

    private final UserRepository userRepository;
    private final AuthIdentityRepository identityRepository;
    private final RefreshTokenService refreshTokenService;
    private final PushSubscriptionService pushSubscriptionService;
    private final DreamRepository dreamRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final NotificationRepository notificationRepository;
    private final WaitTimeView waitTimeView;
    private final UserDtoMapper mapper;
    private final Clock clock;

    public AccountService(UserRepository userRepository, AuthIdentityRepository identityRepository,
                          RefreshTokenService refreshTokenService, PushSubscriptionService pushSubscriptionService,
                          DreamRepository dreamRepository, CreditLedgerRepository creditLedgerRepository,
                          NotificationRepository notificationRepository, WaitTimeView waitTimeView,
                          UserDtoMapper mapper, Clock clock) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.refreshTokenService = refreshTokenService;
        this.pushSubscriptionService = pushSubscriptionService;
        this.dreamRepository = dreamRepository;
        this.creditLedgerRepository = creditLedgerRepository;
        this.notificationRepository = notificationRepository;
        this.waitTimeView = waitTimeView;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** The active (non-deleted) user; 401 otherwise (e.g. a still-valid access token of a deleted account). */
    @Transactional(readOnly = true)
    public User requireActive(UUID userId) {
        return userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new UnauthorizedException("Account unavailable", "ACCOUNT_UNAVAILABLE"));
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        return mapper.toDto(requireActive(userId));
    }

    @Transactional
    public UserDto updatePreferences(UUID userId, PreferencesRequest request) {
        User user = requireActive(userId);
        if (request.locale() != null) {
            user.setLocale(request.locale());
        }
        if (request.name() != null) {
            user.setName(request.name().trim());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.birthDate() != null) {
            user.setBirthDate(request.birthDate());
        }
        if (request.marketingOptIn() != null) {
            user.setMarketingOptIn(request.marketingOptIn());
        }
        userRepository.save(user);
        return mapper.toDto(user);
    }

    /**
     * Soft delete: e-mail anonymised, name cleared, tokens revoked, identities unlinked (so the Google account
     * or e-mail can sign up again as a new account), push subscriptions removed. Dreams/orders are kept
     * for accounting but are no longer linked to any personal data.
     */
    @Transactional
    public void delete(UUID userId) {
        User user = requireActive(userId);
        user.setEmail(DELETED_EMAIL_PREFIX + user.getId() + DELETED_EMAIL_DOMAIN);
        user.setName("");
        user.setGender(null);
        user.setMarketingOptIn(false);
        user.setDeletedAt(clock.instant());
        userRepository.save(user);
        identityRepository.deleteAll(identityRepository.findByUserId(userId));
        refreshTokenService.revokeAll(userId);
        pushSubscriptionService.removeAll(userId);
        log.info("Account {} deleted", userId);
    }

    @Transactional(readOnly = true)
    public DashboardSummary dashboard(UUID userId, Locale requestLocale) {
        User user = requireActive(userId);
        Locale locale = requestLocale != null ? requestLocale : user.getLocale();
        return new DashboardSummary(
                dreamRepository.countByUserIdAndStatus(userId, DreamStatus.DRAFT),
                dreamRepository.countByUserIdAndStatus(userId, DreamStatus.IN_REVIEW),
                dreamRepository.countByUserIdAndStatus(userId, DreamStatus.AWAITING_USER_REPLY),
                dreamRepository.countByUserIdAndStatus(userId, DreamStatus.INTERPRETED),
                creditLedgerRepository.balance(userId),
                notificationRepository.countByUserIdAndReadAtIsNull(userId),
                waitTimeView.current(locale));
    }
}
