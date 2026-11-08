-- викладач без Telegram: коли запит не вдалося надіслати і коли про це сказали адмінам
-- (попередження збираються пачкою: одне на викладача й дату, а не по одному на кожну зміну)
ALTER TABLE substitution_request
    ADD COLUMN no_telegram_at  timestamptz,
    ADD COLUMN admin_warned_at timestamptz;

CREATE INDEX idx_substitution_request_no_telegram ON substitution_request (no_telegram_at)
    WHERE status = 'PENDING_CONFIRMATION' AND no_telegram_at IS NOT NULL AND admin_warned_at IS NULL;
