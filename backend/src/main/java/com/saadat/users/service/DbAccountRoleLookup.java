package com.saadat.users.service;

import com.saadat.common.domain.Role;
import com.saadat.common.security.AccountRoleLookup;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link AccountRoleLookup} backed by the users table (one primary-key read per authenticated request). */
@Component
@RequiredArgsConstructor
public class DbAccountRoleLookup implements AccountRoleLookup {

    private final UserRepository userRepository;

    @Override
    public Optional<Role> currentRole(UUID userId) {
        return userRepository.findById(userId).filter(u -> !u.isDeleted()).map(User::getRole);
    }
}
