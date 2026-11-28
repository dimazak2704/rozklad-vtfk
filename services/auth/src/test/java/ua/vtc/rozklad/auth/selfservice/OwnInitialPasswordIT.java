package ua.vtc.rozklad.auth.selfservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.session.SessionAttributes;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.GoogleMock;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * A Google-only account sets its first password over real HTTP (CONTRACTS §0.1): only with a fresh Google sign-in
 * of the same account in this browser's session, never with the access token alone. The same configuration as
 * {@code GoogleLoginIT}, so both share one context.
 */
@AuthIntegrationTest
@Import(GoogleMock.class)
@TestPropertySource(properties = {
        "app.auth.google.client-id=" + GoogleMock.CLIENT_ID,
        "app.auth.google.client-secret=" + GoogleMock.CLIENT_SECRET,
        "app.auth.google.mock=true",
        "app.auth.google.hosted-domain=vtc.vn.ua"})
class OwnInitialPasswordIT {

    private static final String DOMAIN = "vtc.vn.ua";
    private static final String STATUS = "/auth/api/account/password";
    private static final String INITIAL = "/auth/api/account/initial-password";
    private static final String NEW = "Brand-New-Password-7";
    private static final String RETURN = "http://localhost:5180/me?password=set";
    /** Own networks per test: the failures here never meet the lockout keys of other ITs. */
    private static final AtomicInteger NETWORK = new AtomicInteger(10);

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private GoogleMock.Users google;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessions;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void rightAfterAGoogleSignInTheFirstPasswordIsSetAndBothWaysInWork() {
        Person teacher = person();
        String ip = ip();
        Browser here = browsers.open(ip);
        String token = signInWithGoogle(here, teacher);
        Browser phone = browsers.open(ip);
        signInWithGoogle(phone, teacher);

        JsonNode info = status(here, token);
        assertThat(info.path("status").asString()).isEqualTo("NO_PASSWORD");
        assertThat(info.path("reauthRequired").asBoolean()).isFalse();

        HttpResponse<String> set = setInitial(here, token, NEW);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(204);
        assertThat(Browser.setCookies(set)).noneMatch(cookie -> cookie.startsWith("RZ_AUTH="));

        assertThat(accountRepository.findById(teacher.id()).orElseThrow().hasPassword()).isTrue();
        assertThat(status(here, token).path("status").asString()).isEqualTo("CHANGEABLE");
        // this browser stays signed in, the other device has to sign in again
        assertThat(silentRenew(here)).contains("code=");
        assertThat(silentRenew(phone)).contains("error=login_required");
        // the password works with the e-mail as the login, and Google still works
        OidcClient.signIn(browsers.open(ip), teacher.email(), NEW);
        signInWithGoogle(browsers.open(ip), teacher);
        assertThat(methods(teacher.id())).contains("PASSWORD_SET:SUCCESS");

        // a second «first» password is refused: the change path with the current password applies now
        HttpResponse<String> again = setInitial(here, token, "Another-Password-8");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("password-already-set");
    }

    @Test
    void aStaleGoogleSignInNeedsTheReauthenticationWhichReturnsToTheApp() {
        Person teacher = person();
        Browser browser = browsers.open(ip());
        OidcClient.Tokens beforeReauth = googleTokens(browser, teacher);
        String token = beforeReauth.accessToken();
        age(teacher.id(), OwnPasswordService.REAUTH_WINDOW.toMinutes() + 1);

        assertThat(status(browser, token).path("reauthRequired").asBoolean()).isTrue();
        HttpResponse<String> stale = setInitial(browser, token, NEW);
        assertThat(stale.statusCode()).isEqualTo(403);
        assertThat(code(stale)).isEqualTo("reauth-required");
        assertThat(OidcClient.json(stale.body()).path("type").asString())
                .isEqualTo("urn:rozklad:auth:reauth-required");

        HttpResponse<String> start = browser.get("/auth/oauth2/authorization/google?reauth_return="
                + URLEncoder.encode(RETURN, StandardCharsets.UTF_8));
        assertThat(start.statusCode()).isEqualTo(302);
        Map<String, String> toGoogle = Browser.query(Browser.location(start));
        assertThat(toGoogle).containsEntry("prompt", "select_account").containsEntry("max_age", "0")
                .containsEntry("hd", DOMAIN).doesNotContainKey("reauth_return");
        google.nextUser(teacher.subject(), teacher.email(), true, DOMAIN);
        HttpResponse<String> back = callback(browser, google.authorize(atGoogle(Browser.location(start))));
        assertThat(Browser.location(back)).isEqualTo(RETURN);

        assertThat(status(browser, token).path("reauthRequired").asBoolean()).isFalse();
        assertThat(setInitial(browser, token, NEW).statusCode()).isEqualTo(204);
        assertThat(accountRepository.findById(teacher.id()).orElseThrow().hasPassword()).isTrue();

        // the re-authentication gave the session a new id: the ID token from before carries the old sid, so a
        // logout with it is refused and the session lives on (the SPA therefore renews its tokens on return)
        HttpResponse<String> staleLogout = browser.get(logoutUrl(beforeReauth.idToken()), "Accept", "text/html");
        assertThat(staleLogout.statusCode()).isEqualTo(400);
        assertThat(signInSessions(teacher.id())).isEqualTo(1);
        // the silently renewed ID token carries the current sid: «Вийти» ends the session
        OidcClient.Tokens renewed = renew(browser);
        assertThat(renewed.idClaims().path("sid").asString())
                .isNotEqualTo(beforeReauth.idClaims().path("sid").asString());
        HttpResponse<String> logout = browser.get(logoutUrl(renewed.idToken()), "Accept", "text/html");
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(Browser.location(logout)).startsWith("http://localhost:5180/");
        assertThat(signInSessions(teacher.id())).isZero();
        assertThat(silentRenew(browser)).contains("error=login_required");
    }

    @Test
    void aTokenAloneOrWithAnotherAccountsSessionSetsNothing() {
        Person owner = person();
        Person other = person();
        String ip = ip();
        Browser ownersBrowser = browsers.open(ip);
        String ownersToken = signInWithGoogle(ownersBrowser, owner);
        Browser attackersBrowser = browsers.open(ip);
        signInWithGoogle(attackersBrowser, other);

        // a stolen token in a browser whose fresh Google session is of another account
        HttpResponse<String> foreign = setInitial(attackersBrowser, ownersToken, NEW);
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(code(foreign)).isEqualTo("reauth-required");
        assertThat(status(attackersBrowser, ownersToken).path("reauthRequired").asBoolean()).isTrue();

        // the token without any sign-in cookie
        Browser bare = browsers.open(ip);
        HttpResponse<String> alone = setInitial(bare, ownersToken, NEW);
        assertThat(alone.statusCode()).isEqualTo(403);
        assertThat(code(alone)).isEqualTo("reauth-required");

        // a reauthentication link to another site is a plain sign-in that returns nowhere else
        HttpResponse<String> start = ownersBrowser.get("/auth/oauth2/authorization/google?reauth_return="
                + URLEncoder.encode("https://evil.example/steal", StandardCharsets.UTF_8));
        assertThat(Browser.query(Browser.location(start))).doesNotContainKeys("prompt", "max_age");
        google.nextUser(owner.subject(), owner.email(), true, DOMAIN);
        assertThat(Browser.location(callback(ownersBrowser, google.authorize(Browser.location(start)))))
                .doesNotContain("evil.example");

        assertThat(accountRepository.findById(owner.id()).orElseThrow().hasPassword()).isFalse();
        assertThat(methods(owner.id())).containsOnly("PASSWORD_SET:BAD_CREDENTIALS");
    }

    @Test
    void anAccountWithAPasswordUsesTheChangePath() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open(ip());
        String token = OidcClient.signIn(browser, teacher.username(), TestAccounts.PASSWORD).accessToken();

        JsonNode info = status(browser, token);
        assertThat(info.path("status").asString()).isEqualTo("CHANGEABLE");
        assertThat(info.path("reauthRequired").asBoolean()).isFalse();
        HttpResponse<String> refused = setInitial(browser, token, NEW);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(code(refused)).isEqualTo("password-already-set");
    }

    @Test
    void attemptsWithoutTheProofLockLikeSignInsAndThePolicyApplies() {
        Person teacher = person();
        String ip = ip();
        Browser browser = browsers.open(ip);
        String token = signInWithGoogle(browser, teacher);

        HttpResponse<String> weak = setInitial(browser, token, "short-1");
        assertThat(weak.statusCode()).isEqualTo(400);
        assertThat(code(weak)).isEqualTo("password-rejected");
        assertThat(OidcClient.json(weak.body()).path("reason").asString()).isEqualTo("too-short");

        age(teacher.id(), OwnPasswordService.REAUTH_WINDOW.toMinutes() + 1);
        // app.auth.lockout.max-failures=3 in the ITs
        for (int i = 0; i < 3; i++) {
            assertThat(setInitial(browser, token, NEW).statusCode()).isEqualTo(403);
        }
        HttpResponse<String> locked = setInitial(browser, token, NEW);
        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(code(locked)).isEqualTo("too-many-attempts");
        // the same lock covers the sign-in page for this identifier from this network
        assertThat(Browser.location(browsers.open(ip).signIn(teacher.email(), NEW)))
                .isEqualTo("/auth/login?error=throttled");
        assertThat(accountRepository.findById(teacher.id()).orElseThrow().hasPassword()).isFalse();
    }

    private record Person(UUID id, String email, String subject) {
    }

    private Person person() {
        TestAccounts.Created created = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        return new Person(created.id(), created.email(), "google-" + UUID.randomUUID());
    }

    /** The SPA's sign-in through Google: returns the access token; the session gets the Google mark. */
    private String signInWithGoogle(Browser browser, Person person) {
        return googleTokens(browser, person).accessToken();
    }

    private OidcClient.Tokens googleTokens(Browser browser, Person person) {
        String verifier = OidcClient.verifier();
        browser.get(OidcClient.authorizeUrl(verifier, "g", null), "Accept", "text/html");
        google.nextUser(person.subject(), person.email(), true, DOMAIN);
        HttpResponse<String> start = browser.get("/auth/oauth2/authorization/google");
        HttpResponse<String> spa = browser.follow(callback(browser, google.authorize(Browser.location(start))));
        Map<String, String> params = Browser.query(Browser.location(spa));
        assertThat(Browser.location(spa)).startsWith(OidcClient.WEB_CALLBACK);
        return OidcClient.exchange(browser, params.get("code"), verifier);
    }

    /** The SPA's silent renew ({@code prompt=none}) in the hidden iframe, then the code exchange. */
    private static OidcClient.Tokens renew(Browser browser) {
        String verifier = OidcClient.verifier();
        String back = Browser.location(browser.get(OidcClient.authorizeUrl(verifier, "r", "prompt=none"),
                "Accept", "text/html"));
        assertThat(back).startsWith(OidcClient.WEB_CALLBACK).contains("code=");
        return OidcClient.exchange(browser, Browser.query(back).get("code"), verifier);
    }

    private static String logoutUrl(String idToken) {
        return UriComponentsBuilder.fromPath("/auth/connect/logout")
                .queryParam("id_token_hint", idToken)
                .queryParam("post_logout_redirect_uri", "http://localhost:5180/")
                .build().encode().toUriString();
    }

    private long signInSessions(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM spring_session WHERE principal_name = ?")
                .param(accountId.toString()).query(Long.class).single();
    }

    /**
     * Google shows its account chooser for {@code prompt=select_account}; the mock would show its own login page
     * instead, so the test passes it the request without the prompt (the person picks the same account).
     */
    private static String atGoogle(String authorizationUrl) {
        return UriComponentsBuilder.fromUriString(authorizationUrl).replaceQueryParam("prompt")
                .replaceQueryParam("max_age").build(true).toUriString();
    }

    /** The browser comes back from Google to the issuer's address (nginx passes it in X-Forwarded-*). */
    private static HttpResponse<String> callback(Browser browser, URI back) {
        HttpResponse<String> response = browser.get(back.getRawPath() + "?" + back.getRawQuery(),
                "X-Forwarded-Host", "localhost:8088", "X-Forwarded-Port", "8088", "X-Forwarded-Proto", "http",
                "Accept", "text/html");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        return response;
    }

    /** Moves the Google sign-in of every session of the account {@code minutes} into the past. */
    private void age(UUID accountId, long minutes) {
        age(sessions, accountId, Instant.now().minusSeconds(minutes * 60).toEpochMilli());
    }

    private static <S extends Session> void age(FindByIndexNameSessionRepository<S> repository, UUID accountId,
            long at) {
        repository.findByPrincipalName(accountId.toString()).values().forEach(session -> {
            assertThat((Object) session.getAttribute(SessionAttributes.GOOGLE_AUTHENTICATED_AT)).isNotNull();
            session.setAttribute(SessionAttributes.GOOGLE_AUTHENTICATED_AT, at);
            repository.save(session);
        });
    }

    private static JsonNode status(Browser browser, String token) {
        HttpResponse<String> response = browser.get(STATUS, "Authorization", "Bearer " + token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return OidcClient.json(response.body());
    }

    private static HttpResponse<String> setInitial(Browser browser, String token, String password) {
        return browser.json("POST", INITIAL, "{\"newPassword\":\"" + password + "\"}",
                "Authorization", "Bearer " + token);
    }

    private static String code(HttpResponse<String> response) {
        return OidcClient.json(response.body()).path("code").asString();
    }

    private static String silentRenew(Browser browser) {
        return Browser.location(browser.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r", "prompt=none"),
                "Accept", "text/html"));
    }

    private java.util.List<String> methods(UUID accountId) {
        return jdbc.sql("""
                SELECT method || ':' || outcome FROM login_attempt
                 WHERE account_id = ? AND method = 'PASSWORD_SET' ORDER BY id
                """).param(accountId).query(String.class).list();
    }

    private static String ip() {
        return "198.18." + NETWORK.incrementAndGet() + ".9";
    }
}
