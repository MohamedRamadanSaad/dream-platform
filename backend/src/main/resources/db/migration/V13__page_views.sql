-- V13: page-view tracking (POST /public/track). One row per SPA route change; "visitors" = distinct session_id.
-- Paths under /admin and bots are never stored. country_code is server-detected (CountryResolver), never sent
-- by the client; referrer_host keeps the host only.
CREATE TABLE page_views (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    path           varchar(255) NOT NULL,
    session_id     varchar(100) NOT NULL,
    visitor_id     varchar(100),
    user_id        uuid         REFERENCES users (id) ON DELETE SET NULL,
    country_code   char(2),
    device         varchar(16)  NOT NULL CHECK (device IN ('MOBILE', 'TABLET', 'DESKTOP')),
    referrer_host  varchar(255),
    created_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_page_views_created ON page_views (created_at);
CREATE INDEX ix_page_views_session ON page_views (session_id);
CREATE INDEX ix_page_views_path ON page_views (path);
