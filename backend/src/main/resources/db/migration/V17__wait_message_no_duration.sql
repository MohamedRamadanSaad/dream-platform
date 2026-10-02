-- V17: the reply time is now always shown from the numbers the interpreter sets (hours, or a day range).
-- The seeded extra message repeated a fixed "two to three days", which contradicts any other setting.
-- Replace it with a duration-free note, only where the interpreter has not edited it.
UPDATE app_settings
SET value = 'نمنح كل رؤيا حقها من الدراسة المتأنية وتحليل الرموز.', updated_at = now()
WHERE key = 'wait.message_ar' AND value LIKE 'نظراً لكثرة الرؤى، يستغرق التعبير حالياً من يومين إلى ثلاثة أيام%';

UPDATE app_settings
SET value = 'Every dream gets the careful study it deserves.', updated_at = now()
WHERE key = 'wait.message_en' AND value LIKE 'Due to high demand, interpretations currently take two to three days%';
