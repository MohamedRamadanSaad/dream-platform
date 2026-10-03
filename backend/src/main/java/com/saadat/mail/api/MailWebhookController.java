package com.saadat.mail.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.error.NotConfiguredException;
import com.saadat.common.error.UnauthorizedException;
import com.saadat.config.props.AppProperties;
import com.saadat.mail.inbound.SupportAutoReplyService;
import com.saadat.support.service.SupportTicketBodyService;
import com.saadat.support.service.SupportTicketService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /webhooks/mail/inbound — the support mailbox host (Hostinger, event {@code message.received}) calls this with
 * {@code Authorization: Bearer <secret>}; the secret is {@code app.mail.webhook-secret} (env MAIL_WEBHOOK_SECRET),
 * compared in constant time. Blank secret → 503 (endpoint off); wrong/missing token → 401. Once authenticated the
 * answer is always 200: {@code {"status":"ok"}} or {@code {"status":"skipped","reason":"..."}} (open route like
 * every /webhooks/** path; authenticity is the secret).
 *
 * <p>Every message that passes the guards (written by a person) also opens a support ticket (sender, subject, time
 * and the text when the payload carries it; otherwise the text is fetched over IMAP in the background) — even when
 * the auto-reply itself is skipped for its cooldown or switched off.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MailWebhookController {

    private static final String BEARER_PREFIX = "Bearer ";
    static final String REASON_INVALID_PAYLOAD = "invalid-payload";

    private final AppProperties properties;
    private final SupportAutoReplyService autoReplyService;
    private final SupportTicketService ticketService;
    private final SupportTicketBodyService bodyService;
    private final ObjectMapper objectMapper;

    /** Response body; {@code reason} only when skipped. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InboundResponse(String status, String reason) {
    }

    @PostMapping(ApiPaths.Webhooks.MAIL_INBOUND)
    public InboundResponse inbound(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                   @RequestBody(required = false) String body) {
        String secret = properties.getMail().getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            throw new NotConfiguredException("The inbound mail webhook is not configured (MAIL_WEBHOOK_SECRET)");
        }
        if (!matches(bearerToken(authorization), secret.trim())) {
            log.warn("Inbound mail webhook with a wrong secret rejected");
            throw new UnauthorizedException("Invalid webhook secret", "INVALID_SIGNATURE");
        }
        JsonNode payload;
        try {
            payload = body == null || body.isBlank() ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            payload = null;
        }
        if (payload == null || !payload.isContainerNode()) {
            log.info("Inbound mail webhook: payload is empty or not JSON, skipped");
            return new InboundResponse(SupportAutoReplyService.STATUS_SKIPPED, REASON_INVALID_PAYLOAD);
        }
        SupportAutoReplyService.Screening screening = autoReplyService.screen(payload);
        if (screening.fromPerson()) {
            ticketService.openFromInbound(screening.message()) // never throws
                    .filter(ticket -> ticket.getBody() == null)
                    .ifPresent(ticket -> bodyService.scheduleFetch(ticket.getId())); // background, never throws
        }
        SupportAutoReplyService.Outcome outcome = autoReplyService.reply(screening);
        return new InboundResponse(outcome.status(), outcome.reason());
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return "";
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /** Constant-time comparison (SHA-256 of both, so the length is not leaked either). */
    static boolean matches(String token, String secret) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(token.getBytes(StandardCharsets.UTF_8));
            byte[] b = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
