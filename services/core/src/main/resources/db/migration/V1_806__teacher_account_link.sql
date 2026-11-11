-- Зв'язок викладача з обліковим записом більше не прив'язаний до Keycloak: це id облікового запису
-- сервісу входу (той самий UUID, що й sub у токені). Значення не змінюються, лише назви.
ALTER TABLE teacher RENAME COLUMN keycloak_user_id TO account_id;
ALTER TABLE teacher RENAME CONSTRAINT uq_teacher_keycloak_user TO uq_teacher_account;
