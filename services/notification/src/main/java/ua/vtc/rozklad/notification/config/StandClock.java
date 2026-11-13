package ua.vtc.rozklad.notification.config;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Development "stand time" (a time machine for the demo stand): the domain clock shows
 * {@code ROZKLAD_STAND_TIME} (a Kyiv local date-time) at the anchor moment and keeps ticking from
 * there. The anchor ({@code ROZKLAD_STAND_TIME_ANCHOR}, an ISO instant) is the real moment the
 * stand time refers to; {@code scripts/dev-stack.sh up --time} sets one anchor for every service so
 * their clocks agree. Without an anchor the service's start is used.
 * <p>
 * Only the domain follows this clock. Code that must keep real time (outbox leases and staleness,
 * dedup retention, one-time codes and invites, rate limits) takes {@link #real(Clock)}; token
 * validation never sees the clock bean at all. Production refuses the setting at startup.
 */
public final class StandClock extends Clock {

    private static final Logger log = LoggerFactory.getLogger(StandClock.class);

    private final Clock real;
    private final Duration offset;

    StandClock(Clock real, Duration offset) {
        this.real = Objects.requireNonNull(real);
        this.offset = Objects.requireNonNull(offset);
    }

    /**
     * The clock of the domain: {@code real} when {@code standTime} is blank, otherwise a clock
     * shifted so that it shows {@code standTime} (local time in the zone of {@code real}) at
     * {@code anchor} (or now, when blank).
     *
     * @throws IllegalStateException when the stand time is set in production or cannot be parsed
     */
    public static Clock create(Clock real, String standTime, String anchor, boolean production) {
        if (!StringUtils.hasText(standTime)) {
            return real;
        }
        if (production) {
            throw new IllegalStateException("ROZKLAD_STAND_TIME is set in production; the stand time "
                    + "(time machine) is for the development stand only: remove it");
        }
        LocalDateTime target;
        Instant at;
        try {
            target = LocalDateTime.parse(standTime.strip());
            at = StringUtils.hasText(anchor) ? Instant.parse(anchor.strip()) : real.instant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("ROZKLAD_STAND_TIME must look like 2026-11-20T10:00 and "
                    + "ROZKLAD_STAND_TIME_ANCHOR like 2026-11-14T08:00:00Z: " + e.getParsedString(), e);
        }
        Duration offset = Duration.between(at, target.atZone(real.getZone()).toInstant());
        StandClock clock = new StandClock(real, offset);
        log.warn("Stand time is ON (development only): the domain clock shows {} now, offset {} from real time",
                LocalDateTime.now(clock), offset);
        return clock;
    }

    /**
     * Production, as auth's ProdGuard sees it: an https issuer that is not local, or the Spring
     * profile {@code prod}.
     */
    public static boolean isProduction(Environment environment) {
        if (environment.matchesProfiles("prod")) {
            return true;
        }
        String issuer = environment.getProperty("app.auth.issuer");
        if (!StringUtils.hasText(issuer)) {
            return false;
        }
        URI uri = URI.create(issuer.strip());
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && !host.equalsIgnoreCase("localhost") && !host.endsWith(".localhost")
                && !host.startsWith("127.") && !host.equals("[::1]");
    }

    /** The real clock behind a stand clock; any other clock (a test's fixed clock) as it is. */
    public static Clock real(Clock clock) {
        return clock instanceof StandClock stand ? stand.real : clock;
    }

    /** Whether the clock is shifted from real time (the stand time is on). */
    public static boolean isShifted(Clock clock) {
        return clock instanceof StandClock;
    }

    public Duration offset() {
        return offset;
    }

    @Override
    public ZoneId getZone() {
        return real.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(real.getZone()) ? this : new StandClock(real.withZone(zone), offset);
    }

    @Override
    public Instant instant() {
        return real.instant().plus(offset);
    }

    @Override
    public long millis() {
        return real.millis() + offset.toMillis();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StandClock that && real.equals(that.real) && offset.equals(that.offset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(real, offset);
    }

    @Override
    public String toString() {
        return "StandClock[" + real + ", offset " + offset + "]";
    }
}
