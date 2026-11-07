package ua.vtc.rozklad.notification.delivery;

public enum OutboundStatus {
    /** Waiting for its first attempt (or deferred by the rate limiter). */
    QUEUED,
    /** Failed transiently, next attempt at {@code next_attempt_at}. */
    RETRYING,
    SENT,
    /** Permanently failed: rejected by Telegram, attempts exhausted, stale or chat unbound. */
    FAILED;

    public boolean isPending() {
        return this == QUEUED || this == RETRYING;
    }
}
