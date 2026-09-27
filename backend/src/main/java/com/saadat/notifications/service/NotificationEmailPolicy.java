package com.saadat.notifications.service;

import com.saadat.common.domain.NotificationType;
import com.saadat.mail.MailTemplates;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single place that decides which notification types also send an e-mail, and with which template
 * (contract rule 10). Types without an entry are in-app + push only.
 */
@Component
public class NotificationEmailPolicy {

    private static final Map<NotificationType, String> TEMPLATES;

    static {
        Map<NotificationType, String> m = new EnumMap<>(NotificationType.class);
        m.put(NotificationType.DREAM_SUBMITTED, MailTemplates.DREAM_SUBMITTED);
        m.put(NotificationType.DREAM_RECEIVED, MailTemplates.DREAM_RECEIVED);
        m.put(NotificationType.INTERPRETER_QUESTION, MailTemplates.INTERPRETER_QUESTION);
        m.put(NotificationType.USER_REPLIED, MailTemplates.USER_REPLIED);
        m.put(NotificationType.INTERPRETATION_READY, MailTemplates.INTERPRETATION_READY);
        // PAYMENT_SUCCESS: in-app + push only here — the receipt e-mail (MailTemplates.PAYMENT_RECEIPT, with
        // paidAt and a send-once ref) is sent by payments.service.PaymentService itself.
        // PROMOTION: in-app + push only (marketing e-mail requires an explicit opt-in flow)
        TEMPLATES = Collections.unmodifiableMap(m);
    }

    public Optional<String> templateFor(NotificationType type) {
        return Optional.ofNullable(TEMPLATES.get(type));
    }
}
