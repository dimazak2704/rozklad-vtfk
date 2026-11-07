package ua.vtc.rozklad.notification.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import ua.vtc.rozklad.notification.config.InfrastructureConfig;

/** Test clock in Kyiv time, starting on Monday 19.10.2026 08:00 (order №35); tests move it explicitly. */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-10-19T05:00:00Z");

    private volatile Instant now = START;

    @Override
    public ZoneId getZone() {
        return InfrastructureConfig.KYIV;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    public void reset() {
        this.now = START;
    }
}
