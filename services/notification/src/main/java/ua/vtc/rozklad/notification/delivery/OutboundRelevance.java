package ua.vtc.rozklad.notification.delivery;

import java.util.Optional;

/**
 * The last check before a queued message goes out: the module that queued it can say it is no longer worth
 * sending (a substitution request whose change went to another teacher or was cancelled while the message
 * waited for a rate-limit token or a retry). Such a message is FAILED with the reason instead of being sent.
 */
public interface OutboundRelevance {

    /** @return why {@code message} must not be sent any more, or empty to send it */
    Optional<String> obsoleteReason(OutboundMessage message);
}
