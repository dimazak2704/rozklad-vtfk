package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.login.LockoutPolicy.Decision;
import ua.vtc.rozklad.auth.login.LockoutPolicy.Stats;

class LockoutPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private final LockoutPolicy policy = new LockoutPolicy(new AuthServerProperties.Lockout(5, Duration.ofHours(12),
            Duration.ofSeconds(60), Duration.ofMinutes(15), 50, Duration.ofHours(24), Duration.ofSeconds(2), 20,
            Duration.ofMinutes(15)));

    @Test
    void belowTheThresholdEverythingIsAllowed() {
        assertThat(policy.decide(new Stats(4, NOW.minusSeconds(1), 4, 1), NOW)).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide(new Stats(0, null, 0, 0), NOW)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void theFifthFailureLocksForOneMinute() {
        assertThat(policy.decide(new Stats(5, NOW.minusSeconds(59), 5, 1), NOW)).isEqualTo(Decision.LOCKED);
        assertThat(policy.decide(new Stats(5, NOW.minusSeconds(61), 5, 1), NOW)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void theDelayDoublesAndStopsAtFifteenMinutes() {
        assertThat(policy.delay(5)).isEqualTo(Duration.ofSeconds(60));
        assertThat(policy.delay(6)).isEqualTo(Duration.ofSeconds(120));
        assertThat(policy.delay(8)).isEqualTo(Duration.ofSeconds(480));
        assertThat(policy.delay(9)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.delay(40)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.delay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.decide(new Stats(7, NOW.minusSeconds(200), 7, 1), NOW)).isEqualTo(Decision.LOCKED);
        assertThat(policy.decide(new Stats(7, NOW.minusSeconds(241), 7, 1), NOW)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void manyFailuresFromAllNetworksOnlySlowDown() {
        assertThat(policy.decide(new Stats(0, null, 50, 1), NOW)).isEqualTo(Decision.SLOW);
        assertThat(policy.decide(new Stats(1, NOW, 49, 1), NOW)).isEqualTo(Decision.ALLOW);
    }

    @Test
    void aSprayingNetworkIsThrottledFirst() {
        assertThat(policy.decide(new Stats(0, null, 0, 20), NOW)).isEqualTo(Decision.THROTTLED);
        assertThat(policy.decide(new Stats(9, NOW, 60, 25), NOW)).isEqualTo(Decision.THROTTLED);
        assertThat(policy.decide(new Stats(0, null, 0, 19), NOW)).isEqualTo(Decision.ALLOW);
    }
}
