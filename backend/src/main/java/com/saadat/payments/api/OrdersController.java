package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.payments.service.AdminOrderFilter;
import com.saadat.payments.service.DreamAttachments;
import com.saadat.payments.service.LeftoverWithdrawalService;
import com.saadat.payments.service.OrderQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /me/orders, GET /me/orders/{id}, POST /me/orders/{id}/release-dreams, GET /admin/orders (+ /counts, /{id}). */
@RestController
@RequiredArgsConstructor
public class OrdersController {

    private final OrderQueryService orderQueryService;
    private final DreamAttachments dreamAttachments;
    private final LeftoverWithdrawalService leftoverWithdrawalService;

    @GetMapping(ApiPaths.Me.ORDERS)
    public PageResponse<OrderDto> myOrders(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return orderQueryService.mine(AuthPrincipal.current().userId(), page, size);
    }

    @GetMapping(ApiPaths.Me.ORDER)
    public OrderDto myOrder(@PathVariable UUID id) {
        return orderQueryService.mine(AuthPrincipal.current().userId(), id);
    }

    /** Lets the order go of its dreams (all, or {@code dreamIds}); they return to the drafts. */
    @PostMapping(ApiPaths.Me.ORDER_RELEASE_DREAMS)
    public ReleaseDreamsResponse releaseDreams(@PathVariable UUID id,
                                               @Valid @RequestBody(required = false) ReleaseDreamsRequest body) {
        return new ReleaseDreamsResponse(dreamAttachments.release(AuthPrincipal.current().userId(), id,
                body == null ? null : body.dreamIds()));
    }

    /** {@code dreamIds} absent or empty = every dream of the order. */
    public record ReleaseDreamsRequest(@Size(max = 50) List<UUID> dreamIds) {
    }

    /** The dreams that left the order. */
    public record ReleaseDreamsResponse(List<UUID> released) {
    }

    /**
     * {@code scope}: REVIEW | DOMESTIC | INTERNATIONAL | UNPAID | ALL (default, CSV export); {@code q} = order number;
     * filters (all optional, combinable): status (repeatable or comma list), provider, country, currency, from, to
     * (ISO dates, Riyadh days, {@code to} inclusive), minAmount, maxAmount, packageId, flag (REVOKED |
     * LEFTOVER_WITHDRAWN | RESENT). 400 INVALID_FILTER (+ field) for a bad value.
     */
    @GetMapping(ApiPaths.Admin.ORDERS)
    public PageResponse<AdminOrderRow> adminOrders(@RequestParam(required = false) AdminOrderDtos.Scope scope,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) List<String> status,
                                                   @RequestParam(required = false) String provider,
                                                   @RequestParam(required = false) String country,
                                                   @RequestParam(required = false) String currency,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) String minAmount,
                                                   @RequestParam(required = false) String maxAmount,
                                                   @RequestParam(required = false) String packageId,
                                                   @RequestParam(required = false) String flag) {
        return orderQueryService.admin(scope, q, AdminOrderFilter.parse(status, provider, country, currency, from, to,
                minAmount, maxAmount, packageId, flag), page, size);
    }

    /** Tab badges {review, domestic, international, unpaid}; honour the same filters (and q) as the list. */
    @GetMapping(ApiPaths.Admin.ORDER_COUNTS)
    public AdminOrderDtos.Counts adminOrderCounts(@RequestParam(required = false) String q,
                                                  @RequestParam(required = false) List<String> status,
                                                  @RequestParam(required = false) String provider,
                                                  @RequestParam(required = false) String country,
                                                  @RequestParam(required = false) String currency,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String minAmount,
                                                  @RequestParam(required = false) String maxAmount,
                                                  @RequestParam(required = false) String packageId,
                                                  @RequestParam(required = false) String flag) {
        return orderQueryService.counts(AdminOrderFilter.parse(status, provider, country, currency, from, to,
                minAmount, maxAmount, packageId, flag), q);
    }

    /** Withdraws the unused credits of an approved InstaPay order (interpreter only). */
    @PostMapping(ApiPaths.Admin.ORDER_WITHDRAW_LEFTOVER)
    public AdminOrderDtos.Detail withdrawLeftover(@PathVariable UUID id,
                                                  @Valid @RequestBody WithdrawLeftoverRequest body) {
        return leftoverWithdrawalService.withdraw(AuthPrincipal.current().userId(), id, body.reason(), body.note());
    }

    /** Gives the withdrawn credits of an order back (interpreter only). */
    @PostMapping(ApiPaths.Admin.ORDER_RESTORE_LEFTOVER)
    public AdminOrderDtos.Detail restoreLeftover(@PathVariable UUID id) {
        return leftoverWithdrawalService.restore(AuthPrincipal.current().userId(), id);
    }

    /** Why the credits are withdrawn (the buyer's text depends on it); {@code note} is required for OTHER. */
    public record WithdrawLeftoverRequest(@NotNull LeftoverWithdrawalService.Reason reason,
                                          @Size(max = 500) String note) {
    }

    @GetMapping(ApiPaths.Admin.ORDER)
    public AdminOrderDtos.Detail adminOrder(@PathVariable UUID id) {
        return orderQueryService.detail(id);
    }
}
