package com.saadat.support.domain;

/** support_tickets.status: NEW → IN_PROGRESS (any number of updates) → CLOSED (final); NEW → CLOSED directly. */
public enum SupportTicketStatus {
    NEW,
    IN_PROGRESS,
    CLOSED
}
