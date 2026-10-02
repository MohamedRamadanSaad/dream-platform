package com.saadat.tracking.service;

import com.saadat.common.domain.Device;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Small built-in User-Agent heuristics (no parsing library): bot detection and device class for page-view tracking,
 * plus the browser and system names shown in the devices list and the new sign-in e-mail
 * (docs/SESSIONS_PROFILE_CONTRACT.md §2). Unknown or missing values give {@link #OTHER} / {@link Device#DESKTOP}.
 */
public final class UserAgents {

    /** Browser or system that is not recognised (also for a missing header). */
    public static final String OTHER = "Other";

    // browsers (contract values)
    public static final String CHROME = "Chrome";
    public static final String SAFARI = "Safari";
    public static final String EDGE = "Edge";
    public static final String FIREFOX = "Firefox";
    public static final String SAMSUNG_INTERNET = "Samsung Internet";
    public static final String OPERA = "Opera";

    // systems (contract values)
    public static final String WINDOWS = "Windows";
    public static final String MACOS = "macOS";
    public static final String IOS = "iOS";
    public static final String IPADOS = "iPadOS";
    public static final String ANDROID_OS = "Android";
    public static final String LINUX = "Linux";

    /** Crawlers, link previews and monitors (contract: bot|crawler|spider|preview, case-insensitive). */
    private static final Pattern BOT = Pattern.compile("bot|crawler|spider|preview", Pattern.CASE_INSENSITIVE);

    private static final List<String> TABLET_MARKERS = List.of("ipad", "tablet", "kindle", "silk/", "playbook");
    private static final List<String> MOBILE_MARKERS =
            List.of("mobi", "iphone", "ipod", "android", "windows phone", "blackberry", "opera mini");
    private static final String ANDROID = "android";
    private static final String MOBILE = "mobile";

    // browser markers, checked in this order: the Chromium-based browsers also write "Chrome/" and "Safari/",
    // and Chrome writes "Safari/" too
    private static final List<String> SAMSUNG_MARKERS = List.of("samsungbrowser/");
    private static final List<String> EDGE_MARKERS = List.of("edg/", "edge/", "edga/", "edgios/");
    private static final List<String> OPERA_MARKERS = List.of("opr/", "opera", "opios/");
    private static final List<String> FIREFOX_MARKERS = List.of("firefox/", "fxios/");
    private static final List<String> CHROME_MARKERS = List.of("chrome/", "crios/", "chromium/");
    private static final List<String> SAFARI_MARKERS = List.of("safari/");

    // system markers, checked in this order: iOS writes "like Mac OS X", Android writes "Linux"
    private static final List<String> WINDOWS_MARKERS = List.of("windows");
    private static final List<String> IPADOS_MARKERS = List.of("ipad");
    private static final List<String> IOS_MARKERS = List.of("iphone", "ipod");
    private static final List<String> MACOS_MARKERS = List.of("mac os x", "macintosh");
    private static final List<String> LINUX_MARKERS = List.of("linux");

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

    /** "Chrome", "Safari", "Edge", "Firefox", "Samsung Internet", "Opera" or "Other". */
    public static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return OTHER;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (containsAny(ua, SAMSUNG_MARKERS)) {
            return SAMSUNG_INTERNET;
        }
        if (containsAny(ua, EDGE_MARKERS)) {
            return EDGE;
        }
        if (containsAny(ua, OPERA_MARKERS)) {
            return OPERA;
        }
        if (containsAny(ua, FIREFOX_MARKERS)) {
            return FIREFOX;
        }
        if (containsAny(ua, CHROME_MARKERS)) {
            return CHROME;
        }
        // old Android browsers also write "Safari/"
        if (containsAny(ua, SAFARI_MARKERS) && !ua.contains(ANDROID)) {
            return SAFARI;
        }
        return OTHER;
    }

    /** "Windows", "macOS", "iOS", "iPadOS", "Android", "Linux" or "Other". */
    public static String os(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return OTHER;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (containsAny(ua, WINDOWS_MARKERS)) {
            return WINDOWS;
        }
        if (containsAny(ua, IPADOS_MARKERS)) {
            return IPADOS;
        }
        if (containsAny(ua, IOS_MARKERS)) {
            return IOS;
        }
        if (ua.contains(ANDROID)) {
            return ANDROID_OS;
        }
        if (containsAny(ua, MACOS_MARKERS)) {
            return MACOS;
        }
        if (containsAny(ua, LINUX_MARKERS)) {
            return LINUX;
        }
        return OTHER;
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
