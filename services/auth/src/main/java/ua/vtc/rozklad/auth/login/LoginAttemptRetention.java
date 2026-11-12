package ua.vtc.rozklad.auth.login;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the sign-in journal and the trusted devices for 90 days. */
@Component
public class LoginAttemptRetention {

    static final Duration RETENTION = Duration.ofDays(90);
    private static final Logger log = LoggerFactory.getLogger(LoginAttemptRetention.class);

    private final LoginAttempts attempts;
    private final LoginDevices devices;
    private final Clock clock;

    public LoginAttemptRetention(LoginAttempts attempts, LoginDevices devices, Clock clock) {
        this.attempts = attempts;
        this.devices = devices;
        this.clock = clock;
    }

    @Scheduled(cron = "0 41 3 * * *")
    public void run() {
        int removed = attempts.purgeOlderThan(clock.instant().minus(RETENTION));
        devices.purgeOlderThan(clock.instant().minus(LoginDevices.LIFETIME));
        if (removed > 0) {
            log.info("removed {} sign-in journal rows older than {} days", removed, RETENTION.toDays());
        }
    }
}
