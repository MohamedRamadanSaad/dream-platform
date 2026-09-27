package com.saadat.credits.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.credits.service.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /me/credits — balance + ledger (newest first). */
@RestController
@RequiredArgsConstructor
public class CreditsController {

    private final CreditService creditService;

    @GetMapping(ApiPaths.Me.CREDITS)
    public CreditsSummaryDto credits() {
        return creditService.summary(AuthPrincipal.current().userId());
    }
}
