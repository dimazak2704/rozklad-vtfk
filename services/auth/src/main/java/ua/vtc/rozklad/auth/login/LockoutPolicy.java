package ua.vtc.rozklad.auth.login;

import java.time.Duration;
import java.time.Instant;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Decides whether a password attempt may proceed (DESIGN §4.3). Pure logic over {@link Stats}:
 * <ul>
 *   <li>network throttle: at least {@code networkDistinctIdentifiers} different identifiers failed from the
 *       client's network within {@code networkWindow} → refused;</li>
 *   <li>lockout per key: {@code n ≥ maxFailures} failures since the last success within {@code window} →
 *       refused until {@code lastFailure + min(baseDelay × 2^(n−max), maxDelay)}. The key is (identifier,
 *       network), or the device for a browser that already signed in as this identifier ({@link LoginDevices});</li>
 *   <li>global gate: at least {@code slowdownFailures} failures of the identifier from all networks within
 *       {@code slowdownWindow} → one password check per {@link #gateDelay} for the identifier, the rest are
 *       refused without BCrypt ({@link Decision#GATED}).</li>
 * </ul>
 * A trusted device skips the network throttle and the global gate: other people's failures on a shared NAT or
 * from a botnet do not lock its owner out, while its own failures still lock it. Unknown and existing
 * identifiers behave the same: the statistics are keyed by the identifier hash only.
 */
public final class LockoutPolicy {

    private final AuthServerProperties.Lockout config;

    public LockoutPolicy(AuthServerProperties.Lockout config) {
        this.config = config;
    }

    public Decision decide(Stats stats, Instant now) {
        if (!stats.trustedDevice() && stats.networkIdentifiers() >= config.networkDistinctIdentifiers()) {
            return Decision.THROTTLED;
        }
        if (stats.keyFailures() >= config.maxFailures() && stats.lastFailure() != null) {
            Instant until = stats.lastFailure().plus(delay(stats.keyFailures()));
            if (now.isBefore(until)) {
                return Decision.LOCKED;
            }
        }
        if (!stats.trustedDevice() && stats.identifierFailures() >= config.slowdownFailures()) {
            return Decision.GATED;
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
     * The spacing of password checks behind the global gate: {@code slowdownDelay} per {@code slowdownFailures}
     * failures (2 s at 50, 20 s at 500), at most {@code maxDelay}. A guesser feeding the counter slows itself
     * down, so a day of guessing from any number of networks stays in the low thousands.
     */
    public Duration gateDelay(Stats stats) {
        long steps = Math.max(1, stats.identifierFailures() / config.slowdownFailures());
        Duration max = config.maxDelay();
        if (steps >= max.dividedBy(config.slowdownDelay())) {
            return max;
        }
        return config.slowdownDelay().multipliedBy(steps);
    }

    /**
     * @param keyFailures failures on the key since the last success there, within the window
     * @param lastFailure the newest of those failures, or {@code null}
     * @param identifierFailures failures of the identifier without a trusted device, all networks, slowdown window
     * @param networkIdentifiers distinct identifiers that failed from the network (without a trusted device)
     *        within the network window
     * @param trustedDevice the browser already signed in as this identifier ({@link LoginDevices})
     */
    public record Stats(int keyFailures, Instant lastFailure, int identifierFailures, int networkIdentifiers,
            boolean trustedDevice) {
    }

    public enum Decision { ALLOW, GATED, LOCKED, THROTTLED }
}
