package com.saadat.config.props;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Infrastructure configuration (secrets, URLs, integration switches) bound from {@code app.*}.
 * Business settings the interpreter can change live in {@code app_settings} (see SettingsService).
 * Every value is fed from an environment variable — see backend/.env.example.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Public URL of the SPA, e.g. https://saadatu-aldarein.com (no trailing slash). */
    @NotBlank
    private String frontendUrl;

    /** Public URL of this API including the /api context path, e.g. https://saadatu-aldarein.com/api. */
    @NotBlank
    private String apiUrl;

    /** Google OAuth client id (audience of Google ID tokens). Empty disables Google login. */
    private String googleClientId = "";

    @Valid
    private Auth auth = new Auth();

    @Valid
    private Payments payments = new Payments();

    @Valid
    private Push push = new Push();

    @Valid
    private Mail mail = new Mail();

    @Valid
    private Youtube youtube = new Youtube();

    @Valid
    private Cors cors = new Cors();

    @Valid
    private RateLimit rateLimit = new RateLimit();

    @Valid
    private Async async = new Async();

    @Valid
    private Passkeys passkeys = new Passkeys();

    @Getter
    @Setter
    public static class Auth {
        /** Enables mock logins (idToken "mock", X-Country header). MUST be false outside local/test. */
        private boolean allowMock = false;

        /** E-mail of the user that idToken "mock" logs in as (only when allowMock=true). */
        private String mockEmail = "";

        /**
         * Bootstrap list (env INTERPRETER_EMAILS, comma-separated) merged with setting interpreter.emails so the
         * first interpreter can log in before any setting has been edited from the dashboard.
         */
        private String bootstrapInterpreterEmails = "";

        /** HS256 secret for access tokens; at least 32 characters. */
        @NotBlank
        @Size(min = 32)
        private String jwtSecret;

        /** JWT "iss" claim. */
        @NotBlank
        private String jwtIssuer = "saadat-api";

        /** Name of the refresh-token cookie. */
        @NotBlank
        private String refreshCookieName = "rt";

        /** Path of the refresh-token cookie (context path included). */
        @NotBlank
        private String refreshCookiePath = "/api/auth";

        /** Secure flag of the refresh-token cookie (false only in local). */
        private boolean refreshCookieSecure = true;

        /** SameSite attribute of the refresh-token cookie. */
        @NotBlank
        private String refreshCookieSameSite = "Lax";
    }

    @Getter
    @Setter
    public static class Payments {
        /** Use the MockPaymentProvider and expose POST /webhooks/mock/{orderId}. Never in prod. */
        private boolean mock = false;

        @Valid
        private Kashier kashier = new Kashier();

        @Valid
        private Mor mor = new Mor();
    }

    /** Kashier (Egypt). Keys and MID from the Kashier dashboard → Integration; test and live keys differ. */
    @Getter
    @Setter
    public static class Kashier {
        /** Merchant id, e.g. MID-12345-678 (shown under the account name in the dashboard). */
        private String merchantId = "";
        /** Payment API Key: signs nothing we send, verifies the webhook signature (x-kashier-signature). */
        private String apiKey = "";
        /** Secret Key: Authorization header of the payment-session API call. */
        private String secretKey = "";
        /** "test" or "live" — must match the keys. */
        private String mode = "test";
        /** Optional override of the API host; empty → https://test-api.kashier.io or https://api.kashier.io. */
        private String baseUrl = "";

        public boolean isConfigured() {
            return notBlank(merchantId) && notBlank(apiKey) && notBlank(secretKey);
        }

        public boolean isLive() {
            return "live".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        public String resolvedBaseUrl() {
            if (notBlank(baseUrl)) {
                return baseUrl.trim().replaceAll("/+$", "");
            }
            return isLive() ? "https://api.kashier.io" : "https://test-api.kashier.io";
        }
    }

    @Getter
    @Setter
    public static class Mor {
        private String apiKey = "";
        private String storeId = "";
        private String webhookSecret = "";
        private String baseUrl = "";

        public boolean isConfigured() {
            return notBlank(apiKey) && notBlank(webhookSecret) && notBlank(baseUrl);
        }
    }

    @Getter
    @Setter
    public static class Push {
        /** VAPID public key (base64url). Empty disables web push. */
        private String publicKey = "";
        /** VAPID private key (base64url). */
        private String privateKey = "";
        /** VAPID subject, e.g. mailto:support@example.com. */
        private String subject = "";

        public boolean isConfigured() {
            return notBlank(publicKey) && notBlank(privateKey) && notBlank(subject);
        }
    }

    @Getter
    @Setter
    public static class Mail {
        /** Sender address (From). */
        private String from = "";
        /** Sender display name. */
        private String fromName = "";
        /**
         * Shared secret of the support mailbox webhook (POST /webhooks/mail/inbound, env MAIL_WEBHOOK_SECRET),
         * sent by the mail host as {@code Authorization: Bearer <secret>}. Empty disables the endpoint (503).
         */
        private String webhookSecret = "";
    }

    @Getter
    @Setter
    public static class Youtube {
        /** Feed URL template; {channelId} is replaced with setting brand.youtube_channel_id. */
        @NotBlank
        private String feedUrl = "https://www.youtube.com/feeds/videos.xml?channel_id={channelId}";
        /** HTTP timeout for the feed fetch, in seconds. */
        @Min(1)
        private int timeoutSeconds = 10;
        /**
         * While setting brand.youtube_channel_id is empty, resolve it from the channel page in setting
         * brand.youtube_url (YoutubeChannelIdResolver). Off in tests (no network).
         */
        private boolean resolveChannelId = true;
        /** The channel page is fetched only over https from this domain or one of its subdomains. */
        @NotBlank
        private String channelPageDomain = "youtube.com";
    }

    @Getter
    @Setter
    public static class Cors {
        /** Extra allowed origins (frontendUrl is always allowed). */
        private List<String> allowedOrigins = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        /** Requests per minute per IP on /auth/**. */
        @Min(1)
        private int authPerMinute = 10;
        /** Requests per minute per user on POST /dreams. */
        @Min(1)
        private int dreamsPerMinute = 30;
        /** Requests per minute per IP on /checkout. */
        @Min(1)
        private int checkoutPerMinute = 5;
        /** Requests per minute per IP on POST /public/track. */
        @Min(1)
        private int trackPerMinute = 60;
        /** Safety valve: bucket map is cleared when it grows beyond this many keys. */
        @Min(100)
        private int maxKeys = 100_000;
    }

    @Getter
    @Setter
    public static class Async {
        @Min(1)
        private int corePoolSize = 4;
        @Min(1)
        private int maxPoolSize = 16;
        @Min(0)
        private int queueCapacity = 500;
    }

    /** Passkeys (WebAuthn) relying party; both default to {@link #frontendUrl}. */
    @Getter
    @Setter
    public static class Passkeys {
        /** Relying-party ID (env PASSKEY_RP_ID); blank = the host of frontend-url, e.g. saadatu-aldarein.com. */
        private String rpId = "";
        /** Allowed origins (env PASSKEY_ORIGINS, comma-separated); empty = the origin of frontend-url. */
        private List<String> origins = new ArrayList<>();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
