package com.saadat.payments.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 5 minutes: INITIATED orders past {@code expires_at} → EXPIRED. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {

    private final PaymentService paymentService;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void expire() {
        try {
            int n = paymentService.expireOverdue();
            if (n > 0) {
                log.info("Expired {} unpaid orders", n);
            }
        } catch (RuntimeException e) {
            log.error("Order expiry job failed", e);
        }
    }
}
