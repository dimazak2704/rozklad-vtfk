package ua.vtc.rozklad.core.identity.internal;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Serializes links between teachers and accounts (transaction-scoped PostgreSQL advisory locks):
 * the account first, then the teacher, always in this order. Two parallel links of one account
 * then run one after another, and the second sees the first one's result instead of failing on
 * the unique {@code keycloak_user_id} index.
 */
@Component
class AccountLinkLocks {

    private final JdbcTemplate jdbc;

    AccountLinkLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockAccountAndTeacher(UUID accountId, UUID teacherId) {
        lock("teacher-account:" + accountId);
        lockTeacher(teacherId);
    }

    void lockTeacher(UUID teacherId) {
        lock("account-of-teacher:" + teacherId);
    }

    private void lock(String key) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?, 0))", Integer.class, key);
    }
}
