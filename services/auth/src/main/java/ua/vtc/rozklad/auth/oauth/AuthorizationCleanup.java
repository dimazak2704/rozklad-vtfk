package ua.vtc.rozklad.auth.oauth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly cleanup of {@code oauth2_authorization}: rows whose code, access and ID tokens all expired more than
 * 24 h ago and that hold no live refresh token. The margin keeps {@code id_token_hint} usable for logout
 * during a session (Spring AS accepts an expired ID token there).
 */
@Component
public class AuthorizationCleanup {

    static final Duration MARGIN = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(AuthorizationCleanup.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuthorizationCleanup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 17 * * * *")
    public void run() {
        int removed = purge(clock.instant());
        if (removed > 0) {
            log.info("removed {} expired authorizations", removed);
        }
    }

    int purge(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(MARGIN));
        return jdbc.sql("""
                DELETE FROM oauth2_authorization
                 WHERE coalesce(authorization_code_expires_at, access_token_expires_at, oidc_id_token_expires_at,
                                refresh_token_expires_at, device_code_expires_at) IS NOT NULL
                   AND coalesce(authorization_code_expires_at, '-infinity') < :cutoff
                   AND coalesce(access_token_expires_at, '-infinity') < :cutoff
                   AND coalesce(oidc_id_token_expires_at, '-infinity') < :cutoff
                   AND coalesce(device_code_expires_at, '-infinity') < :cutoff
                   AND coalesce(refresh_token_expires_at, '-infinity') < :now
                """)
                .param("cutoff", cutoff)
                .param("now", Timestamp.from(now))
                .update();
    }
}
