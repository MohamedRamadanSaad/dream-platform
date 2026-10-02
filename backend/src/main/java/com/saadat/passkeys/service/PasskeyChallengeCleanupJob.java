package com.saadat.passkeys.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every hour: deletes expired passkey challenges (used or not), so anonymous sign-in attempts leave nothing behind. */
@Slf4j
@Component
public class PasskeyChallengeCleanupJob {

    private final PasskeyChallenges challenges;

    public PasskeyChallengeCleanupJob(PasskeyChallenges challenges) {
        this.challenges = challenges;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purge() {
        try {
            int removed = challenges.purgeExpired();
            if (removed > 0) {
                log.debug("Removed {} expired passkey challenges", removed);
            }
        } catch (RuntimeException e) {
            log.error("Passkey challenge cleanup failed", e);
        }
    }
}
