-- Перший власний пароль облікового запису, що входить лише через Google (POST /auth/api/account/initial-password):
-- доказ — свіжий повторний вхід через Google у цій самій сесії браузера. Спроби пишуться в журнал з методом
-- PASSWORD_SET (без доказу — BAD_CREDENTIALS) і рахуються в блокування за логіном, як і зміна пароля.
ALTER TABLE login_attempt DROP CONSTRAINT login_attempt_method_check;
ALTER TABLE login_attempt ADD CONSTRAINT login_attempt_method_check
    CHECK (method IN ('PASSWORD', 'GOOGLE', 'PASSWORD_CHANGE', 'PASSWORD_SET'));
