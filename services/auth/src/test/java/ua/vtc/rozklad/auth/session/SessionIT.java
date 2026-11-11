package ua.vtc.rozklad.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/** Session cookie attributes and the idle and absolute timeouts of the sign-in session (Spring Session JDBC). */
@AuthIntegrationTest
class SessionIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessions;

    @Test
    void theCookieIsHttpOnlyLaxAndScopedToAuth() {
        HttpResponse<String> page = browsers.open().get("/auth/login");

        String cookie = Browser.setCookies(page).stream().filter(c -> c.startsWith("RZ_AUTH=")).findFirst()
                .orElseThrow();
        assertThat(cookie).contains("Path=/auth").contains("HttpOnly").contains("SameSite=Lax")
                .doesNotContain("Secure").doesNotContain("Max-Age").doesNotContain("Expires");
    }

    @Test
    void aSessionOlderThanTheAbsoluteLifetimeEndsEvenIfActive() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD);
        assertThat(silentRenew(browser)).contains("code=");

        update(browser, session -> session.setAttribute(SessionAttributes.AUTHENTICATED_AT,
                Instant.now().minus(Duration.ofHours(25)).toEpochMilli()));

        assertThat(silentRenew(browser)).contains("error=login_required");
    }

    @Test
    void anIdleSessionExpires() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD);

        update(browser, session -> {
            session.setMaxInactiveInterval(Duration.ofMinutes(1));
            session.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(2)));
        });

        assertThat(silentRenew(browser)).contains("error=login_required");
    }

    private <S extends Session> void update(Browser browser, java.util.function.Consumer<Session> change) {
        @SuppressWarnings("unchecked")
        var repository = (FindByIndexNameSessionRepository<S>) sessions;
        String encoded = browser.cookie("RZ_AUTH").orElseThrow().getValue();
        String id = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        S session = repository.findById(id);
        assertThat(session).as("session " + id).isNotNull();
        change.accept(session);
        repository.save(session);
    }

    private static String silentRenew(Browser browser) {
        return Browser.location(browser.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r", "prompt=none"),
                "Accept", "text/html"));
    }
}
