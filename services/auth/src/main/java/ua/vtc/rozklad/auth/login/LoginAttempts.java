package ua.vtc.rozklad.auth.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * The {@code login_attempt} journal: input of the lockout rules and the audit of sign-in decisions.
 * Identifiers are stored as SHA-256 of the trimmed lower-case value; passwords never.
 */
@Repository
public class LoginAttempts {

    /** Outcomes that count as failures of a password sign-in. */
    private static final String FAILURES = "('BAD_CREDENTIALS', 'DISABLED', 'REVOKED')";

    private final JdbcClient jdbc;

    public LoginAttempts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Method method, byte[] identifierHash, UUID accountId, ClientAddress address, Outcome outcome,
            Instant at) {
        record(method, identifierHash, accountId, address, null, outcome, at);
    }

    /** {@code deviceHash}: the token hash of a trusted device ({@link LoginDevices}), or {@code null}. */
    public void record(Method method, byte[] identifierHash, UUID accountId, ClientAddress address, byte[] deviceHash,
            Outcome outcome, Instant at) {
        jdbc.sql("""
                INSERT INTO login_attempt (occurred_at, method, identifier_hash, account_id, ip, ip_prefix, device_hash,
                                           outcome)
                VALUES (?, ?, ?, ?, ?::inet, ?::cidr, ?, ?)
                """)
                .params(Timestamp.from(at), method.name(), identifierHash, accountId, address.ip(), address.prefix(),
                        deviceHash, outcome.name())
                .update();
    }

    /**
     * What the lockout rules need about one password attempt (DESIGN §4.3). Without a trusted device the key is
     * (identifier, network) over attempts that came without one; with a device the key is the device. The
     * identifier and network counts take only attempts without a trusted device.
     */
    public LockoutPolicy.Stats stats(byte[] identifierHash, String prefix, byte[] deviceHash, Instant now,
            AuthServerProperties.Lockout lockout) {
        Instant keySince = now.minus(lockout.window());
        Instant globalSince = now.minus(lockout.slowdownWindow());
        Instant networkSince = now.minus(lockout.networkWindow());
        String key = deviceHash != null ? "identifier_hash = :hash AND device_hash = :device"
                : "identifier_hash = :hash AND ip_prefix IS NOT DISTINCT FROM :prefix::cidr AND device_hash IS NULL";
        var query = jdbc.sql("""
                WITH last_success AS (
                    SELECT coalesce(max(occurred_at), '-infinity'::timestamptz) AS at FROM login_attempt
                     WHERE %2$s AND outcome = 'SUCCESS')
                SELECT (SELECT count(*) FROM login_attempt, last_success
                         WHERE %2$s AND method = 'PASSWORD' AND outcome IN %1$s
                           AND occurred_at > greatest(:keySince, last_success.at)) AS key_failures,
                       (SELECT max(occurred_at) FROM login_attempt, last_success
                         WHERE %2$s AND method = 'PASSWORD' AND outcome IN %1$s
                           AND occurred_at > greatest(:keySince, last_success.at)) AS last_failure,
                       (SELECT count(*) FROM login_attempt
                         WHERE identifier_hash = :hash AND method = 'PASSWORD' AND outcome IN %1$s
                           AND device_hash IS NULL AND occurred_at > :globalSince) AS identifier_failures,
                       (SELECT count(DISTINCT identifier_hash) FROM login_attempt
                         WHERE :prefix::cidr IS NOT NULL AND ip_prefix = :prefix::cidr AND method = 'PASSWORD'
                           AND outcome IN %1$s AND device_hash IS NULL
                           AND occurred_at > :networkSince) AS network_identifiers
                """.formatted(FAILURES, key))
                .param("hash", identifierHash)
                .param("prefix", prefix)
                .param("keySince", Timestamp.from(keySince))
                .param("globalSince", Timestamp.from(globalSince))
                .param("networkSince", Timestamp.from(networkSince));
        if (deviceHash != null) {
            query = query.param("device", deviceHash);
        }
        return query.query((rs, row) -> {
                    Timestamp last = rs.getTimestamp("last_failure");
                    return new LockoutPolicy.Stats(rs.getInt("key_failures"), last == null ? null : last.toInstant(),
                            rs.getInt("identifier_failures"), rs.getInt("network_identifiers"), deviceHash != null);
                })
                .single();
    }

    /**
     * The global gate of an identifier: {@code true} for at most one caller per {@code delay}. The row is taken
     * by one atomic upsert, so parallel attempts cannot pass together.
     */
    public boolean passGate(byte[] identifierHash, Instant now, Duration delay) {
        return jdbc.sql("""
                INSERT INTO login_gate (identifier_hash, next_attempt_at) VALUES (:hash, :next)
                ON CONFLICT (identifier_hash) DO UPDATE SET next_attempt_at = EXCLUDED.next_attempt_at
                 WHERE login_gate.next_attempt_at <= :now
                RETURNING 1
                """)
                .param("hash", identifierHash)
                .param("next", Timestamp.from(now.plus(delay)))
                .param("now", Timestamp.from(now))
                .query(Integer.class).optional().isPresent();
    }

    /** Deletes journal rows older than {@code retention}; returns how many. */
    public int purgeOlderThan(Instant cutoff) {
        jdbc.sql("DELETE FROM login_gate WHERE next_attempt_at < ?").param(Timestamp.from(cutoff)).update();
        return jdbc.sql("DELETE FROM login_attempt WHERE occurred_at < ?").param(Timestamp.from(cutoff)).update();
    }

    public static byte[] hash(String identifier) {
        String normalised = identifier == null ? "" : identifier.strip().toLowerCase(Locale.ROOT);
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public enum Method { PASSWORD, GOOGLE }

    public enum Outcome {
        SUCCESS, BAD_CREDENTIALS, LOCKED, THROTTLED, DISABLED, REVOKED, NOT_ALLOWED, DOMAIN_MISMATCH,
        EMAIL_UNVERIFIED, IDENTITY_CONFLICT, PROVIDER_ERROR
    }
}
