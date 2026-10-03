package com.saadat.support.api;

import com.saadat.common.api.ApiPaths;
import com.saadat.common.api.PageResponse;
import com.saadat.common.security.AuthPrincipal;
import com.saadat.support.api.SupportTicketDtos.MessageRequest;
import com.saadat.support.api.SupportTicketDtos.SupportTicketCounts;
import com.saadat.support.api.SupportTicketDtos.SupportTicketDetail;
import com.saadat.support.api.SupportTicketDtos.SupportTicketRow;
import com.saadat.support.domain.SupportTicketStatus;
import com.saadat.support.service.SupportTicketService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Interpreter routes for the support mailbox tickets (ROLE_INTERPRETER via SecurityConfig, like every /admin/**
 * route). Errors are RFC 7807: 404 unknown ticket, 409 {@code TICKET_CLOSED}, 400 blank / too long message.
 */
@RestController
@RequiredArgsConstructor
public class AdminSupportController {

    private final SupportTicketService service;

    @GetMapping(ApiPaths.Admin.SUPPORT_TICKETS)
    public PageResponse<SupportTicketRow> list(@RequestParam(required = false) SupportTicketStatus status,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return service.list(status, page, size);
    }

    @GetMapping(ApiPaths.Admin.SUPPORT_TICKET_COUNTS)
    public SupportTicketCounts counts() {
        return service.counts();
    }

    @GetMapping(ApiPaths.Admin.SUPPORT_TICKET)
    public SupportTicketDetail detail(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping(ApiPaths.Admin.SUPPORT_TICKET_IN_PROGRESS)
    public SupportTicketDetail inProgress(@PathVariable UUID id, @Valid @RequestBody MessageRequest body) {
        return service.markInProgress(actor(), id, body.message());
    }

    @PostMapping(ApiPaths.Admin.SUPPORT_TICKET_CLOSE)
    public SupportTicketDetail close(@PathVariable UUID id, @Valid @RequestBody MessageRequest body) {
        return service.close(actor(), id, body.message());
    }

    private static UUID actor() {
        return AuthPrincipal.current().userId();
    }
}
