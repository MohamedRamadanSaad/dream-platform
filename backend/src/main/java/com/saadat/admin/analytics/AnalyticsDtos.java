package com.saadat.admin.analytics;

import java.time.LocalDate;

import com.saadat.common.domain.AuthProvider;
import com.saadat.common.domain.Currency;
import com.saadat.common.domain.Gender;
import com.saadat.common.domain.Locale;
import com.saadat.credits.api.CreditsSummaryDto;
import com.saadat.dreams.api.DreamSummaryDto;
import com.saadat.payments.api.OrderDto;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** types.ts admin analytics shapes. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record RevenueDto(Currency currency, BigDecimal amount) {
    }

    /**
     * types.ts AdminSummary. {@code pendingPayments}: InstaPay transfers waiting for the interpreter; {@code revenue}:
     * per currency, the buyers' own amounts (reference only); {@code revenueEgp}: settled EGP of the last 30 days.
     */
    public record AdminSummaryDto(long pendingPayments, long inReview, long inProgress, long awaitingReply,
                                  long overdue, long interpretedToday, List<RevenueDto> revenue, double revenueEgp,
                                  double avgResponseHours,
                                  /** Dreams waiting for their payment to be approved again (V40; queue tab count). */
                                  long awaitingPayment) {
    }

    /**
     * types.ts CountryStat. {@code revenueBase}: settled EGP (the base currency); {@code revenueByCurrency}: the
     * buyers' own currencies (reference only).
     */
    public record CountryStatDto(String countryCode, String countryName, long visits, long dreams, double revenueBase,
                                 long users, List<RevenueDto> revenueByCurrency) {
    }

    /** types.ts AdminCountryDashboard. */
    public record AdminCountryDashboardDto(String period, Currency baseCurrency, List<CountryStatDto> topVisits,
                                           List<CountryStatDto> topDreams, List<CountryStatDto> topRevenue) {
    }

    /**
     * types.ts AdminUserRow. Money paid (SUCCESS orders): {@code totalPaid} in the user's own currency
     * {@code totalPaidCurrency} (EGP for a country whose default currency is EGP, i.e. Egypt; USD for everyone else,
     * through {@code pricing.fx_to_usd}); {@code totalPaidEgp} = the same in EGP (settled EGP), the ranking base;
     * {@code totalPaidBase} = {@code totalPaidEgp} (kept for compatibility; it was USD before).
     */
    public record AdminUserRowDto(UUID id, String name, String email, String countryCode, long visits, long dreams,
                                  long drafts, double totalPaidBase, Double avgRating, Instant lastSeenAt,
                                  double totalPaid, Currency totalPaidCurrency, double totalPaidEgp) {
    }

    /** types.ts AdminUser360 (AdminUserRow + detail, flattened; {@code dreams} is the count). */
    public record AdminUser360Dto(
            UUID id,
            String name,
            String email,
            String countryCode,
            long visits,
            long dreams,
            long drafts,
            double totalPaidBase,
            Double avgRating,
            Instant lastSeenAt,
            Gender gender,
            LocalDate birthDate,
            Integer age,
            Locale locale,
            List<AuthProvider> providers,
            Instant createdAt,
            CreditsSummaryDto credits,
            List<OrderDto> orders,
            List<DreamSummaryDto> dreamList,
            List<UserTestimonialDto> testimonials,
            String notes,
            List<String> tags,
            /** Same money fields as AdminUserRow (the user's own currency, and EGP). */
            double totalPaid,
            Currency totalPaidCurrency,
            double totalPaidEgp) {
    }

    public record UserTestimonialDto(UUID dreamId, int rating, String comment, boolean approved) {
    }

    /** PUT /admin/users/{id}/notes. */
    public record UserNotesRequest(String notes, List<String> tags) {
    }

    /** POST /admin/users/{id}/credits. */
    public record CreditAdjustRequest(@NotNull Integer delta, String reason) {
    }
}
