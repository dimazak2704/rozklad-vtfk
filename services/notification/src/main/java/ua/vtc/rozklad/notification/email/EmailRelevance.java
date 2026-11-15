package ua.vtc.rozklad.notification.email;

import java.util.Optional;

/**
 * The last check before a letter goes out: the module that knows the change can say it is not worth sending any
 * more (cancelled, answered in Telegram during the settle delay, moved to another teacher). Such a letter is
 * FAILED with the reason instead of being sent. Implemented next to the substitutions, so this package does not
 * depend on them.
 */
public interface EmailRelevance {

    /** @return why {@code message} must not be sent any more, or empty to send it */
    Optional<String> obsoleteReason(EmailMessage message);
}
