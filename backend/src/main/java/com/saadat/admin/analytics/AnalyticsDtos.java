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

    /** types.ts AdminSummary. */
    public record AdminSummaryDto(long inReview, long awaitingReply, long overdue, long interpretedToday,
                                  List<RevenueDto> revenue, double avgResponseHours) {
    }

    /** types.ts CountryStat. */
    public record CountryStatDto(String countryCode, String countryName, long visits, long dreams, double revenueBase,
                                 long users) {
    }

    /** types.ts AdminCountryDashboard. */
    public record AdminCountryDashboardDto(String period, Currency baseCurrency, List<CountryStatDto> topVisits,
                                           List<CountryStatDto> topDreams, List<CountryStatDto> topRevenue) {
    }

    /** types.ts AdminUserRow. */
    public record AdminUserRowDto(UUID id, String name, String email, String countryCode, long visits, long dreams,
                                  long drafts, double totalPaidBase, Double avgRating, Instant lastSeenAt) {
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
            List<String> tags) {
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
