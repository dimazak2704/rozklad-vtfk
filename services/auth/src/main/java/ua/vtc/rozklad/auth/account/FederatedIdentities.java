package ua.vtc.rozklad.auth.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Google bindings ({@code federated_identity}): an account is found by Google's stable {@code sub}, never by
 * the e-mail alone once it is bound. One binding per account and one account per {@code sub}.
 */
@Repository
public class FederatedIdentities {

    public static final String GOOGLE = "google";

    private final JdbcClient jdbc;

    public FederatedIdentities(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The account bound to this Google {@code sub}. */
    public Optional<UUID> accountOf(String subject) {
        return jdbc.sql("SELECT account_id FROM federated_identity WHERE provider = ? AND subject = ?")
                .params(GOOGLE, subject).query(UUID.class).optional();
    }

    /** The Google {@code sub} the account is bound to. */
    public Optional<String> subjectOf(UUID accountId) {
        return jdbc.sql("SELECT subject FROM federated_identity WHERE provider = ? AND account_id = ?")
                .params(GOOGLE, accountId).query(String.class).optional();
    }

    /**
     * Binds the account to the {@code sub} (first Google sign-in). {@code false} when a concurrent sign-in won:
     * the caller re-reads the binding instead of failing on the unique constraints.
     */
    public boolean bind(UUID accountId, String subject, String email, String hostedDomain, Instant now) {
        return jdbc.sql("""
                INSERT INTO federated_identity (id, account_id, provider, subject, email_at_link, hosted_domain,
                                                linked_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """)
                .params(UUID.randomUUID(), accountId, GOOGLE, subject, email, hostedDomain, Timestamp.from(now),
                        Timestamp.from(now))
                .update() > 0;
    }

    public void recordLogin(String subject, Instant now) {
        jdbc.sql("UPDATE federated_identity SET last_login_at = ? WHERE provider = ? AND subject = ?")
                .params(Timestamp.from(now), GOOGLE, subject).update();
    }
}
