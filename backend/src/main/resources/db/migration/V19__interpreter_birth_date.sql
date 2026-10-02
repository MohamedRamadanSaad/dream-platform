-- V19: profile data fix (docs/SESSIONS_PROFILE_CONTRACT.md §4): the date of birth of the active interpreter accounts.
UPDATE users SET birth_date = DATE '1988-03-06' WHERE role = 'INTERPRETER' AND deleted_at IS NULL;
