package com.saadat.auth.service;

import com.saadat.common.domain.Role;
import com.saadat.common.web.LogMask;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps stored roles in line with the interpreter rules: every account holding INTERPRETER whose e-mail no longer
 * matches the rules (domain, setting interpreter.emails, bootstrap env) is set back to USER. Runs at startup and
 * whenever the interpreter settings change; sign-in applies the same rule to the signing-in account.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterpreterRoleSync {

    private final UserRepository userRepository;
    private final AuthService authService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        int changed = syncAll();
        if (changed > 0) {
            log.warn("Interpreter role revoked from {} account(s) that no longer match the interpreter rules", changed);
        }
    }

    /** Demotes interpreters that no longer match the rules; returns how many accounts changed. */
    public int syncAll() {
        int changed = 0;
        for (User user : userRepository.findByRole(Role.INTERPRETER)) {
            if (authService.expectedRole(user.getEmail()) != Role.INTERPRETER) {
                log.warn("Revoking INTERPRETER from {}", LogMask.email(user.getEmail()));
                user.setRole(Role.USER);
                userRepository.save(user);
                changed++;
            }
        }
        return changed;
    }
}
