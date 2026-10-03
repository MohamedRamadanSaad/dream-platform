package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.ApiException;
import com.saadat.common.error.ForbiddenException;
import com.saadat.common.error.NotFoundException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.payments.domain.Order;
import com.saadat.payments.provider.MockPaymentProvider;
import com.saadat.payments.provider.MorProvider;
import com.saadat.payments.provider.KashierProvider;
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
    private final KashierProvider kashierProvider;
    private final MorProvider morProvider;
    private final MockPaymentProvider mockProvider;
    private final OrderRepository orderRepository;

    /**
     * Kashier webhook: JSON {@code {event, data}}, signature in header {@code x-kashier-signature}. Only a successful
     * {@code pay} reaches {@link PaymentService#confirm}; other verified events are acknowledged and logged.
     */
    @PostMapping(ApiPaths.Webhooks.KASHIER)
    public ResponseEntity<Void> kashier(@RequestBody(required = false) String body, HttpServletRequest request) {
        if (!kashierProvider.verifySignature(request, body)) {
            log.warn("Kashier webhook with invalid signature rejected");
            throw new UnauthorizedException("Invalid signature", "INVALID_SIGNATURE");
        }
        if (!kashierProvider.isPaymentResult(body)) {
            log.info("Kashier webhook {} acknowledged without action", kashierProvider.describe(body));
            return ResponseEntity.ok().build();
        }
        WebhookEvent event = kashierProvider.parseWebhook(request, body);
        PaymentService.ConfirmOutcome outcome = paymentService.confirm(event);
        log.info("Kashier webhook txn={} → {}", event.providerTxnId(), outcome);
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
