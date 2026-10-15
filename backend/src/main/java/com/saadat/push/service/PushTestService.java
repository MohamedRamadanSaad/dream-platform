package com.saadat.push.service;

import com.saadat.common.domain.Locale;
import com.saadat.common.domain.Role;
import com.saadat.mail.MessageText;
import com.saadat.notifications.repo.PushSubscriptionRepository;
import com.saadat.push.api.PushTestResult;
import com.saadat.settings.SettingKeys;
import com.saadat.settings.SettingsService;
import com.saadat.users.repo.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Send a test notification" on the account page: a localized push to every subscription of the caller only, so the
 * owner of a phone can check the whole chain (keys, push service, service worker) without waiting for a real event.
 */
@Service
public class PushTestService {

    static final String TITLE_KEY = "push.test.title";
    static final String BODY_KEY = "push.test.body";
    static final String USER_URL = "/me";
    static final String INTERPRETER_URL = "/admin";

    private final WebPushSender sender;
    private final PushSubscriptionRepository repository;
    private final UserRepository userRepository;
    private final MessageText messageText;
    private final SettingsService settings;

    public PushTestService(WebPushSender sender, PushSubscriptionRepository repository, UserRepository userRepository,
                           MessageText messageText, SettingsService settings) {
        this.sender = sender;
        this.repository = repository;
        this.userRepository = userRepository;
        this.messageText = messageText;
        this.settings = settings;
    }

    public PushTestResult sendTest(UUID userId, Role role) {
        int subscriptions = repository.findByUserId(userId).size();
        boolean configured = sender.isConfigured();
        if (!configured || subscriptions == 0) {
            return new PushTestResult(subscriptions, 0, configured);
        }
        Locale locale = userRepository.findById(userId).map(com.saadat.users.domain.User::getLocale).orElse(Locale.AR);
        String brand = settings.getString(locale == Locale.EN ? SettingKeys.BRAND_NAME_EN : SettingKeys.BRAND_NAME_AR, "");
        String title = brand.isBlank() ? messageText.get(TITLE_KEY, locale, Map.of()) : brand;
        String body = messageText.get(BODY_KEY, locale, Map.of());
        String url = role == Role.INTERPRETER ? INTERPRETER_URL : USER_URL;
        int sent = sender.sendToUser(userId, title, body, url);
        return new PushTestResult(subscriptions, sent, true);
    }
}
