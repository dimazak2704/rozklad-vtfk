-- Типовий режим погодження замін тепер NOTIFY (SystemSettings.DEFAULTS). Значення, яке ніхто не змінював
-- (записане міграцією V1_802 від імені 'system'), переводимо на новий типовий; вибір адміністратора лишається.
UPDATE setting
SET value      = '"NOTIFY"',
    updated_at = now()
WHERE key = 'substitution.approvalMode'
  AND updated_by = 'system'
  AND value = '"CONFIRM"'::jsonb;
