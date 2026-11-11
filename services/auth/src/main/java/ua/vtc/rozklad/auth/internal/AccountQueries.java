package ua.vtc.rozklad.auth.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read side of the internal accounts API: {@link AccountDto} with roles and the Google binding. */
@Repository
public class AccountQueries {

    private static final String SELECT = """
            SELECT a.id, a.username, a.email, a.display_name, a.enabled, a.disabled_reason, a.demo,
                   a.password_hash IS NOT NULL AS has_password, a.last_login_at, a.created_at, a.updated_at,
                   (SELECT coalesce(array_agg(r.role ORDER BY r.role), '{}') FROM account_role r
                     WHERE r.account_id = a.id) AS roles,
                   f.email_at_link AS google_email, f.linked_at AS google_linked_at
              FROM account a
              LEFT JOIN federated_identity f ON f.account_id = a.id AND f.provider = 'google'
            """;

    private final JdbcClient jdbc;

    public AccountQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountDto> byId(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = ?").param(id).query(AccountQueries::map).optional();
    }

    public Optional<AccountDto> byUsername(String username) {
        return jdbc.sql(SELECT + " WHERE a.username = ?").param(username.strip().toLowerCase(Locale.ROOT))
                .query(AccountQueries::map).optional();
    }

    public Optional<AccountDto> byEmail(String email) {
        return jdbc.sql(SELECT + " WHERE a.email = ?").param(email.strip().toLowerCase(Locale.ROOT))
                .query(AccountQueries::map).optional();
    }

    public List<AccountDto> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE a.id = ANY(?) ORDER BY a.display_name, a.id")
                .param(ids.toArray(UUID[]::new)).query(AccountQueries::map).list();
    }

    /** A page of accounts filtered by text (name, username, e-mail), role and enabled flag. */
    public List<AccountDto> search(String text, String role, Boolean enabled, int page, int size) {
        String pattern = text == null || text.isBlank() ? null
                : "%" + text.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
        return jdbc.sql(SELECT + """
                 WHERE (CAST(:pattern AS text) IS NULL OR lower(a.display_name) LIKE :pattern
                        OR a.username LIKE :pattern OR a.email LIKE :pattern)
                   AND (CAST(:role AS text) IS NULL
                        OR EXISTS (SELECT 1 FROM account_role r WHERE r.account_id = a.id AND r.role = :role))
                   AND (CAST(:enabled AS boolean) IS NULL OR a.enabled = :enabled)
                 ORDER BY a.display_name, a.id
                 LIMIT :size OFFSET :offset
                """)
                .param("pattern", pattern)
                .param("role", role)
                .param("enabled", enabled)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(AccountQueries::map).list();
    }

    private static AccountDto map(ResultSet rs, int row) throws SQLException {
        Array roles = rs.getArray("roles");
        List<String> roleList = roles == null ? List.of()
                : Arrays.stream((Object[]) roles.getArray()).map(String::valueOf).toList();
        Instant linkedAt = instant(rs, "google_linked_at");
        return new AccountDto(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("email"),
                rs.getString("display_name"),
                roleList,
                rs.getBoolean("enabled"),
                rs.getString("disabled_reason"),
                rs.getBoolean("has_password"),
                rs.getBoolean("demo"),
                new AccountDto.Google(linkedAt != null, rs.getString("google_email"), linkedAt),
                instant(rs, "last_login_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
