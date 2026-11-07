package ua.vtc.rozklad.notification.delivery;

import java.time.Duration;

/** Token buckets for Telegram calls: one for the whole bot and one per chat. */
public interface RateLimiter {

    /**
     * Takes a token from the global bucket and from the chat's bucket if both have one.
     *
     * @return {@link Duration#ZERO} when acquired, otherwise how long to wait (nothing is taken)
     */
    Duration tryAcquire(long chatId);
}
