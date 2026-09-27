package com.saadat.payments.service;

import com.saadat.common.api.PageResponse;
import com.saadat.common.api.Pages;
import com.saadat.common.error.NotFoundException;
import com.saadat.payments.api.AdminOrderRow;
import com.saadat.payments.api.OrderDto;
import com.saadat.payments.domain.Order;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of orders: /me/orders, /me/orders/{id}, /admin/orders. */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<OrderDto> mine(UUID userId, Integer page, Integer size) {
        return PageResponse.from(orderRepository.findByUserIdOrderByCreatedAtDesc(userId, Pages.of(page, size)),
                OrderDto::from);
    }

    @Transactional(readOnly = true)
    public OrderDto mine(UUID userId, UUID orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId).map(OrderDto::from)
                .orElseThrow(() -> NotFoundException.of("Order", orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderDto> ofUser(UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(OrderDto::from).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderRow> all(Integer page, Integer size) {
        Page<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc(Pages.of(page, size));
        List<UUID> userIds = orders.getContent().stream().map(Order::getUserId).distinct().toList();
        Map<UUID, String> names = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        Function<Order, AdminOrderRow> mapper =
                o -> AdminOrderRow.of(OrderDto.from(o), names.getOrDefault(o.getUserId(), ""));
        return PageResponse.from(orders, mapper);
    }
}
