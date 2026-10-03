-- V31: soft delete everywhere. Nothing is removed from the database any more: every "delete" of these rows sets
-- deleted = true (Hibernate @SoftDelete on the entities turns em.remove, derived deleteBy... and JPQL "delete from"
-- into "update ... set deleted = true" and hides deleted rows from every entity load and JPQL query).
-- Native SQL is NOT filtered by Hibernate: every native query that reads one of these tables must add "not x.deleted".

ALTER TABLE country_groups     ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE price_rules        ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE promotions         ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE coupons            ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE packages           ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE dreams             ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE passkeys           ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE passkey_challenges ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE push_subscriptions ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE auth_identities    ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE magic_links        ADD COLUMN deleted boolean NOT NULL DEFAULT false;
ALTER TABLE refresh_tokens     ADD COLUMN deleted boolean NOT NULL DEFAULT false;

-- Natural keys are unique among live rows only, so a deleted row never blocks creating the same thing again
-- (same coupon code, same scope + package price, same Google account after account deletion, same browser push
-- endpoint after unsubscribe, same passkey credential). Same names as the constraints they replace.

ALTER TABLE coupons DROP CONSTRAINT ux_coupons_code;
CREATE UNIQUE INDEX ux_coupons_code ON coupons (code) WHERE NOT deleted;

ALTER TABLE price_rules DROP CONSTRAINT ux_price_rules_scope_package;
CREATE UNIQUE INDEX ux_price_rules_scope_package ON price_rules (scope, scope_id, package_id) NULLS NOT DISTINCT
    WHERE NOT deleted;

ALTER TABLE auth_identities DROP CONSTRAINT ux_auth_identities_provider_subject;
CREATE UNIQUE INDEX ux_auth_identities_provider_subject ON auth_identities (provider, provider_subject)
    WHERE NOT deleted;

ALTER TABLE push_subscriptions DROP CONSTRAINT ux_push_subscriptions_endpoint;
CREATE UNIQUE INDEX ux_push_subscriptions_endpoint ON push_subscriptions (endpoint) WHERE NOT deleted;

ALTER TABLE passkeys DROP CONSTRAINT ux_passkeys_credential_id;
CREATE UNIQUE INDEX ux_passkeys_credential_id ON passkeys (credential_id) WHERE NOT deleted;

-- magic_links.token_hash and refresh_tokens.token_hash stay globally unique: they are random hashes, a new row
-- never reuses an old value.
