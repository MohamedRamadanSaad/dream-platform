package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.common.web.CountryResolver;
import com.saadat.payments.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /checkout — server-side priced order + provider checkout URL. */
@RestController
@RequiredArgsConstructor
public class CheckoutController {

    private final PaymentService paymentService;
    private final CountryResolver countryResolver;

    @PostMapping(ApiPaths.Checkout.ROOT)
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest body, HttpServletRequest request) {
        return paymentService.checkout(AuthPrincipal.current().userId(), body, countryResolver.resolve(request));
    }
}
