-- V15: thresholds of the interpreter's insights (GET /admin/analytics/insights) and the Excel export row cap.
INSERT INTO app_settings (key, value, type, description) VALUES
    ('insights.awaiting_reply_days',    '3',     'INT', 'Insights: report dreams waiting for the user''s reply longer than N days'),
    ('insights.traffic_change_percent', '10',    'INT', 'Insights: report visits up/down by at least N% vs the same days of last month'),
    ('insights.streak_min_days',        '3',     'INT', 'Insights: celebrate interpreting on at least N consecutive days'),
    ('reports.excel_max_rows',          '50000', 'INT', 'Maximum number of rows in the dreams Excel export')
ON CONFLICT (key) DO NOTHING;
