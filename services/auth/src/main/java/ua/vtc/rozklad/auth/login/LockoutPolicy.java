package ua.vtc.rozklad.auth.login;

import java.time.Duration;
import java.time.Instant;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Decides whether a password attempt may proceed (DESIGN §4.3). Pure logic over {@link Stats}:
 * <ul>
 *   <li>network throttle: at least {@code networkDistinctIdentifiers} different identifiers failed from the
 *       client's network within {@code networkWindow} → refused;</li>
 *   <li>lockout per identifier and network: {@code n ≥ maxFailures} failures since the last success within
 *       {@code window} → refused until {@code lastFailure + min(baseDelay × 2^(n−max), maxDelay)};</li>
 *   <li>global slowdown: at least {@code slowdownFailures} failures of the identifier from all networks
 *       within {@code slowdownWindow} → allowed after a delay, never blocked.</li>
 * </ul>
 * Unknown and existing identifiers behave the same: the statistics are keyed by the identifier hash only.
 */
public final class LockoutPolicy {

    private final AuthServerProperties.Lockout config;

    public LockoutPolicy(AuthServerProperties.Lockout config) {
        this.config = config;
    }

    public Decision decide(Stats stats, Instant now) {
        if (stats.networkIdentifiers() >= config.networkDistinctIdentifiers()) {
            return Decision.THROTTLED;
        }
        if (stats.keyFailures() >= config.maxFailures() && stats.lastFailure() != null) {
            Instant until = stats.lastFailure().plus(delay(stats.keyFailures()));
            if (now.isBefore(until)) {
                return Decision.LOCKED;
            }
        }
        if (stats.identifierFailures() >= config.slowdownFailures()) {
            return Decision.SLOW;
        }
        return Decision.ALLOW;
    }

    /** {@code min(baseDelay × 2^(n−max), maxDelay)} without overflow. */
    Duration delay(int failures) {
        int exponent = Math.max(0, failures - config.maxFailures());
        Duration max = config.maxDelay();
        Duration delay = config.baseDelay();
        for (int i = 0; i < exponent && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    /**
     * @param keyFailures failures on (identifier, network) since the last success there, within the window
     * @param lastFailure the newest of those failures, or {@code null}
     * @param identifierFailures failures of the identifier from all networks within the slowdown window
     * @param networkIdentifiers distinct identifiers that failed from the network within the network window
     */
    public record Stats(int keyFailures, Instant lastFailure, int identifierFailures, int networkIdentifiers) {
    }

    public enum Decision { ALLOW, SLOW, LOCKED, THROTTLED }
}
