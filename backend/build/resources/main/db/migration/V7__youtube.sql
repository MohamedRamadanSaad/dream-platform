-- V7: YouTube feed cache + per-user "seen" marker

CREATE TABLE youtube_videos (
    id             varchar(32)  PRIMARY KEY, -- YouTube video id
    title          varchar(500) NOT NULL,
    published_at   timestamptz  NOT NULL,
    thumbnail_url  varchar(500),
    url            varchar(500) NOT NULL,
    fetched_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_youtube_videos_published ON youtube_videos (published_at DESC);

CREATE TABLE youtube_seen (
    user_id  uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    seen_at  timestamptz NOT NULL
);
