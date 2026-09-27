package com.saadat.credits.api;

import java.util.List;

/** types.ts CreditsSummary. */
public record CreditsSummaryDto(int balance, List<CreditLedgerEntryDto> entries) {
}
