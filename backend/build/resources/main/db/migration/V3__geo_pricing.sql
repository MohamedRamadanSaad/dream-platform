-- V3: geography, packages and pricing
-- Fixed seed ids (referenced by tests / docs):
--   group  g1 الخليج        11111111-1111-4111-8111-000000000001
--   group  g2 المغرب العربي 11111111-1111-4111-8111-000000000002
--   package p1 (1 credit)   22222222-2222-4222-8222-000000000001
--   package p2 (2 credits)  22222222-2222-4222-8222-000000000002
--   package p3 (3 credits)  22222222-2222-4222-8222-000000000003
--   price rules r1..r12     33333333-3333-4333-8333-0000000000NN
--   promotion pr1           44444444-4444-4444-8444-000000000001
--   coupon c1 BARAKA10      55555555-5555-4555-8555-000000000001

CREATE TABLE country_groups (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(100) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE countries (
    code              char(2)      PRIMARY KEY,
    name_ar           varchar(100) NOT NULL,
    name_en           varchar(100) NOT NULL,
    continent         char(2)      NOT NULL CHECK (continent IN ('AF', 'AS', 'EU', 'NA', 'SA', 'OC')),
    default_currency  char(3)      NOT NULL CHECK (default_currency IN ('EGP', 'SAR', 'USD')),
    group_id          uuid         REFERENCES country_groups (id) ON DELETE SET NULL
);
CREATE INDEX ix_countries_group ON countries (group_id);

CREATE TABLE packages (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name_ar         varchar(200) NOT NULL,
    name_en         varchar(200) NOT NULL,
    description_ar  text         NOT NULL DEFAULT '',
    description_en  text         NOT NULL DEFAULT '',
    credits         integer      NOT NULL CHECK (credits > 0),
    badge           varchar(64),
    sort_order      integer      NOT NULL DEFAULT 0,
    active          boolean      NOT NULL DEFAULT true,
    validity_months integer      CHECK (validity_months IS NULL OR validity_months > 0),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE price_rules (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    scope       varchar(16)   NOT NULL CHECK (scope IN ('GLOBAL', 'CONTINENT', 'GROUP', 'COUNTRY')),
    scope_id    varchar(64),
    package_id  uuid          NOT NULL REFERENCES packages (id) ON DELETE CASCADE,
    price       numeric(12,2) NOT NULL CHECK (price >= 0),
    currency    char(3)       NOT NULL CHECK (currency IN ('EGP', 'SAR', 'USD')),
    CONSTRAINT ck_price_rules_scope_id CHECK ((scope = 'GLOBAL') = (scope_id IS NULL)),
    CONSTRAINT ux_price_rules_scope_package UNIQUE NULLS NOT DISTINCT (scope, scope_id, package_id)
);
CREATE INDEX ix_price_rules_package ON price_rules (package_id);

CREATE TABLE promotions (
    id          uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    name        varchar(200)  NOT NULL,
    package_ids uuid[]        NOT NULL DEFAULT '{}',
    type        varchar(16)   NOT NULL CHECK (type IN ('PERCENT', 'FIXED', 'BONUS')),
    value       numeric(12,2) NOT NULL CHECK (value >= 0),
    starts_at   timestamptz   NOT NULL,
    ends_at     timestamptz   NOT NULL,
    max_uses    integer       CHECK (max_uses IS NULL OR max_uses >= 0),
    used_count  integer       NOT NULL DEFAULT 0,
    scope       varchar(16)   NOT NULL DEFAULT 'GLOBAL' CHECK (scope IN ('GLOBAL', 'CONTINENT', 'GROUP', 'COUNTRY')),
    scope_id    varchar(64),
    active      boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_promotions_window CHECK (ends_at > starts_at)
);
CREATE INDEX ix_promotions_active_window ON promotions (active, starts_at, ends_at);

CREATE TABLE coupons (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    code            varchar(64)   NOT NULL,
    type            varchar(16)   NOT NULL CHECK (type IN ('PERCENT', 'FIXED')),
    value           numeric(12,2) NOT NULL CHECK (value >= 0),
    max_uses        integer       CHECK (max_uses IS NULL OR max_uses >= 0),
    per_user_limit  integer       NOT NULL DEFAULT 1 CHECK (per_user_limit >= 1),
    used_count      integer       NOT NULL DEFAULT 0,
    expires_at      timestamptz,
    active          boolean       NOT NULL DEFAULT true,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ux_coupons_code UNIQUE (code),
    CONSTRAINT ck_coupons_code_upper CHECK (code = upper(code))
);

-- order_id FK is added in V4 (orders table does not exist yet)
CREATE TABLE coupon_redemptions (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    coupon_id   uuid        NOT NULL REFERENCES coupons (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    order_id    uuid        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_coupon_redemptions UNIQUE (coupon_id, user_id, order_id)
);
CREATE INDEX ix_coupon_redemptions_coupon_user ON coupon_redemptions (coupon_id, user_id);

-- ---------------------------------------------------------------- seeds

INSERT INTO country_groups (id, name) VALUES
    ('11111111-1111-4111-8111-000000000001', 'الخليج'),
    ('11111111-1111-4111-8111-000000000002', 'المغرب العربي');

-- Default currency: EG → EGP, GCC → SAR, everything else → USD.
INSERT INTO countries (code, name_ar, name_en, continent, default_currency, group_id) VALUES
    -- Arab world
    ('EG', 'مصر', 'Egypt', 'AF', 'EGP', NULL),
    ('SA', 'السعودية', 'Saudi Arabia', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('AE', 'الإمارات', 'UAE', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('KW', 'الكويت', 'Kuwait', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('QA', 'قطر', 'Qatar', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('BH', 'البحرين', 'Bahrain', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('OM', 'عُمان', 'Oman', 'AS', 'SAR', '11111111-1111-4111-8111-000000000001'),
    ('YE', 'اليمن', 'Yemen', 'AS', 'USD', NULL),
    ('IQ', 'العراق', 'Iraq', 'AS', 'USD', NULL),
    ('SY', 'سوريا', 'Syria', 'AS', 'USD', NULL),
    ('JO', 'الأردن', 'Jordan', 'AS', 'USD', NULL),
    ('LB', 'لبنان', 'Lebanon', 'AS', 'USD', NULL),
    ('PS', 'فلسطين', 'Palestine', 'AS', 'USD', NULL),
    ('MA', 'المغرب', 'Morocco', 'AF', 'USD', '11111111-1111-4111-8111-000000000002'),
    ('DZ', 'الجزائر', 'Algeria', 'AF', 'USD', '11111111-1111-4111-8111-000000000002'),
    ('TN', 'تونس', 'Tunisia', 'AF', 'USD', '11111111-1111-4111-8111-000000000002'),
    ('LY', 'ليبيا', 'Libya', 'AF', 'USD', '11111111-1111-4111-8111-000000000002'),
    ('MR', 'موريتانيا', 'Mauritania', 'AF', 'USD', '11111111-1111-4111-8111-000000000002'),
    ('SD', 'السودان', 'Sudan', 'AF', 'USD', NULL),
    ('SO', 'الصومال', 'Somalia', 'AF', 'USD', NULL),
    ('DJ', 'جيبوتي', 'Djibouti', 'AF', 'USD', NULL),
    ('KM', 'جزر القمر', 'Comoros', 'AF', 'USD', NULL),
    -- Europe
    ('DE', 'ألمانيا', 'Germany', 'EU', 'USD', NULL),
    ('FR', 'فرنسا', 'France', 'EU', 'USD', NULL),
    ('GB', 'بريطانيا', 'United Kingdom', 'EU', 'USD', NULL),
    ('IT', 'إيطاليا', 'Italy', 'EU', 'USD', NULL),
    ('ES', 'إسبانيا', 'Spain', 'EU', 'USD', NULL),
    ('NL', 'هولندا', 'Netherlands', 'EU', 'USD', NULL),
    ('BE', 'بلجيكا', 'Belgium', 'EU', 'USD', NULL),
    ('AT', 'النمسا', 'Austria', 'EU', 'USD', NULL),
    ('CH', 'سويسرا', 'Switzerland', 'EU', 'USD', NULL),
    ('SE', 'السويد', 'Sweden', 'EU', 'USD', NULL),
    ('NO', 'النرويج', 'Norway', 'EU', 'USD', NULL),
    ('DK', 'الدنمارك', 'Denmark', 'EU', 'USD', NULL),
    ('FI', 'فنلندا', 'Finland', 'EU', 'USD', NULL),
    ('IE', 'أيرلندا', 'Ireland', 'EU', 'USD', NULL),
    ('PT', 'البرتغال', 'Portugal', 'EU', 'USD', NULL),
    ('PL', 'بولندا', 'Poland', 'EU', 'USD', NULL),
    ('GR', 'اليونان', 'Greece', 'EU', 'USD', NULL),
    ('CZ', 'التشيك', 'Czechia', 'EU', 'USD', NULL),
    ('RO', 'رومانيا', 'Romania', 'EU', 'USD', NULL),
    ('HU', 'المجر', 'Hungary', 'EU', 'USD', NULL),
    ('RU', 'روسيا', 'Russia', 'EU', 'USD', NULL),
    ('BA', 'البوسنة والهرسك', 'Bosnia and Herzegovina', 'EU', 'USD', NULL),
    ('AL', 'ألبانيا', 'Albania', 'EU', 'USD', NULL),
    -- North America
    ('US', 'الولايات المتحدة', 'United States', 'NA', 'USD', NULL),
    ('CA', 'كندا', 'Canada', 'NA', 'USD', NULL),
    ('MX', 'المكسيك', 'Mexico', 'NA', 'USD', NULL),
    -- South America
    ('BR', 'البرازيل', 'Brazil', 'SA', 'USD', NULL),
    ('AR', 'الأرجنتين', 'Argentina', 'SA', 'USD', NULL),
    ('CL', 'تشيلي', 'Chile', 'SA', 'USD', NULL),
    ('CO', 'كولومبيا', 'Colombia', 'SA', 'USD', NULL),
    ('VE', 'فنزويلا', 'Venezuela', 'SA', 'USD', NULL),
    -- Oceania
    ('AU', 'أستراليا', 'Australia', 'OC', 'USD', NULL),
    ('NZ', 'نيوزيلندا', 'New Zealand', 'OC', 'USD', NULL),
    -- Asia (non-Arab)
    ('TR', 'تركيا', 'Türkiye', 'AS', 'USD', NULL),
    ('IR', 'إيران', 'Iran', 'AS', 'USD', NULL),
    ('PK', 'باكستان', 'Pakistan', 'AS', 'USD', NULL),
    ('IN', 'الهند', 'India', 'AS', 'USD', NULL),
    ('ID', 'إندونيسيا', 'Indonesia', 'AS', 'USD', NULL),
    ('MY', 'ماليزيا', 'Malaysia', 'AS', 'USD', NULL),
    ('BD', 'بنغلاديش', 'Bangladesh', 'AS', 'USD', NULL),
    ('SG', 'سنغافورة', 'Singapore', 'AS', 'USD', NULL),
    ('CN', 'الصين', 'China', 'AS', 'USD', NULL),
    ('JP', 'اليابان', 'Japan', 'AS', 'USD', NULL),
    ('KR', 'كوريا الجنوبية', 'South Korea', 'AS', 'USD', NULL),
    ('AF', 'أفغانستان', 'Afghanistan', 'AS', 'USD', NULL),
    ('AZ', 'أذربيجان', 'Azerbaijan', 'AS', 'USD', NULL),
    ('KZ', 'كازاخستان', 'Kazakhstan', 'AS', 'USD', NULL),
    ('UZ', 'أوزبكستان', 'Uzbekistan', 'AS', 'USD', NULL),
    -- Africa (non-Arab)
    ('NG', 'نيجيريا', 'Nigeria', 'AF', 'USD', NULL),
    ('SN', 'السنغال', 'Senegal', 'AF', 'USD', NULL),
    ('ZA', 'جنوب أفريقيا', 'South Africa', 'AF', 'USD', NULL),
    ('KE', 'كينيا', 'Kenya', 'AF', 'USD', NULL),
    ('ET', 'إثيوبيا', 'Ethiopia', 'AF', 'USD', NULL),
    ('ML', 'مالي', 'Mali', 'AF', 'USD', NULL),
    ('NE', 'النيجر', 'Niger', 'AF', 'USD', NULL),
    ('TD', 'تشاد', 'Chad', 'AF', 'USD', NULL),
    ('GH', 'غانا', 'Ghana', 'AF', 'USD', NULL),
    ('ER', 'إريتريا', 'Eritrea', 'AF', 'USD', NULL);

INSERT INTO packages (id, name_ar, name_en, description_ar, description_en, credits, badge, sort_order, active, validity_months) VALUES
    ('22222222-2222-4222-8222-000000000001', 'تفسير رؤيا واحدة', 'One dream',
     'تفسير دقيق ومفصل لرؤيا واحدة، يقدم لك الوضوح والسكينة.',
     'A precise, detailed interpretation of one dream.',
     1, NULL, 1, true, 12),
    ('22222222-2222-4222-8222-000000000002', 'تفسير رؤيتين', 'Two dreams',
     'باقة مثالية لتفسير رؤيتين متصلتين أو منفصلتين، مع تحليل أعمق للرسائل.',
     'Ideal for two related or separate dreams, with deeper analysis.',
     2, 'الأكثر طلباً', 2, true, 12),
    ('22222222-2222-4222-8222-000000000003', 'تفسير ثلاث رؤى', 'Three dreams',
     'باقة شاملة لتفسير ثلاث رؤى، تمنحك رؤية متكاملة لرسائل رؤاك.',
     'A complete package for three dreams.',
     3, 'أوفر', 3, true, 12);

INSERT INTO price_rules (id, scope, scope_id, package_id, price, currency) VALUES
    -- global fallback in USD
    ('33333333-3333-4333-8333-000000000001', 'GLOBAL', NULL, '22222222-2222-4222-8222-000000000001', 15, 'USD'),
    ('33333333-3333-4333-8333-000000000002', 'GLOBAL', NULL, '22222222-2222-4222-8222-000000000002', 27, 'USD'),
    ('33333333-3333-4333-8333-000000000003', 'GLOBAL', NULL, '22222222-2222-4222-8222-000000000003', 39, 'USD'),
    -- Egypt
    ('33333333-3333-4333-8333-000000000004', 'COUNTRY', 'EG', '22222222-2222-4222-8222-000000000001', 199, 'EGP'),
    ('33333333-3333-4333-8333-000000000005', 'COUNTRY', 'EG', '22222222-2222-4222-8222-000000000002', 349, 'EGP'),
    ('33333333-3333-4333-8333-000000000006', 'COUNTRY', 'EG', '22222222-2222-4222-8222-000000000003', 499, 'EGP'),
    -- Gulf group
    ('33333333-3333-4333-8333-000000000007', 'GROUP', '11111111-1111-4111-8111-000000000001', '22222222-2222-4222-8222-000000000001', 49, 'SAR'),
    ('33333333-3333-4333-8333-000000000008', 'GROUP', '11111111-1111-4111-8111-000000000001', '22222222-2222-4222-8222-000000000002', 89, 'SAR'),
    ('33333333-3333-4333-8333-000000000009', 'GROUP', '11111111-1111-4111-8111-000000000001', '22222222-2222-4222-8222-000000000003', 129, 'SAR'),
    -- Europe continent
    ('33333333-3333-4333-8333-000000000010', 'CONTINENT', 'EU', '22222222-2222-4222-8222-000000000001', 19, 'USD'),
    ('33333333-3333-4333-8333-000000000011', 'CONTINENT', 'EU', '22222222-2222-4222-8222-000000000002', 35, 'USD'),
    ('33333333-3333-4333-8333-000000000012', 'CONTINENT', 'EU', '22222222-2222-4222-8222-000000000003', 49, 'USD');

-- Mock promotion pr1: 20% on p2, window relative to migration time (disable/edit via admin).
INSERT INTO promotions (id, name, package_ids, type, value, starts_at, ends_at, max_uses, used_count, scope, scope_id, active) VALUES
    ('44444444-4444-4444-8444-000000000001', 'عرض المولد',
     ARRAY['22222222-2222-4222-8222-000000000002']::uuid[],
     'PERCENT', 20, now() - interval '1 day', now() + interval '2 days', 100, 0, 'GLOBAL', NULL, true);

-- Mock coupon c1.
INSERT INTO coupons (id, code, type, value, max_uses, per_user_limit, used_count, expires_at, active) VALUES
    ('55555555-5555-4555-8555-000000000001', 'BARAKA10', 'PERCENT', 10, 200, 1, 0, now() + interval '30 days', true);
