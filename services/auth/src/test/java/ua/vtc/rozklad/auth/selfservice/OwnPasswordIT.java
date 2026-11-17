package ua.vtc.rozklad.auth.selfservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * Changing one's own password over real HTTP with the SPA's token: the session of this browser stays, the other
 * devices can no longer renew, the old password stops working, and wrong guesses lock like sign-ins do.
 */
@AuthIntegrationTest
class OwnPasswordIT {

    private static final String PATH = "/auth/api/account/password";
    private static final String NEW = "Brand-New-Password-7";
    /** Own networks per test: the failures here never meet the lockout keys of other ITs. */
    private static final AtomicInteger NETWORK = new AtomicInteger(10);

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Test
    void theNewPasswordWorksTheOldOneNotAndOnlyThisBrowserStaysSignedIn() {
        TestAccounts.Created teacher = accounts.teacher();
        String ip = ip();
        Browser here = browsers.open(ip);
        String token = OidcClient.signIn(here, teacher.username(), TestAccounts.PASSWORD).accessToken();
        Browser phone = browsers.open(ip);
        OidcClient.signIn(phone, teacher.username(), TestAccounts.PASSWORD);

        String sessionCookie = here.cookie("RZ_AUTH").orElseThrow().getValue();
        HttpResponse<String> status = here.get(PATH, "Authorization", "Bearer " + token);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(OidcClient.json(status.body()).path("status").asString()).isEqualTo("CHANGEABLE");

        HttpResponse<String> changed = change(here, token, TestAccounts.PASSWORD, NEW);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
        // the bearer API authenticates the request only: the sign-in session keeps its id (no session fixation
        // rotation), otherwise the service would see an id not yet in the database and end this session too
        assertThat(Browser.setCookies(status)).noneMatch(cookie -> cookie.startsWith("RZ_AUTH="));
        assertThat(Browser.setCookies(changed)).noneMatch(cookie -> cookie.startsWith("RZ_AUTH="));
        assertThat(here.cookie("RZ_AUTH").orElseThrow().getValue()).isEqualTo(sessionCookie);

        assertThat(silentRenew(here)).contains("code=");
        assertThat(silentRenew(phone)).contains("error=login_required");
        Browser fresh = browsers.open(ip);
        assertThat(Browser.location(fresh.signIn(teacher.username(), TestAccounts.PASSWORD))).contains("error");
        OidcClient.signIn(browsers.open(ip), teacher.username(), NEW);
    }

    @Test
    void aWrongCurrentPasswordIs422AndRepeatedGuessesLockLikeSignIns() {
        TestAccounts.Created teacher = accounts.teacher();
        String ip = ip();
        Browser browser = browsers.open(ip);
        String token = OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD).accessToken();

        HttpResponse<String> wrong = change(browser, token, "Not-My-Password-1", NEW);
        assertThat(wrong.statusCode()).isEqualTo(422);
        JsonNode problem = OidcClient.json(wrong.body());
        assertThat(problem.path("type").asString()).isEqualTo("urn:rozklad:auth:current-password-wrong");
        assertThat(problem.path("code").asString()).isEqualTo("current-password-wrong");

        // app.auth.lockout.max-failures=3 in the ITs: the third failure locks the identifier from this network
        change(browser, token, "Not-My-Password-2", NEW);
        change(browser, token, "Not-My-Password-3", NEW);
        HttpResponse<String> locked = change(browser, token, TestAccounts.PASSWORD, NEW);
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(OidcClient.json(locked.body()).path("code").asString()).isEqualTo("too-many-attempts");
        // the same lock covers the sign-in page from this network
        Browser other = browsers.open(ip);
        assertThat(Browser.location(other.signIn(teacher.username(), TestAccounts.PASSWORD)))
                .isEqualTo("/auth/login?error=throttled");
    }

    @Test
    void aPolicyViolationNamesTheReason() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open(ip());
        String token = OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD).accessToken();

        HttpResponse<String> response = change(browser, token, TestAccounts.PASSWORD, "short-1");

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode problem = OidcClient.json(response.body());
        assertThat(problem.path("code").asString()).isEqualTo("password-rejected");
        assertThat(problem.path("reason").asString()).isEqualTo("too-short");
    }

    @Test
    void withoutATokenOrWithAServiceTokenNothingChanges() {
        Browser browser = browsers.open(ip());
        assertThat(change(browser, null, TestAccounts.PASSWORD, NEW).statusCode()).isEqualTo(401);

        String service = OidcClient.serviceToken(browser, "rozklad-notification",
                AuthIntegrationTest.NOTIFICATION_SECRET, null);
        assertThat(change(browser, service, TestAccounts.PASSWORD, NEW).statusCode()).isEqualTo(403);
    }

    private static HttpResponse<String> change(Browser browser, String token, String current, String next) {
        String body = "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
        return token == null ? browser.json("POST", PATH, body)
                : browser.json("POST", PATH, body, "Authorization", "Bearer " + token);
    }

    private static String silentRenew(Browser browser) {
        return Browser.location(browser.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r", "prompt=none"),
                "Accept", "text/html"));
    }

    private static String ip() {
        return "198.51." + NETWORK.incrementAndGet() + ".7";
    }
}
