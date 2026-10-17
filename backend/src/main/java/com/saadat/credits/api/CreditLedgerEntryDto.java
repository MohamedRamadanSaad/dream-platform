package com.saadat.credits.api;

import com.saadat.common.domain.LedgerKind;
import com.saadat.common.domain.LedgerReason;
import com.saadat.payments.domain.CreditLedgerEntry;
import java.time.Instant;
import java.util.UUID;

/**
 * types.ts CreditLedgerEntry. {@code kind} (V41): APPROVAL_REVOKED / LEFTOVER_WITHDRAWN / LEFTOVER_RESTORED on the
 * REVOKE / REFUND rows of those actions, else null; {@code orderNumber}: the readable number of {@code orderId}.
 */
public record CreditLedgerEntryDto(UUID id, int delta, LedgerReason reason, UUID orderId, UUID dreamId,
                                   Instant createdAt, LedgerKind kind, String orderNumber) {

    public static CreditLedgerEntryDto from(CreditLedgerEntry e) {
        return from(e, null);
    }

    public static CreditLedgerEntryDto from(CreditLedgerEntry e, String orderNumber) {
        return new CreditLedgerEntryDto(e.getId(), e.getDelta(), e.getReason(), e.getOrderId(), e.getDreamId(),
                e.getCreatedAt(), e.getKind(), orderNumber);
    }
}
