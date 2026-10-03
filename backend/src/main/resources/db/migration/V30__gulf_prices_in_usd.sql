-- V30: Gulf group pays in USD through Kashier (Kashier never takes SAR). Owner's prices, 2026-10-03:
-- one dream 7 USD, two dreams 12 USD, three dreams 23 USD. Any other SAR price rule is converted at the
-- settings rate (SAR → USD) and rounded to a whole dollar, so no SAR price stays without a payment provider.

UPDATE price_rules SET currency = 'USD', price = 7
WHERE currency = 'SAR' AND package_id = '22222222-2222-4222-8222-000000000001';
UPDATE price_rules SET currency = 'USD', price = 12
WHERE currency = 'SAR' AND package_id = '22222222-2222-4222-8222-000000000002';
UPDATE price_rules SET currency = 'USD', price = 23
WHERE currency = 'SAR' AND package_id = '22222222-2222-4222-8222-000000000003';

UPDATE price_rules SET currency = 'USD', price = GREATEST(1, ROUND(price * 0.2667))
WHERE currency = 'SAR';

-- the dashboard proposes this currency when the interpreter adds a price for these countries
UPDATE countries SET default_currency = 'USD' WHERE default_currency = 'SAR';
