package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.util.UriComponentsBuilder;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * RP-initiated logout with {@code SpringSessionBackedSessionRegistry}: the session ends, the authorization
 * that holds the ID token is deleted, an expired ID token is still accepted, other devices keep theirs.
 */
@AuthIntegrationTest
class LogoutIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void logoutEndsTheSessionDeletesItsAuthorizationAndReturnsToTheSite() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser laptop = browsers.open();
        Browser phone = browsers.open();
        OidcClient.Tokens tokens = OidcClient.signIn(laptop, teacher.username(), TestAccounts.PASSWORD);
        OidcClient.signIn(phone, teacher.username(), TestAccounts.PASSWORD);
        assertThat(tokens.idClaims().path("sid").asString()).isNotBlank();
        assertThat(authorizations(teacher.id())).isEqualTo(2);
        assertThat(sessions(teacher.id())).isEqualTo(2);

        HttpResponse<String> logout = laptop.get(logoutUrl(tokens.idToken(), "st"), "Accept", "text/html");

        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(Browser.location(logout)).isEqualTo("http://localhost:5180/?state=st");
        assertThat(authorizations(teacher.id())).isEqualTo(1);
        assertThat(sessions(teacher.id())).isEqualTo(1);
        // ноутбук без сесії: тихе оновлення — login_required; телефон — далі в системі
        assertThat(silentRenew(laptop)).contains("error=login_required");
        assertThat(silentRenew(phone)).contains("code=");
    }

    @Test
    void anExpiredIdTokenIsStillAcceptedForLogout() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        OidcClient.Tokens tokens = OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD);
        jdbc.sql("UPDATE oauth2_authorization SET oidc_id_token_issued_at = now() - interval '2 hours', "
                + "oidc_id_token_expires_at = now() - interval '1 hour' WHERE principal_name = ?")
                .param(teacher.id().toString()).update();

        HttpResponse<String> logout = browser.get(logoutUrl(tokens.idToken(), "x"), "Accept", "text/html");

        assertThat(Browser.location(logout)).startsWith("http://localhost:5180/");
        assertThat(authorizations(teacher.id())).isZero();
    }

    @Test
    void anUnregisteredPostLogoutRedirectIsRefused() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        OidcClient.Tokens tokens = OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD);
        String url = UriComponentsBuilder.fromPath("/auth/connect/logout")
                .queryParam("id_token_hint", tokens.idToken())
                .queryParam("post_logout_redirect_uri", "https://evil.example/")
                .build().encode().toUriString();

        HttpResponse<String> logout = browser.get(url, "Accept", "text/html");

        assertThat(logout.statusCode()).isEqualTo(400);
        assertThat(authorizations(teacher.id())).isEqualTo(1);
    }

    private static String logoutUrl(String idToken, String state) {
        return UriComponentsBuilder.fromPath("/auth/connect/logout")
                .queryParam("id_token_hint", idToken)
                .queryParam("post_logout_redirect_uri", "http://localhost:5180/")
                .queryParam("state", state)
                .build().encode().toUriString();
    }

    private static String silentRenew(Browser browser) {
        return Browser.location(browser.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r", "prompt=none"),
                "Accept", "text/html"));
    }

    private long authorizations(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?")
                .param(accountId.toString()).query(Long.class).single();
    }

    private long sessions(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                .param(accountId.toString()).query(Long.class).single();
    }
}
