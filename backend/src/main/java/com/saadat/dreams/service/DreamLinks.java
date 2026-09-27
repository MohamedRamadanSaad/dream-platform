package com.saadat.dreams.service;

import com.saadat.mail.FrontendPaths;
import java.util.UUID;

/** SPA routes used as notification/e-mail links (frontend router paths). */
public final class DreamLinks {

    public static final String USER_DREAM = FrontendPaths.MY_DREAMS + "/";
    public static final String ADMIN_DREAM = FrontendPaths.ADMIN_DREAMS + "/";
    public static final String ADMIN_QUEUE = FrontendPaths.ADMIN_DREAMS;

    private DreamLinks() {
    }

    public static String user(UUID dreamId) {
        return USER_DREAM + dreamId;
    }

    public static String admin(UUID dreamId) {
        return ADMIN_DREAM + dreamId;
    }
}
