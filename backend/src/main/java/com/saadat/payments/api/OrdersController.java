package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.payments.service.OrderQueryService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /me/orders, GET /me/orders/{id}, GET /admin/orders (+ /counts, /{id}). */
@RestController
@RequiredArgsConstructor
public class OrdersController {

    private final OrderQueryService orderQueryService;

    @GetMapping(ApiPaths.Me.ORDERS)
    public PageResponse<OrderDto> myOrders(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return orderQueryService.mine(AuthPrincipal.current().userId(), page, size);
    }

    @GetMapping(ApiPaths.Me.ORDER)
    public OrderDto myOrder(@PathVariable UUID id) {
        return orderQueryService.mine(AuthPrincipal.current().userId(), id);
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
