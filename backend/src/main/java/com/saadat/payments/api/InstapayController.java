package com.saadat.payments.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.payments.service.InstapayService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** InstaPay manual transfers: the buyer's payment page and the interpreter's review list and settings. */
@RestController
@RequiredArgsConstructor
public class InstapayController {

    private final InstapayService instapayService;

    @GetMapping(ApiPaths.Me.ORDER_INSTAPAY)
    public InstapayDtos.OrderView view(@PathVariable UUID id) {
        return instapayService.view(AuthPrincipal.current().userId(), id);
    }

    @PostMapping(ApiPaths.Me.ORDER_INSTAPAY)
    public InstapayDtos.OrderView submit(@PathVariable UUID id, @Valid @RequestBody InstapayDtos.SubmitRequest body) {
        return instapayService.submit(AuthPrincipal.current().userId(), id, body);
    }

    @GetMapping(ApiPaths.Admin.INSTAPAY_TRANSFERS)
    public InstapayDtos.TransferList transfers() {
        return instapayService.transfers();
    }

    @GetMapping(ApiPaths.Admin.INSTAPAY_TRANSFER_COUNTS)
    public InstapayDtos.Counts counts() {
        return instapayService.counts();
    }

    @PostMapping(ApiPaths.Admin.INSTAPAY_TRANSFER_APPROVE)
    public InstapayDtos.TransferRow approve(@PathVariable UUID id,
                                            @Valid @RequestBody InstapayDtos.ApproveRequest body) {
        return instapayService.approve(AuthPrincipal.current().userId(), id, body.reference());
    }

    @PostMapping(ApiPaths.Admin.INSTAPAY_TRANSFER_REJECT)
    public InstapayDtos.TransferRow reject(@PathVariable UUID id,
                                           @Valid @RequestBody(required = false) InstapayDtos.RejectRequest body) {
        return instapayService.reject(AuthPrincipal.current().userId(), id, body == null ? null : body.note());
    }

    @PostMapping(ApiPaths.Admin.INSTAPAY_TRANSFER_REVOKE)
    public InstapayDtos.TransferRow revoke(@PathVariable UUID id,
                                           @Valid @RequestBody(required = false) InstapayDtos.RevokeRequest body) {
        return instapayService.revoke(AuthPrincipal.current().userId(), id, body == null ? null : body.note());
    }

    @GetMapping(ApiPaths.Admin.INSTAPAY_SETTINGS)
    public InstapayDtos.Settings settings() {
        return instapayService.settings();
    }

    @PutMapping(ApiPaths.Admin.INSTAPAY_SETTINGS)
    public InstapayDtos.Settings saveSettings(@Valid @RequestBody InstapayDtos.Settings body) {
        return instapayService.saveSettings(AuthPrincipal.current().userId(), body);
    }
}
