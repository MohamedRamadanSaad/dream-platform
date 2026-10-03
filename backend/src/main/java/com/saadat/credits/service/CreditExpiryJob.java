package com.saadat.credits.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every hour: writes off the unused part of purchases past their expiry (one user per transaction). */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditExpiryJob {

    private final CreditExpiryService expiryService;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    public void run() {
        try {
            int users = 0;
            int credits = 0;
            for (UUID userId : expiryService.usersWithDueExpiry()) {
                try {
                    credits += expiryService.expireDueLocked(userId);
                    users++;
                } catch (RuntimeException e) {
                    log.error("Credit expiry failed for user {}", userId, e);
                }
            }
            if (users > 0) {
                log.info("Credit expiry: {} user(s) settled, {} credit(s) expired", users, credits);
            }
        } catch (RuntimeException e) {
            log.error("Credit expiry job failed", e);
        }
    }
}
