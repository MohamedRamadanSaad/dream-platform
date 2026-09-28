-- "Join 2,000+ people": public counter = this historical base + INTERPRETED dreams on the platform.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('stats.interpreted_base', '2000', 'INT', 'Historical interpreted dreams before the platform; added to the live INTERPRETED count in /public/stats')
ON CONFLICT (key) DO NOTHING;
