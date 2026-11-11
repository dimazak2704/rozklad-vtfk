package ua.vtc.rozklad.auth.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
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
        jdbc.sql("""
                INSERT INTO login_attempt (occurred_at, method, identifier_hash, account_id, ip, ip_prefix, outcome)
                VALUES (?, ?, ?, ?, ?::inet, ?::cidr, ?)
                """)
                .params(Timestamp.from(at), method.name(), identifierHash, accountId, address.ip(), address.prefix(),
                        outcome.name())
                .update();
    }

    /** What the lockout rules need about one password attempt (DESIGN §4.3). */
    public LockoutPolicy.Stats stats(byte[] identifierHash, String prefix, Instant now, AuthServerProperties.Lockout lockout) {
        Instant keySince = now.minus(lockout.window());
        Instant globalSince = now.minus(lockout.slowdownWindow());
        Instant networkSince = now.minus(lockout.networkWindow());
        return jdbc.sql("""
                WITH last_success AS (
                    SELECT coalesce(max(occurred_at), '-infinity'::timestamptz) AS at FROM login_attempt
                     WHERE identifier_hash = :hash AND ip_prefix IS NOT DISTINCT FROM :prefix::cidr
                       AND outcome = 'SUCCESS')
                SELECT (SELECT count(*) FROM login_attempt, last_success
                         WHERE identifier_hash = :hash AND ip_prefix IS NOT DISTINCT FROM :prefix::cidr
                           AND method = 'PASSWORD' AND outcome IN %1$s
                           AND occurred_at > greatest(:keySince, last_success.at)) AS key_failures,
                       (SELECT max(occurred_at) FROM login_attempt, last_success
                         WHERE identifier_hash = :hash AND ip_prefix IS NOT DISTINCT FROM :prefix::cidr
                           AND method = 'PASSWORD' AND outcome IN %1$s
                           AND occurred_at > greatest(:keySince, last_success.at)) AS last_failure,
                       (SELECT count(*) FROM login_attempt
                         WHERE identifier_hash = :hash AND method = 'PASSWORD' AND outcome IN %1$s
                           AND occurred_at > :globalSince) AS identifier_failures,
                       (SELECT count(DISTINCT identifier_hash) FROM login_attempt
                         WHERE :prefix::cidr IS NOT NULL AND ip_prefix = :prefix::cidr AND method = 'PASSWORD'
                           AND outcome IN %1$s AND occurred_at > :networkSince) AS network_identifiers
                """.formatted(FAILURES))
                .param("hash", identifierHash)
                .param("prefix", prefix)
                .param("keySince", Timestamp.from(keySince))
                .param("globalSince", Timestamp.from(globalSince))
                .param("networkSince", Timestamp.from(networkSince))
                .query((rs, row) -> {
                    Timestamp last = rs.getTimestamp("last_failure");
                    return new LockoutPolicy.Stats(rs.getInt("key_failures"), last == null ? null : last.toInstant(),
                            rs.getInt("identifier_failures"), rs.getInt("network_identifiers"));
                })
                .single();
    }

    /** Deletes journal rows older than {@code retention}; returns how many. */
    public int purgeOlderThan(Instant cutoff) {
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
