package ua.vtc.rozklad.auth.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/** Password sign-in through the form: CSRF, session fixation, one generic error, the journal. */
@AuthIntegrationTest
class PasswordLoginIT {

    private static final String WRONG = "Неправильний логін або пароль.";

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theFormNeedsItsCsrfToken() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        browser.get("/auth/login");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", teacher.username());
        form.put("password", TestAccounts.PASSWORD);

        HttpResponse<String> response = browser.postForm("/auth/login", form);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(outcomes(teacher.id())).isEmpty();
    }

    @Test
    void signingInChangesTheSessionIdAndContinuesTheSavedAuthorizeRequest() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        browser.get(OidcClient.authorizeUrl(OidcClient.verifier(), "s", null), "Accept", "text/html");
        browser.get("/auth/login");
        String before = browser.cookie("RZ_AUTH").map(HttpCookie::getValue).orElseThrow();

        HttpResponse<String> response = browser.signIn(teacher.username(), TestAccounts.PASSWORD);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(Browser.location(response)).contains("/auth/oauth2/authorize").contains("client_id=rozklad-web");
        assertThat(browser.cookie("RZ_AUTH").map(HttpCookie::getValue).orElseThrow()).isNotEqualTo(before);
        assertThat(outcomes(teacher.id())).containsExactly("SUCCESS");
        assertThat(jdbc.sql("SELECT last_login_at IS NOT NULL FROM account WHERE id = ?").param(teacher.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void anEmailSignsInToo() {
        TestAccounts.Created teacher = accounts.teacher();

        HttpResponse<String> response = browsers.open().signIn(teacher.email().toUpperCase(), TestAccounts.PASSWORD);

        assertThat(Browser.location(response)).doesNotContain("error");
    }

    @Test
    void withoutASavedRequestTheSiteFinishesTheSignIn() {
        TestAccounts.Created teacher = accounts.teacher();

        HttpResponse<String> response = browsers.open().signIn(teacher.username(), TestAccounts.PASSWORD);

        assertThat(Browser.location(response)).isEqualTo("http://localhost:8088/login");
    }

    @Test
    void aWrongPasswordAndAnUnknownUserLookTheSame() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open("203.0.113.10");

        HttpResponse<String> wrong = browser.signIn(teacher.username(), "not-the-password-123");
        HttpResponse<String> unknown = browser.signIn("nobody-" + UUID.randomUUID().toString().substring(0, 8),
                "not-the-password-123");

        // контекстно-відносна адреса: без подвоєння /auth/auth
        assertThat(Browser.location(wrong)).isEqualTo("/auth/login?error");
        assertThat(Browser.location(unknown)).isEqualTo("/auth/login?error");
        assertThat(browser.get("/auth/login?error").body()).contains(WRONG);
        assertThat(outcomes(teacher.id())).containsExactly("BAD_CREDENTIALS");
    }

    @Test
    void disabledAndRevokedAccountsGetTheSameGenericError() {
        TestAccounts.Created disabled = accounts.teacher();
        TestAccounts.Created revoked = accounts.teacher();
        jdbc.sql("UPDATE account SET enabled = false, disabled_reason = 'DEMO_OFF' WHERE id = ?")
                .param(disabled.id()).update();
        jdbc.sql("UPDATE account SET enabled = false, disabled_reason = 'REVOKED' WHERE id = ?")
                .param(revoked.id()).update();
        Browser browser = browsers.open("203.0.113.11");

        assertThat(Browser.location(browser.signIn(disabled.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error");
        assertThat(Browser.location(browser.signIn(revoked.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error");
        assertThat(outcomes(disabled.id())).containsExactly("DISABLED");
        assertThat(outcomes(revoked.id())).containsExactly("REVOKED");
    }

    @Test
    void anAccountWithoutAPasswordCannotSignInWithAnEmptyOne() {
        TestAccounts.Created teacher = accounts.teacher();
        jdbc.sql("UPDATE account SET password_hash = NULL WHERE id = ?").param(teacher.id()).update();

        HttpResponse<String> response = browsers.open("203.0.113.12").signIn(teacher.username(), "");

        assertThat(Browser.location(response)).isEqualTo("/auth/login?error");
    }

    @Test
    void theJournalNeverHoldsTheRawIdentifier() {
        TestAccounts.Created teacher = accounts.teacher();
        browsers.open("203.0.113.13").signIn(teacher.username(), "wrong-password-xyz");

        List<String> hashes = jdbc.sql("SELECT encode(identifier_hash, 'hex') FROM login_attempt WHERE account_id = ?")
                .param(teacher.id()).query(String.class).list();
        assertThat(hashes).hasSize(1).allSatisfy(hash -> assertThat(hash).hasSize(64).doesNotContain("user"));
        assertThat(jdbc.sql("SELECT host(ip) || ' ' || ip_prefix::text FROM login_attempt WHERE account_id = ?")
                .param(teacher.id()).query(String.class).single()).isEqualTo("203.0.113.13 203.0.113.0/24");
    }

    private List<String> outcomes(UUID accountId) {
        return jdbc.sql("SELECT outcome FROM login_attempt WHERE account_id = ? ORDER BY id").param(accountId)
                .query(String.class).list();
    }
}
