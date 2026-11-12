package ua.vtc.rozklad.auth.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.PasswordPolicy;
import ua.vtc.rozklad.auth.config.AuthServerProperties.Bootstrap;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.PropertiesFixture;
import ua.vtc.rozklad.auth.support.TestAccounts;

/** The break-glass repair of an existing account (DESIGN §4.10) also ends the sign-ins of the old password. */
@AuthIntegrationTest
class BootstrapAdminIT {

    private static final String NEW_PASSWORD = "Break-Glass-Repair-2026";

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordPolicy policy;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts testAccounts;

    @Test
    void forcedRepairOfAnExistingAccountEndsItsSessionsAndAuthorizations() {
        TestAccounts.Created teacher = testAccounts.teacher();
        Browser stolen = browsers.open();
        OidcClient.signIn(stolen, teacher.username(), TestAccounts.PASSWORD);
        assertThat(signIns(teacher)).isEqualTo(2);
        BootstrapAdmin repair = new BootstrapAdmin(PropertiesFixture.builder()
                .bootstrap(new Bootstrap(teacher.username(), null, NEW_PASSWORD, true)).build(), accounts, policy,
                passwordEncoder, transactions, Clock.systemUTC());

        transactions.executeWithoutResult(status -> repair.apply());

        assertThat(signIns(teacher)).isZero();
        HttpResponse<String> silent = stolen.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r1", "prompt=none"),
                "Accept", "text/html");
        assertThat(Browser.location(silent)).contains("error=login_required");
        assertThat(OidcClient.signIn(browsers.open(), teacher.username(), NEW_PASSWORD).accessClaims().path("roles")
                .toString()).contains("ADMIN");
    }

    private long signIns(TestAccounts.Created account) {
        String principal = account.id().toString();
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?").param(principal)
                .query(Long.class).single()
                + jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?").param(principal)
                        .query(Long.class).single();
    }
}
