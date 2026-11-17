-- Зміна власного пароля (POST /auth/api/account/password) перевіряє поточний пароль: ці перевірки пишуться в той
-- самий журнал з методом PASSWORD_CHANGE і рахуються в блокування за логіном разом зі спробами входу, тож вкрадений
-- access token не дає перебирати пароль в обхід блокування сторінки входу.
ALTER TABLE login_attempt ALTER COLUMN method TYPE varchar(20);
ALTER TABLE login_attempt DROP CONSTRAINT login_attempt_method_check;
ALTER TABLE login_attempt ADD CONSTRAINT login_attempt_method_check
    CHECK (method IN ('PASSWORD', 'GOOGLE', 'PASSWORD_CHANGE'));
