package com.saadat.tracking.service;

import com.saadat.common.domain.CountrySource;
import com.saadat.common.web.CountryResolver.ResolvedCountry;
import com.saadat.config.props.AppProperties;
import com.saadat.mail.FrontendPaths;
import com.saadat.tracking.api.TrackRequest;
import com.saadat.tracking.domain.PageView;
import com.saadat.tracking.repo.PageViewRepository;
import com.saadat.users.domain.User;
import com.saadat.users.repo.UserRepository;
import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores one page view per SPA route change (contract §1). Never stored: paths under {@code /admin} and bots.
 * The country is server-detected ({@link com.saadat.common.web.CountryResolver}); when only the configured
 * default is known, the signed-in user's stored country is used, else none. The referrer is reduced to its host;
 * links from the site itself count as direct.
 */
@Service
public class TrackingService {

    static final int HOST_MAX = 255;
    private static final String WWW = "www.";

    private final PageViewRepository repository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final Set<String> ownHosts;

    public TrackingService(PageViewRepository repository, UserRepository userRepository, AppProperties properties,
                           Clock clock) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.clock = clock;
        Set<String> hosts = new HashSet<>();
        addHost(hosts, properties.getFrontendUrl());
        addHost(hosts, properties.getApiUrl());
        for (String origin : properties.getCors().getAllowedOrigins()) {
            addHost(hosts, origin);
        }
        this.ownHosts = Set.copyOf(hosts);
    }

    /**
     * @param userId the caller when a valid Bearer token was sent, else null
     * @return true when the view was stored (false for interpreter paths and bots)
     */
    @Transactional
    public boolean track(TrackRequest request, String userAgent, ResolvedCountry country, UUID userId) {
        String path = normalizePath(request.path());
        if (path.startsWith(FrontendPaths.ADMIN_ROOT) || UserAgents.isBot(userAgent)) {
            return false;
        }
        PageView view = new PageView();
        view.setPath(path);
        view.setSessionId(request.sessionId().trim());
        view.setUserId(userId);
        view.setCountryCode(countryOf(country, userId));
        view.setDevice(UserAgents.device(userAgent));
        view.setReferrerHost(referrerHost(request.referrer()));
        view.setCreatedAt(clock.instant());
        repository.save(view);
        return true;
    }

    private String countryOf(ResolvedCountry country, UUID userId) {
        if (country != null && country.source() != CountrySource.DEFAULT) {
            return country.countryCode();
        }
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getCountryCode).orElse(null);
    }

    /** Route only: no query string or fragment, no trailing slash ("/" stays "/"). */
    static String normalizePath(String raw) {
        String p = raw == null ? "" : raw.trim();
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        int fragment = p.indexOf('#');
        if (fragment >= 0) {
            p = p.substring(0, fragment);
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? FrontendPaths.HOME : p;
    }

    /** Lower-case host without "www."; null for empty/unparseable referrers and for the site's own hosts. */
    String referrerHost(String referrer) {
        String host = host(referrer);
        if (host == null || ownHosts.contains(host)) {
            return null;
        }
        return host.length() > HOST_MAX ? host.substring(0, HOST_MAX) : host;
    }

    private static void addHost(Set<String> hosts, String url) {
        String host = host(url);
        if (host != null) {
            hosts.add(host);
        }
    }

    private static String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith(WWW) ? host.substring(WWW.length()) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
