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

/** GET /me/orders, GET /me/orders/{id}, GET /admin/orders. */
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

    @GetMapping(ApiPaths.Admin.ORDERS)
    public PageResponse<AdminOrderRow> adminOrders(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return orderQueryService.all(page, size);
    }
}
