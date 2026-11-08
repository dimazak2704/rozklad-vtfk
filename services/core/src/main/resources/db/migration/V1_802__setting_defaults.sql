-- Типові налаштування (DOMAIN §7–§9; підпис і назва — з розпоряджень коледжу №35, №96, №105).
-- Ті самі значення має SystemSettings.DEFAULTS; наявні значення не перезаписуються.
INSERT INTO setting (key, value, updated_at, updated_by)
VALUES ('substitution.approvalMode', '"CONFIRM"', now(), 'system'),
       ('substitution.confirmTimeoutMinutes', '120', now(), 'system'),
       ('order.signatoryPosition', '"Заступник директора з НР"', now(), 'system'),
       ('order.signatoryName', '"Микола ПОЛІЩУК"', now(), 'system'),
       ('general.collegeName', '"Вінницький технічний фаховий коледж"', now(), 'system'),
       ('accounting.hoursPerPair', '2', now(), 'system'),
       ('public.showTeacherFullNames', 'false', now(), 'system')
ON CONFLICT (key) DO NOTHING;

-- типовий профіль дзвінків — «2 зміни, 60 хв» з міграції V1_201
INSERT INTO setting (key, value, updated_at, updated_by)
VALUES ('calendar.activeBellProfileId',
        COALESCE((SELECT to_jsonb(id::text) FROM bell_profile WHERE name = '2 зміни, 60 хв'), 'null'::jsonb),
        now(), 'system')
ON CONFLICT (key) DO NOTHING;
