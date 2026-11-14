package ua.vtc.rozklad.core.shared.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.time.StandClock;

/** Limits of the realtime stream: how long one lives and how many may be open. */
class SseBroadcasterTest {

    private static final Instant NOW = Instant.parse("2026-11-14T08:00:00Z");
    private static final Instant IN_FIVE_MINUTES = NOW.plusSeconds(300);

    private final SseBroadcaster broadcaster = new SseBroadcaster(Clock.fixed(NOW, ZoneId.of("Europe/Kyiv")));

    @Test
    void aStreamEndsWhenItsAccessTokenExpires() {
        assertThat(broadcaster.subscribe(teacher(1), IN_FIVE_MINUTES).getTimeout()).isEqualTo(300_000L);
        assertThat(broadcaster.subscribe(teacher(2), NOW.plus(Duration.ofHours(10))).getTimeout())
                .as("never longer than the usual reconnect period").isEqualTo(Duration.ofMinutes(30).toMillis());
        assertThat(broadcaster.subscribe(teacher(3), NOW.minusSeconds(30)).getTimeout())
                .as("a token accepted within the clock skew gets a stream that closes at once").isEqualTo(1_000L);
        assertThat(broadcaster.subscribe(teacher(4), null).getTimeout()).isEqualTo(Duration.ofMinutes(30).toMillis());
    }

    @Test
    void tokenExpiryIsMeasuredInRealTimeNotInTheStandTime() {
        Clock real = Clock.fixed(NOW, ZoneId.of("Europe/Kyiv"));
        SseBroadcaster onStand = new SseBroadcaster(StandClock.create(real, "2026-11-20T10:00", NOW.toString(), false));

        assertThat(onStand.subscribe(teacher(1), IN_FIVE_MINUTES).getTimeout()).isEqualTo(300_000L);
    }

    @Test
    void oneAccountGetsAtMostTenStreams() {
        CurrentUser teacher = teacher(1);
        for (int tab = 0; tab < SseBroadcaster.MAX_STREAMS_PER_USER; tab++) {
            broadcaster.subscribe(teacher, IN_FIVE_MINUTES);
        }

        assertThatThrownBy(() -> broadcaster.subscribe(teacher, IN_FIVE_MINUTES))
                .isInstanceOfSatisfying(ErrorResponseException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
                    assertThat(error.getBody().getType()).hasToString("urn:rozklad:problem:too-many-requests");
                });
        assertThat(broadcaster.subscriberCount()).isEqualTo(10);

        broadcaster.subscribe(teacher(2), IN_FIVE_MINUTES);
        assertThat(broadcaster.subscriberCount()).isEqualTo(11);
    }

    @Test
    void allStreamsTogetherAreCappedSoTheServerKeepsConnectionsForEveryoneElse() {
        int users = SseBroadcaster.MAX_STREAMS / SseBroadcaster.MAX_STREAMS_PER_USER;
        for (int user = 0; user < users; user++) {
            for (int tab = 0; tab < SseBroadcaster.MAX_STREAMS_PER_USER; tab++) {
                broadcaster.subscribe(teacher(user), IN_FIVE_MINUTES);
            }
        }

        assertThatThrownBy(() -> broadcaster.subscribe(teacher(users), IN_FIVE_MINUTES))
                .isInstanceOfSatisfying(ErrorResponseException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
                });
        assertThat(broadcaster.subscriberCount()).isEqualTo(SseBroadcaster.MAX_STREAMS);
    }

    private static CurrentUser teacher(int number) {
        return new CurrentUser(new UUID(0, number), "teacher" + number, Set.of("TEACHER"));
    }
}
