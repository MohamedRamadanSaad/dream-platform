package com.saadat.notifications.service;

import com.saadat.common.domain.Role;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Who the interpreter(s) are: active users with role INTERPRETER (granted at login from interpreter.emails). */
@Component
public class InterpreterDirectory {

    private final UserRepository userRepository;

    public InterpreterDirectory(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<UUID> interpreterUserIds() {
        return userRepository.findByRole(Role.INTERPRETER).stream()
                .filter(u -> !u.isDeleted())
                .map(User::getId)
                .toList();
    }
}
