package com.saadat.users.service;

import com.saadat.common.domain.LedgerReason;
import com.saadat.common.domain.Locale;
import com.saadat.common.domain.OrderStatus;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.CreditLedgerRepository;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.pricing.repo.DreamPackageRepository;
import com.saadat.tracking.repo.PageViewRepository;
import com.saadat.users.api.MeDtos.LastPackage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user dashboard's activity tiles: credits used so far, when the user was last on the site, and the last
 * package bought. Read-only.
 */
@Component
public class DashboardActivity {

    /** A visit ends after 30 minutes without a page view (same rule as the visit cookie of the SPA). */
    static final Duration VISIT_GAP = Duration.ofMinutes(30);
    /** Same limit as page_views.session_id. */
    static final int VISIT_ID_MAX = 100;

    private final CreditLedgerRepository ledgerRepository;
    private final OrderRepository orderRepository;
    private final DreamPackageRepository packageRepository;
    private final PageViewRepository pageViewRepository;
    private final Clock clock;

    public DashboardActivity(CreditLedgerRepository ledgerRepository, OrderRepository orderRepository,
                             DreamPackageRepository packageRepository, PageViewRepository pageViewRepository,
                             Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.orderRepository = orderRepository;
        this.packageRepository = packageRepository;
        this.pageViewRepository = pageViewRepository;
        this.clock = clock;
    }

    /** Credits spent on dreams, refunds of dreams taken back (never negative). */
    @Transactional(readOnly = true)
    public long usedCredits(UUID userId) {
        long net = ledgerRepository.netDreamCredits(userId, LedgerReason.SUBMIT, LedgerReason.REFUND);
        return Math.max(0, -net);
    }

    /**
     * The last time the user was on the site before this visit (null on the first visit).
     *
     * @param currentVisit the SPA's visit id; when unknown, views of the last 30 minutes count as this visit
     */
    @Transactional(readOnly = true)
    public Instant lastVisitAt(UUID userId, String currentVisit) {
        Instant now = clock.instant();
        boolean known = currentVisit != null && !currentVisit.isBlank() && currentVisit.length() <= VISIT_ID_MAX;
        String visit = known ? currentVisit.trim() : "";
        Instant before = known ? now : now.minus(VISIT_GAP);
        return pageViewRepository.lastSeenBefore(userId, visit, before);
    }

    @Transactional(readOnly = true)
    public LastPackage lastPackage(UUID userId, Locale locale) {
        return orderRepository.findFirstByUserIdAndStatusOrderByPaidAtDescCreatedAtDesc(userId, OrderStatus.SUCCESS)
                .map(o -> new LastPackage(o.getId(), packageName(o, locale), o.getCredits(), o.getAmount(),
                        o.getCurrency(), o.getPaidAt() != null ? o.getPaidAt() : o.getCreatedAt()))
                .orElse(null);
    }

    private String packageName(Order order, Locale locale) {
        if (order.getPackageId() == null) {
            return order.getPackageNameSnapshot();
        }
        return packageRepository.findById(order.getPackageId())
                .map(p -> locale == Locale.EN ? p.getNameEn() : p.getNameAr())
                .filter(name -> name != null && !name.isBlank())
                .orElse(order.getPackageNameSnapshot());
    }
}
