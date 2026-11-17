package ua.vtc.rozklad.auth.account;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accounts and their roles over plain JDBC. Identifiers are matched in lower case. */
@Repository
public class AccountRepository {

    private static final String SELECT = """
            SELECT a.id, a.username, a.email, a.display_name, a.password_hash, a.enabled, a.disabled_reason,
                   a.demo, a.last_login_at,
                   (SELECT coalesce(array_agg(r.role ORDER BY r.role), '{}') FROM account_role r
                     WHERE r.account_id = a.id) AS roles
              FROM account a
            """;

    /** A usable admin (D12): enabled, role ADMIN, and a password or a Google binding. */
    private static final String USABLE_ADMIN = """
            a.enabled AND EXISTS (SELECT 1 FROM account_role r WHERE r.account_id = a.id AND r.role = 'ADMIN')
              AND (a.password_hash IS NOT NULL
                   OR EXISTS (SELECT 1 FROM federated_identity f WHERE f.account_id = a.id))
            """;

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = ?").param(id).query(AccountRepository::map).optional();
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.sql(SELECT + " WHERE a.username = ?").param(normalise(username))
                .query(AccountRepository::map).optional();
    }

    public Optional<Account> findByEmail(String email) {
        return jdbc.sql(SELECT + " WHERE a.email = ?").param(normalise(email))
                .query(AccountRepository::map).optional();
    }

    /** The sign-in identifier: an {@code @} means an e-mail (usernames cannot contain one). */
    public Optional<Account> findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return identifier.contains("@") ? findByEmail(identifier) : findByUsername(identifier);
    }

    /** Accounts created by the server demo seed ({@code demo = true}). */
    public List<Account> findDemo() {
        return jdbc.sql(SELECT + " WHERE a.demo ORDER BY a.username").query(AccountRepository::map).list();
    }

    public List<Account> findAllById(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE a.id = ANY(?) ORDER BY a.display_name")
                .param(ids.toArray(UUID[]::new)).query(AccountRepository::map).list();
    }

    @Transactional
    public void insert(NewAccount account, Instant now, String actor) {
        jdbc.sql("""
                INSERT INTO account (id, username, email, display_name, password_hash, password_changed_at, enabled,
                                     demo, created_at, created_by, updated_at, updated_by)
                VALUES (:id, :username, :email, :displayName, :hash, :changedAt, true, :demo, :now, :actor, :now, :actor)
                """)
                .param("id", account.id())
                .param("username", normalise(account.username()))
                .param("email", normalise(account.email()))
                .param("displayName", account.displayName())
                .param("hash", account.passwordHash())
                .param("changedAt", account.passwordHash() == null ? null : Timestamp.from(now))
                .param("demo", account.demo())
                .param("now", Timestamp.from(now))
                .param("actor", actor)
                .update();
        for (String role : account.roles()) {
            addRole(account.id(), role);
        }
    }

    public void addRole(UUID id, String role) {
        jdbc.sql("INSERT INTO account_role (account_id, role) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(id, role).update();
    }

    public void setPassword(UUID id, String passwordHash, Instant now, String actor) {
        jdbc.sql("""
                UPDATE account SET password_hash = ?, password_changed_at = ?, updated_at = ?, updated_by = ?,
                                   version = version + 1
                 WHERE id = ?
                """).params(passwordHash, Timestamp.from(now), Timestamp.from(now), actor, id).update();
    }

    /**
     * Stores the first password of an enabled account that has none; {@code false} when it has one by now (a
     * parallel request or an admin was faster) or is disabled, and nothing changes.
     */
    public boolean setInitialPassword(UUID id, String passwordHash, Instant now, String actor) {
        return jdbc.sql("""
                UPDATE account SET password_hash = ?, password_changed_at = ?, updated_at = ?, updated_by = ?,
                                   version = version + 1
                 WHERE id = ? AND password_hash IS NULL AND enabled
                """).params(passwordHash, Timestamp.from(now), Timestamp.from(now), actor, id).update() == 1;
    }

    /** Enables the account and clears any disable reason, REVOKED included (callers decide whether they may). */
    public void enableClearingReason(UUID id, Instant now, String actor) {
        jdbc.sql("""
                UPDATE account SET enabled = true, disabled_reason = NULL, disabled_at = NULL, updated_at = ?,
                                   updated_by = ?, version = version + 1
                 WHERE id = ?
                """).params(Timestamp.from(now), actor, id).update();
    }

    /** Profile fields; {@code null} keeps the stored value. Identifiers are stored in lower case. */
    public void updateProfile(UUID id, String username, String email, String displayName, Instant now, String actor) {
        jdbc.sql("""
                UPDATE account SET username = coalesce(:username, username), email = coalesce(:email, email),
                                   display_name = coalesce(:displayName, display_name), updated_at = :now,
                                   updated_by = :actor, version = version + 1
                 WHERE id = :id
                """)
                .param("username", normalise(username))
                .param("email", normalise(email))
                .param("displayName", displayName)
                .param("now", Timestamp.from(now))
                .param("actor", actor)
                .param("id", id)
                .update();
    }

    public void replaceRoles(UUID id, Set<String> roles) {
        jdbc.sql("DELETE FROM account_role WHERE account_id = ? AND NOT (role = ANY(?))")
                .params(id, roles.toArray(String[]::new)).update();
        for (String role : roles) {
            addRole(id, role);
        }
    }

    /** Disables the account with a reason ({@code REVOKED}, {@code DEMO_OFF}). */
    public void disable(UUID id, String reason, Instant now, String actor) {
        jdbc.sql("""
                UPDATE account SET enabled = false, disabled_reason = ?, disabled_at = ?, updated_at = ?,
                                   updated_by = ?, version = version + 1
                 WHERE id = ?
                """).params(reason, Timestamp.from(now), Timestamp.from(now), actor, id).update();
    }

    public void delete(UUID id) {
        jdbc.sql("DELETE FROM account WHERE id = ?").param(id).update();
    }

    /** Removes the Google binding; {@code true} when there was one. */
    public boolean unbindGoogle(UUID id) {
        return jdbc.sql("DELETE FROM federated_identity WHERE account_id = ? AND provider = 'google'")
                .param(id).update() > 0;
    }

    public boolean usernameTaken(String username, UUID except) {
        return jdbc.sql("SELECT count(*) FROM account WHERE username = ? AND id <> ?")
                .params(normalise(username), except).query(Long.class).single() > 0;
    }

    public boolean emailTaken(String email, UUID except) {
        return jdbc.sql("SELECT count(*) FROM account WHERE email = ? AND id <> ?")
                .params(normalise(email), except).query(Long.class).single() > 0;
    }

    /** Usable admins (D12) other than {@code id}. */
    public long countUsableAdminsExcept(UUID id) {
        return jdbc.sql("SELECT count(*) FROM account a WHERE a.id <> ? AND " + USABLE_ADMIN)
                .param(id).query(Long.class).single();
    }

    public boolean hasGoogleBinding(UUID id) {
        return jdbc.sql("SELECT count(*) FROM federated_identity WHERE account_id = ?")
                .param(id).query(Long.class).single() > 0;
    }

    /**
     * Ends every sign-in of the account: its Spring Sessions and OAuth2 authorizations (principal name = account
     * id, D9). Returns the deleted sessions and authorizations.
     */
    public int[] deleteSignIns(UUID id) {
        int sessions = jdbc.sql("DELETE FROM spring_session WHERE principal_name = ?").param(id.toString()).update();
        int authorizations = jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = ?")
                .param(id.toString()).update();
        return new int[] {sessions, authorizations};
    }

    public void recordLogin(UUID id, Instant now) {
        jdbc.sql("UPDATE account SET last_login_at = ? WHERE id = ?").params(Timestamp.from(now), id).update();
    }

    public long countUsableAdmins() {
        return jdbc.sql("SELECT count(*) FROM account a WHERE " + USABLE_ADMIN).query(Long.class).single();
    }

    /**
     * Serialises every change that can remove the last usable admin (revoke, delete, role removal, unbind)
     * and the bootstrap. Holds until the end of the current transaction.
     */
    public void lockAdminInvariant() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('auth.last-admin'))").query().singleRow();
    }

    static String normalise(String identifier) {
        return SignInIdentifier.normalise(identifier);
    }

    private static Account map(ResultSet rs, int row) throws SQLException {
        Timestamp lastLogin = rs.getTimestamp("last_login_at");
        Array roles = rs.getArray("roles");
        Set<String> roleSet = roles == null ? Set.of()
                : Arrays.stream((Object[]) roles.getArray()).map(String::valueOf).collect(Collectors.toUnmodifiableSet());
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("enabled"),
                rs.getString("disabled_reason"),
                rs.getBoolean("demo"),
                roleSet,
                lastLogin == null ? null : lastLogin.toInstant());
    }

    /** Data of a new account; {@code passwordHash} is already encoded or {@code null}. */
    public record NewAccount(UUID id, String username, String email, String displayName, Set<String> roles,
            String passwordHash, boolean demo) {
    }
}
