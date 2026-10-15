package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.payments.service.DreamAttachments;
import com.saadat.payments.service.OrderQueryService;
import jakarta.validation.Valid;
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

    /** {@code scope}: REVIEW | DOMESTIC | INTERNATIONAL | ALL (default, kept for the CSV export). */
    @GetMapping(ApiPaths.Admin.ORDERS)
    public PageResponse<AdminOrderRow> adminOrders(@RequestParam(required = false) AdminOrderDtos.Scope scope,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size,
                                                   @RequestParam(required = false) String q) {
        return orderQueryService.admin(scope, q, page, size);
    }

    @GetMapping(ApiPaths.Admin.ORDER_COUNTS)
    public AdminOrderDtos.Counts adminOrderCounts() {
        return orderQueryService.counts();
    }

    @GetMapping(ApiPaths.Admin.ORDER)
    public AdminOrderDtos.Detail adminOrder(@PathVariable UUID id) {
        return orderQueryService.detail(id);
    }
}
