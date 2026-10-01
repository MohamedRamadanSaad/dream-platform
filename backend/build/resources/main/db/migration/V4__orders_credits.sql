-- V4: orders & credit ledger

CREATE TABLE orders (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                uuid          NOT NULL REFERENCES users (id),
    product_type           varchar(16)   NOT NULL DEFAULT 'DREAMS' CHECK (product_type IN ('DREAMS')),
    package_id             uuid          REFERENCES packages (id) ON DELETE SET NULL,
    package_name_snapshot  varchar(200)  NOT NULL,
    credits                integer       NOT NULL CHECK (credits > 0),
    amount                 numeric(12,2) NOT NULL CHECK (amount >= 0),
    currency               char(3)       NOT NULL CHECK (currency IN ('EGP', 'SAR', 'USD')),
    discount               numeric(12,2) NOT NULL DEFAULT 0,
    coupon_id              uuid          REFERENCES coupons (id) ON DELETE SET NULL,
    promotion_id           uuid          REFERENCES promotions (id) ON DELETE SET NULL,
    price_rule_scope       varchar(16)   CHECK (price_rule_scope IN ('GLOBAL', 'CONTINENT', 'GROUP', 'COUNTRY')),
    provider               varchar(16)   NOT NULL CHECK (provider IN ('PAYMOB', 'MOR', 'MOCK')),
    provider_order_id      varchar(128),
    provider_txn_id        varchar(128),
    status                 varchar(16)   NOT NULL DEFAULT 'INITIATED'
                               CHECK (status IN ('INITIATED', 'SUCCESS', 'FAILED', 'EXPIRED', 'REFUNDED', 'SUSPICIOUS')),
    failure_reason         text,
    checkout_intent        jsonb,
    country_code           char(2),
    country_source         varchar(16)   CHECK (country_source IN ('IP', 'HEADER', 'DEFAULT', 'ADMIN')),
    card_country           varchar(8),
    created_at             timestamptz   NOT NULL DEFAULT now(),
    paid_at                timestamptz,
    expires_at             timestamptz   NOT NULL,
    CONSTRAINT ux_orders_provider_txn UNIQUE (provider_txn_id)
);
CREATE INDEX ix_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX ix_orders_status_expires ON orders (status, expires_at);
CREATE INDEX ix_orders_provider_order ON orders (provider_order_id);
CREATE INDEX ix_orders_paid ON orders (paid_at) WHERE status = 'SUCCESS';

ALTER TABLE coupon_redemptions
    ADD CONSTRAINT fk_coupon_redemptions_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE;

CREATE TABLE credit_ledger (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id),
    delta       integer     NOT NULL CHECK (delta <> 0),
    reason      varchar(16) NOT NULL CHECK (reason IN ('PURCHASE', 'SUBMIT', 'REFUND', 'MANUAL', 'BONUS')),
    order_id    uuid        REFERENCES orders (id),
    dream_id    uuid,       -- FK added in V5
    created_by  uuid,
    note        text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_credit_ledger_user_created ON credit_ledger (user_id, created_at);
-- Idempotency guards: one PURCHASE credit per order.
CREATE UNIQUE INDEX ux_credit_ledger_purchase_order ON credit_ledger (order_id) WHERE reason = 'PURCHASE';
