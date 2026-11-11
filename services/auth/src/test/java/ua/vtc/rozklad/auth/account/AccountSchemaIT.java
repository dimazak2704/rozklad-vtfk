package ua.vtc.rozklad.auth.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;

/**
 * Flyway schema and its invariants, the bootstrap admin of the test configuration and the readiness
 * component {@code adminCredential} on the management port.
 */
@AuthIntegrationTest
class AccountSchemaIT {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Environment environment;

    @Autowired
    private Browsers browsers;

    @Test
    void allMigrationsAreApplied() {
        List<String> versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();
        assertThat(versions).containsExactly("1.001", "1.002", "1.100", "1.101");
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND "
                + "table_name IN ('account', 'account_role', 'federated_identity', 'login_attempt', "
                + "'oauth2_authorization', 'oauth2_registered_client', 'oauth2_authorization_consent', "
                + "'spring_session', 'spring_session_attributes')").query(Long.class).single()).isEqualTo(9);
    }

    @Test
    void usernamesAreLowerCaseAndEmailsUniqueCaseInsensitively() {
        assertThatThrownBy(() -> insert("Admin2", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, "Mixed@Case.local")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, null)).isInstanceOf(DataIntegrityViolationException.class);
        insert("unique.user", "unique@case.local");
        assertThatThrownBy(() -> insert("unique.user", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(null, "unique@case.local")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRevocationReasonNeedsADisabledAccount() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO account (id, username, display_name, enabled, disabled_reason, created_at, created_by,
                                     updated_at, updated_by)
                VALUES (?, 'reason.check', 'x', true, 'REVOKED', now(), 't', now(), 't')
                """).param(UUID.randomUUID()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theBootstrapCreatedOneUsableAdminWhoCanSignIn() {
        assertThat(jdbc.sql("SELECT created_by FROM account WHERE username = 'root-admin'").query(String.class).single())
                .isEqualTo("bootstrap");

        Browser browser = browsers.open();
        var tokens = OidcClient.signIn(browser, "root-admin", AuthIntegrationTest.BOOTSTRAP_PASSWORD);
        assertThat(tokens.accessClaims().path("roles").toString()).contains("ADMIN");
    }

    @Test
    void readinessReportsTheAdminCredential() {
        String management = "http://127.0.0.1:" + environment.getRequiredProperty("local.management.port");
        var browser = browsers.open();

        var readiness = browser.get(management + "/actuator/health/readiness");
        var liveness = browser.get(management + "/actuator/health/liveness");

        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"UP\"");
        assertThat(liveness.statusCode()).isEqualTo(200);
        // actuator не відповідає на основному порту
        assertThat(browser.get("/auth/actuator/health").statusCode()).isNotEqualTo(200);
    }

    private void insert(String username, String email) {
        jdbc.sql("""
                INSERT INTO account (id, username, email, display_name, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, 'x', now(), 't', now(), 't')
                """).params(UUID.randomUUID(), username, email).update();
    }
}
