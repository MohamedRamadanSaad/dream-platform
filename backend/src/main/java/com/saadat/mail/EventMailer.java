package com.saadat.mail;

import com.saadat.common.domain.Locale;
import com.saadat.common.tx.AfterCommit;
import com.saadat.notifications.repo.EmailLogRepository;
import com.saadat.notifications.service.InterpreterDirectory;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * One e-mail per meaningful action (contract §5). Recipients and model are captured NOW (e.g. the address of an
 * account that is about to be anonymised); the e-mail is queued on the async pool only after the surrounding
 * transaction commits, so a mail problem can never break or roll back the action, and a rolled-back action
 * never mails anyone. Switched-off events ({@code mail.event.<template>} = false) are dropped right away.
 */
@Component
public class EventMailer {

    static final String MODEL_NAME = "name";

    private final MailService mailService;
    private final AfterCommit afterCommit;
    private final InterpreterDirectory interpreterDirectory;
    private final UserRepository userRepository;
    private final EmailLogRepository emailLogRepository;

    public EventMailer(MailService mailService, AfterCommit afterCommit, InterpreterDirectory interpreterDirectory,
                       UserRepository userRepository, EmailLogRepository emailLogRepository) {
        this.mailService = mailService;
        this.afterCommit = afterCommit;
        this.interpreterDirectory = interpreterDirectory;
        this.userRepository = userRepository;
        this.emailLogRepository = emailLogRepository;
    }

    /** E-mails {@code user} (name added to the model) after commit; {@code ref} goes to email_log.ref. */
    public void toUser(User user, String template, Map<String, Object> model, String ref) {
        if (user == null || user.getEmail() == null || !mailService.isEnabled(template)) {
            return;
        }
        UUID userId = user.getId();
        String to = user.getEmail();
        Locale locale = user.getLocale();
        Map<String, Object> captured = new LinkedHashMap<>(model);
        captured.putIfAbsent(MODEL_NAME, user.getName());
        afterCommit.run("mail:" + template, () -> mailService.sendAsync(userId, to, template, locale, captured, ref));
    }

    /** Like {@link #toUser} but skipped when an e-mail with this template and ref was already logged. */
    public void toUserOnce(User user, String template, Map<String, Object> model, String ref) {
        if (ref != null && emailLogRepository.existsByTemplateAndRef(template, ref)) {
            return;
        }
        toUser(user, template, model, ref);
    }

    /**
     * E-mails every interpreter after commit; {@code modelFor} builds the model in each recipient's locale
     * (e.g. a localized country name). The recipient's name is added as {@code name}.
     */
    public void toInterpreters(String template, String ref, Function<Locale, Map<String, Object>> modelFor) {
        if (!mailService.isEnabled(template)) {
            return;
        }
        afterCommit.run("mail:" + template, () -> {
            for (User interpreter : userRepository.findAllById(interpreterDirectory.interpreterUserIds())) {
                Map<String, Object> model = new LinkedHashMap<>(modelFor.apply(interpreter.getLocale()));
                model.put(MODEL_NAME, interpreter.getName());
                mailService.sendAsync(interpreter.getId(), interpreter.getEmail(), template, interpreter.getLocale(),
                        model, ref);
            }
        });
    }
}
