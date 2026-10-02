package com.saadat.tracking.service;

import com.saadat.common.domain.Device;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** User-Agent heuristics for page-view tracking: bot detection and device class. */
public final class UserAgents {

    /** Crawlers, link previews and monitors (contract: bot|crawler|spider|preview, case-insensitive). */
    private static final Pattern BOT = Pattern.compile("bot|crawler|spider|preview", Pattern.CASE_INSENSITIVE);

    private static final List<String> TABLET_MARKERS = List.of("ipad", "tablet", "kindle", "silk/", "playbook");
    private static final List<String> MOBILE_MARKERS =
            List.of("mobi", "iphone", "ipod", "android", "windows phone", "blackberry", "opera mini");
    private static final String ANDROID = "android";
    private static final String MOBILE = "mobile";

    private UserAgents() {
    }

    public static boolean isBot(String userAgent) {
        return userAgent != null && BOT.matcher(userAgent).find();
    }

    /** TABLET for iPad/Android tablets, MOBILE for phones, DESKTOP otherwise (also when the header is missing). */
    public static Device device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return Device.DESKTOP;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (containsAny(ua, TABLET_MARKERS) || (ua.contains(ANDROID) && !ua.contains(MOBILE))) {
            return Device.TABLET;
        }
        if (containsAny(ua, MOBILE_MARKERS)) {
            return Device.MOBILE;
        }
        return Device.DESKTOP;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
