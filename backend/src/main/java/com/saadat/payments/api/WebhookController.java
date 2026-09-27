package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ForbiddenException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.MockPaymentProvider;
import com.saadat.payments.provider.MorProvider;
import com.saadat.payments.provider.PaymobProvider;
import com.saadat.payments.provider.WebhookEvent;
import com.saadat.payments.repo.OrderRepository;
import com.saadat.payments.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider callbacks (open routes; authenticity is the signature). Every accepted callback answers 200,
 * including duplicates, so providers stop retrying.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;
    private final PaymobProvider paymobProvider;
    private final MorProvider morProvider;
    private final MockPaymentProvider mockProvider;
    private final OrderRepository orderRepository;

    /** Paymob "transaction processed" callback: JSON body {@code {type, obj}}, HMAC in query param {@code hmac}. */
    @PostMapping(ApiPaths.Webhooks.PAYMOB)
    public ResponseEntity<Void> paymob(@RequestBody(required = false) String body, HttpServletRequest request) {
        if (!paymobProvider.verifySignature(request, body)) {
            log.warn("Paymob webhook with invalid HMAC rejected");
            throw new UnauthorizedException("Invalid signature", "INVALID_SIGNATURE");
        }
        WebhookEvent event = paymobProvider.parseWebhook(request, body);
        PaymentService.ConfirmOutcome outcome = paymentService.confirm(event);
        log.info("Paymob webhook txn={} → {}", event.providerTxnId(), outcome);
        return ResponseEntity.ok().build();
    }

    /** TODO(mor): implement once the merchant-of-record vendor is configured (see MorProvider). */
    @PostMapping(ApiPaths.Webhooks.MOR)
    public ResponseEntity<Void> mor(@RequestBody(required = false) String body, HttpServletRequest request) {
        if (!morProvider.isConfigured()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "not-implemented", "NOT_CONFIGURED",
                    "Merchant-of-record webhooks are not configured");
        }
        if (!morProvider.verifySignature(request, body)) {
            throw new UnauthorizedException("Invalid signature", "INVALID_SIGNATURE");
        }
        paymentService.confirm(morProvider.parseWebhook(request, body));
        return ResponseEntity.ok().build();
    }

    /** Local/dev only (app.payments.mock=true): simulates the provider callback for an order. */
    @PostMapping(ApiPaths.Webhooks.MOCK)
    public ResponseEntity<Void> mock(@PathVariable UUID orderId,
                                     @RequestParam(defaultValue = "true") boolean success) {
        if (!mockProvider.isEnabled()) {
            throw new ForbiddenException("Mock payments are disabled");
        }
        Order order = orderRepository.findById(orderId).orElseThrow(() -> NotFoundException.of("Order", orderId));
        PaymentService.ConfirmOutcome outcome = paymentService.confirm(mockProvider.eventFor(order, success));
        log.info("Mock webhook order={} success={} → {}", orderId, success, outcome);
        return ResponseEntity.ok().build();
    }
}
