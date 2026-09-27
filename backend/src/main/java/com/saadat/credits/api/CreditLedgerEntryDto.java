package com.saadat.credits.api;

import com.saadat.common.domain.LedgerReason;
import com.saadat.payments.domain.CreditLedgerEntry;
import java.time.Instant;
import java.util.UUID;

/** types.ts CreditLedgerEntry. */
public record CreditLedgerEntryDto(UUID id, int delta, LedgerReason reason, UUID orderId, UUID dreamId,
                                   Instant createdAt) {

    public static CreditLedgerEntryDto from(CreditLedgerEntry e) {
        return new CreditLedgerEntryDto(e.getId(), e.getDelta(), e.getReason(), e.getOrderId(), e.getDreamId(),
                e.getCreatedAt());
    }
}
