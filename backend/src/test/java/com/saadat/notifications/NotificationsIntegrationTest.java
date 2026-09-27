package com.saadat.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saadat.IntegrationTestBase;
import com.saadat.common.api.ApiPaths;
import com.saadat.common.domain.NotificationType;
import com.saadat.common.domain.Role;
import com.saadat.notifications.domain.Notification;
import com.saadat.notifications.repo.NotificationRepository;
import com.saadat.notifications.service.InterpreterDirectory;
import com.saadat.notifications.service.NotificationService;
import com.saadat.users.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;

class NotificationsIntegrationTest extends IntegrationTestBase {

    @Autowired
    NotificationService notificationService;

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    InterpreterDirectory interpreterDirectory;

    @Test
    void notifyPersistsLocalizedTextAndReadAllClearsUnread() throws Exception {
        User user = createUser("notif", Role.USER);
        notificationService.notify(user.getId(), NotificationType.DREAM_RECEIVED,
                Map.<String, Object>of("expectedBy", Instant.now()), "/me/dreams/abc");
        notificationService.notify(user.getId(), NotificationType.PAYMENT_SUCCESS,
                Map.<String, Object>of("credits", 3), "/me/payments");

        assertThat(notificationService.unreadCount(user.getId())).isEqualTo(2);
        List<Notification> rows = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent();
        assertThat(rows).allSatisfy(n -> {
            assertThat(n.getTitle()).isNotBlank().doesNotStartWith("notif.");
            assertThat(n.getBody()).isNotBlank().doesNotContain("{");
        });
        assertThat(rows).filteredOn(n -> n.getType() == NotificationType.PAYMENT_SUCCESS)
                .singleElement()
                .satisfies(n -> assertThat(n.getBody()).contains("3"));

        mvc.perform(get(ApiPaths.Notifications.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.items[*].type").value(hasItem("DREAM_RECEIVED")))
                .andExpect(jsonPath("$.items[*].link").value(hasItem("/me/payments")))
                .andExpect(jsonPath("$.items[*].title").value(not(hasItem(startsWith("notif.")))));

        mvc.perform(post(ApiPaths.Notifications.READ_ALL).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(notificationService.unreadCount(user.getId())).isZero();
        mvc.perform(get(ApiPaths.Notifications.ROOT).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].readAt").isNotEmpty())
                .andExpect(jsonPath("$.items[1].readAt").isNotEmpty());
    }

    @Test
    void markSingleReadOnlyAffectsOwnNotification() throws Exception {
        User owner = createUser("owner", Role.USER);
        User other = createUser("other", Role.USER);
        notificationService.notify(owner.getId(), NotificationType.INTERPRETATION_READY, Map.of(), "/me/dreams/x");
        Notification n = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(owner.getId(), Pageable.unpaged()).getContent().get(0);

        // another user cannot mark it (still 204, no effect)
        mvc.perform(post(ApiPaths.Notifications.READ, n.getId()).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNoContent());
        assertThat(notificationService.unreadCount(owner.getId())).isEqualTo(1);

        mvc.perform(post(ApiPaths.Notifications.READ, n.getId()).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isNoContent());
        assertThat(notificationService.unreadCount(owner.getId())).isZero();
    }

    @Test
    void notificationsRequireAuthentication() throws Exception {
        mvc.perform(get(ApiPaths.Notifications.ROOT)).andExpect(status().isUnauthorized());
    }

    @Test
    void interpreterDirectoryListsInterpreters() {
        User interpreter = createUser("interp", Role.INTERPRETER);
        User user = createUser("plain", Role.USER);
        assertThat(interpreterDirectory.interpreterUserIds())
                .contains(interpreter.getId())
                .doesNotContain(user.getId());

        notificationService.notifyInterpreters(NotificationType.DREAM_SUBMITTED,
                Map.<String, Object>of("userName", "Sara"), "/admin/dreams/1");
        List<Notification> rows = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(interpreter.getId(), Pageable.unpaged()).getContent();
        assertThat(rows).singleElement().satisfies(n -> assertThat(n.getBody()).contains("Sara"));
    }
}
