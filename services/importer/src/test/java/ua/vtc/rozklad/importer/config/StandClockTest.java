package ua.vtc.rozklad.importer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StandClockTest {

    private static final ZoneId KYIV = InfrastructureConfig.KYIV;
    /** Saturday 14.11.2026 12:00 in Kyiv. */
    private static final Instant REAL = Instant.parse("2026-11-14T10:00:00Z");

    @Test
    void blankStandTimeKeepsTheRealClock() {
        Clock real = Clock.fixed(REAL, KYIV);

        assertThat(StandClock.create(real, null, null, false)).isSameAs(real);
        assertThat(StandClock.create(real, "  ", "", true)).isSameAs(real);
        assertThat(StandClock.isShifted(real)).isFalse();
        assertThat(StandClock.real(real)).isSameAs(real);
    }

    @Test
    void showsTheStandTimeAtTheAnchorAndKeepsTicking() {
        MovingClock real = new MovingClock(REAL);

        Clock stand = StandClock.create(real, "2026-11-20T10:00", "2026-11-14T10:00:00Z", false);

        assertThat(LocalDateTime.now(stand)).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
        real.advance(Duration.ofMinutes(25));
        assertThat(LocalDateTime.now(stand)).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 25));
        assertThat(stand.millis()).isEqualTo(stand.instant().toEpochMilli());
        assertThat(stand.getZone()).isEqualTo(KYIV);
        assertThat(StandClock.isShifted(stand)).isTrue();
        assertThat(StandClock.real(stand)).isSameAs(real);
        assertThat(((StandClock) stand).offset()).isEqualTo(Duration.ofDays(6).minusHours(2));
    }

    @Test
    void anAnchorInThePastMovesTheStandTimeOnByTheTimeSinceThen() {
        Clock real = Clock.fixed(REAL, KYIV);

        Clock stand = StandClock.create(real, "2026-11-20T10:00", "2026-11-14T09:00:00Z", false);

        assertThat(LocalDateTime.now(stand)).isEqualTo(LocalDateTime.of(2026, 11, 20, 11, 0));
    }

    @Test
    void withoutAnAnchorTheStandTimeStartsNow() {
        Clock stand = StandClock.create(Clock.fixed(REAL, KYIV), "2026-11-20T10:00", null, false);

        assertThat(LocalDateTime.now(stand)).isEqualTo(LocalDateTime.of(2026, 11, 20, 10, 0));
    }

    @Test
    void anotherZoneShowsTheSameShiftedInstant() {
        Clock stand = StandClock.create(Clock.fixed(REAL, KYIV), "2026-11-20T10:00", null, false);

        Clock utc = stand.withZone(ZoneOffset.UTC);

        assertThat(utc.instant()).isEqualTo(stand.instant()).isEqualTo(Instant.parse("2026-11-20T08:00:00Z"));
        assertThat(utc.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(stand.withZone(KYIV)).isSameAs(stand);
    }

    @Test
    void productionRefusesTheStandTime() {
        assertThatThrownBy(() -> StandClock.create(Clock.fixed(REAL, KYIV), "2026-11-20T10:00", null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROZKLAD_STAND_TIME")
                .hasMessageContaining("production");
    }

    @Test
    void anUnreadableStandTimeStopsTheStart() {
        Clock real = Clock.fixed(REAL, KYIV);

        assertThatThrownBy(() -> StandClock.create(real, "20.11.2026 10:00", null, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2026-11-20T10:00");
        assertThatThrownBy(() -> StandClock.create(real, "2026-11-20T10:00", "yesterday", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ROZKLAD_STAND_TIME_ANCHOR");
    }

    @Test
    void productionIsAPublicHttpsIssuerOrTheProdProfile() {
        assertThat(StandClock.isProduction(issuer("https://rozklad.vtc.vn.ua/auth"))).isTrue();
        assertThat(StandClock.isProduction(issuer("http://localhost:8088/auth"))).isFalse();
        assertThat(StandClock.isProduction(issuer("https://localhost:8443/auth"))).isFalse();
        assertThat(StandClock.isProduction(issuer("https://127.0.0.1/auth"))).isFalse();
        assertThat(StandClock.isProduction(new MockEnvironment())).isFalse();
        MockEnvironment prod = issuer("http://localhost:8088/auth");
        prod.setActiveProfiles("prod");
        assertThat(StandClock.isProduction(prod)).isTrue();
    }

    @Test
    void theLanModeOnAPrivateAddressIsNotProduction() {
        assertThat(StandClock.isProduction(lan("https://192.168.31.112/auth"))).isFalse();
        assertThat(StandClock.isProduction(lan("https://10.0.0.5:8443/auth"))).isFalse();
        assertThat(StandClock.isProduction(issuer("https://192.168.31.112/auth"))).isTrue();
        assertThat(StandClock.isProduction(lan("https://rozklad.vtc.vn.ua/auth"))).isTrue();
        assertThat(StandClock.isProduction(lan("https://192.168.1.1.example.com/auth"))).isTrue();
        assertThat(StandClock.isProduction(lan("https://172.32.0.1/auth"))).isTrue();
        MockEnvironment prod = lan("https://192.168.31.112/auth");
        prod.setActiveProfiles("prod");
        assertThat(StandClock.isProduction(prod)).isTrue();
    }

    @Test
    void theClockBeanRefusesTheStandTimeWithAnHttpsIssuer() {
        MockEnvironment environment = issuer("https://rozklad.vtc.vn.ua/auth")
                .withProperty("app.stand-time.at", "2026-11-20T10:00");

        assertThatThrownBy(() -> new InfrastructureConfig().clock(environment)).isInstanceOf(IllegalStateException.class);
        assertThat(new InfrastructureConfig().clock(issuer("https://rozklad.vtc.vn.ua/auth"))).isNotInstanceOf(StandClock.class);
        assertThat(new InfrastructureConfig().clock(new MockEnvironment().withProperty("app.stand-time.at", "2026-11-20T10:00")))
                .isInstanceOf(StandClock.class);
    }

    private static MockEnvironment lan(String issuer) {
        return issuer(issuer).withProperty("app.lan-dev", "true");
    }

    private static MockEnvironment issuer(String issuer) {
        return new MockEnvironment().withProperty("app.auth.issuer", issuer);
    }

    /** A real clock the test moves by hand. */
    private static final class MovingClock extends Clock {

        private final AtomicReference<Instant> now;

        MovingClock(Instant start) {
            now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(at -> at.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return KYIV;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
