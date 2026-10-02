package com.saadat.users.service;

import com.saadat.common.domain.Role;
import com.saadat.common.security.AccountRoleLookup;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link AccountRoleLookup} backed by the users table: one query per authenticated request — a primary-key read for
 * tokens without {@code sid}, the role plus an "is this refresh-token family still active" check otherwise.
 */
@Component
@RequiredArgsConstructor
public class DbAccountRoleLookup implements AccountRoleLookup {

    private final UserRepository userRepository;
    private final Clock clock;

    @Override
    public Optional<Role> currentRole(UUID userId, UUID sessionId) {
        if (sessionId == null) {
            return userRepository.findById(userId).filter(u -> !u.isDeleted()).map(User::getRole);
        }
        return userRepository.findActiveRoleInSession(userId, sessionId, clock.instant());
    }
}
