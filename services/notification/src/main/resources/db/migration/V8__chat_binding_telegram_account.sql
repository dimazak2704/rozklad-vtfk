-- Telegram-акаунт, який прив'язав чат викладача (ім'я з профілю і @username, як надіслав Telegram):
-- викладач у кабінеті й диспетчер бачать, чий саме Telegram підключено, і помічають чужий
ALTER TABLE chat_binding
    ADD COLUMN telegram_account varchar(100);
