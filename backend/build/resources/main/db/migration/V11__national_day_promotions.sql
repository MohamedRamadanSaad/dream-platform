-- Replace the seeded promotion with two country-scoped national-occasion offers:
--   Saudi National Day (23 Sep) → SA, Egypt's 6 October Victory Day → EG. Dates are edited from the dashboard.
UPDATE promotions
   SET name = 'عرض اليوم الوطني السعودي', scope = 'COUNTRY', scope_id = 'SA',
       starts_at = date_trunc('day', now()) - interval '1 day', ends_at = date_trunc('day', now()) + interval '6 days'
 WHERE id = '44444444-4444-4444-8444-000000000001';

INSERT INTO promotions (id, name, package_ids, type, value, starts_at, ends_at, max_uses, used_count, scope, scope_id, active) VALUES
    ('44444444-4444-4444-8444-000000000002', 'عرض نصر أكتوبر',
     ARRAY['22222222-2222-4222-8222-000000000002']::uuid[],
     'PERCENT', 15, make_timestamptz(extract(year from now())::int, 10, 1, 0, 0, 0, 'Africa/Cairo'),
     make_timestamptz(extract(year from now())::int, 10, 8, 0, 0, 0, 'Africa/Cairo'), 200, 0, 'COUNTRY', 'EG', true)
ON CONFLICT (id) DO NOTHING;
