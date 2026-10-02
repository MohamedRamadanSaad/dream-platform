package com.saadat.common.api;

/**
 * Every HTTP route of the API. The application runs under the servlet context path {@code /api}
 * ({@code server.servlet.context-path}), so these constants do NOT include the {@code /api} prefix.
 *
 * <p>Constants are absolute (within the context path). Use them directly on method mappings, e.g.
 * {@code @GetMapping(ApiPaths.Dreams.BY_ID)}, without a class-level {@code @RequestMapping} prefix.
 * {@code ALL} constants are Ant patterns used by the security configuration only.
 *
 * <p>Source of truth: {@code frontend/src/api/endpoints.ts} + docs/BACKEND_SPEC.md.
 */
public final class ApiPaths {

    private ApiPaths() {
    }

    public static final class Auth {
        public static final String ROOT = "/auth";
        public static final String ALL = ROOT + "/**";
        public static final String GOOGLE = ROOT + "/google";
        public static final String MAGIC_REQUEST = ROOT + "/magic/request";
        public static final String MAGIC_VERIFY = ROOT + "/magic/verify";
        public static final String REFRESH = ROOT + "/refresh";
        public static final String LOGOUT = ROOT + "/logout";
        /** Requires an access token (the only authenticated route under /auth). */
        public static final String ONBOARDING = ROOT + "/onboarding";

        private Auth() {
        }
    }

    public static final class Public {
        public static final String ROOT = "/public";
        public static final String ALL = ROOT + "/**";
        public static final String CATALOG = ROOT + "/catalog";
        public static final String WAIT_TIME = ROOT + "/wait-time";
        public static final String TESTIMONIALS = ROOT + "/testimonials";
        public static final String STATS = ROOT + "/stats";
        public static final String PUSH_KEY = ROOT + "/push-key";
        /** POST page-view tracking (token optional). */
        public static final String TRACK = ROOT + "/track";

        private Public() {
        }
    }

    public static final class Me {
        public static final String ROOT = "/me";
        public static final String DASHBOARD = ROOT + "/dashboard";
        public static final String PREFERENCES = ROOT + "/preferences";
        public static final String CREDITS = ROOT + "/credits";
        public static final String ORDERS = ROOT + "/orders";
        public static final String ORDER = ORDERS + "/{id}";
        /** PDF of all the caller's non-draft dreams with their interpretations. */
        public static final String DREAMS_PDF = ROOT + "/dreams/pdf";

        private Me() {
        }
    }

    public static final class Dreams {
        public static final String ROOT = "/dreams";
        public static final String BY_ID = ROOT + "/{id}";
        public static final String SUBMIT = ROOT + "/submit";
        public static final String MESSAGES = BY_ID + "/messages";
        public static final String TESTIMONIAL = BY_ID + "/testimonial";
        /** PDF of one of the caller's own non-draft dreams. */
        public static final String PDF = BY_ID + "/pdf";

        private Dreams() {
        }
    }

    public static final class Checkout {
        public static final String ROOT = "/checkout";

        private Checkout() {
        }
    }

    public static final class Notifications {
        public static final String ROOT = "/notifications";
        public static final String READ_ALL = ROOT + "/read-all";
        public static final String READ = ROOT + "/{id}/read";

        private Notifications() {
        }
    }

    public static final class Push {
        public static final String ROOT = "/push";
        /** POST (subscribe, body PushSubscriptionRequest) and DELETE (?endpoint=...). */
        public static final String SUBSCRIPTIONS = ROOT + "/subscriptions";

        private Push() {
        }
    }

    public static final class Youtube {
        public static final String ROOT = "/youtube";
        public static final String UNSEEN = ROOT + "/unseen";
        public static final String SEEN = ROOT + "/seen";

        private Youtube() {
        }
    }

    public static final class Admin {
        public static final String ROOT = "/admin";
        public static final String ALL = ROOT + "/**";

        public static final String ANALYTICS_SUMMARY = ROOT + "/analytics/summary";
        public static final String ANALYTICS_COUNTRIES = ROOT + "/analytics/countries";
        public static final String ANALYTICS_USERS = ROOT + "/analytics/users";
        public static final String ANALYTICS_TRAFFIC = ROOT + "/analytics/traffic";
        public static final String ANALYTICS_INSIGHTS = ROOT + "/analytics/insights";

        public static final String DREAMS = ROOT + "/dreams";
        /** Excel (.xlsx) export of the non-draft dreams matching the filters. */
        public static final String DREAMS_EXPORT = DREAMS + "/export";
        public static final String DREAM = DREAMS + "/{id}";
        public static final String DREAM_MESSAGES = DREAM + "/messages";
        public static final String DREAM_INTERPRETATION = DREAM + "/interpretation";
        public static final String DREAM_CANCEL = DREAM + "/cancel";
        public static final String DREAM_PDF = DREAM + "/pdf";

        public static final String WAIT_TIME = ROOT + "/wait-time";
        public static final String SETTINGS = ROOT + "/settings";

        public static final String PACKAGES = ROOT + "/packages";
        public static final String PACKAGE = PACKAGES + "/{id}";

        public static final String COUNTRIES = ROOT + "/countries";
        public static final String COUNTRY_GROUPS = ROOT + "/country-groups";
        public static final String COUNTRY_GROUP = COUNTRY_GROUPS + "/{id}";

        public static final String PRICE_RULES = ROOT + "/price-rules";
        public static final String PRICE_RULE = PRICE_RULES + "/{id}";

        public static final String PROMOTIONS = ROOT + "/promotions";
        public static final String PROMOTION = PROMOTIONS + "/{id}";

        public static final String COUPONS = ROOT + "/coupons";
        public static final String COUPON = COUPONS + "/{id}";

        public static final String USERS = ROOT + "/users";
        public static final String USER = USERS + "/{id}";
        public static final String USER_NOTES = USER + "/notes";
        public static final String USER_CREDITS = USER + "/credits";
        public static final String USER_PDF = USER + "/pdf";

        public static final String TESTIMONIALS = ROOT + "/testimonials";
        public static final String TESTIMONIAL = TESTIMONIALS + "/{id}";

        public static final String ORDERS = ROOT + "/orders";

        public static final String YOUTUBE_REFRESH = ROOT + "/youtube/refresh";

        private Admin() {
        }
    }

    public static final class Webhooks {
        public static final String ROOT = "/webhooks";
        public static final String ALL = ROOT + "/**";
        public static final String PAYMOB = ROOT + "/paymob";
        public static final String MOR = ROOT + "/mor";
        /** Only functional when app.payments.mock=true (the controller must enforce it). */
        public static final String MOCK = ROOT + "/mock/{orderId}";

        private Webhooks() {
        }
    }

    /** Infrastructure routes that are open without authentication. */
    public static final class Infra {
        public static final String ERROR = "/error";
        public static final String HEALTH = "/actuator/health";
        public static final String HEALTH_ALL = "/actuator/health/**";
        public static final String INFO = "/actuator/info";
        public static final String OPENAPI = "/v3/api-docs/**";
        public static final String SWAGGER_UI = "/swagger-ui/**";
        public static final String SWAGGER_UI_HTML = "/swagger-ui.html";

        private Infra() {
        }
    }
}
