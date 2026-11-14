package ua.vtc.rozklad.auth.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.test.context.TestPropertySource;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.GoogleMock;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * Google sign-in against an in-process OIDC provider (DESIGN §4.4) with the college domain configured: the
 * allowlist, the domain rule, the binding by {@code sub}, the two messages, and that the result is the same
 * principal and tokens as a password sign-in.
 */
@AuthIntegrationTest
@Import(GoogleMock.class)
@TestPropertySource(properties = {
        "app.auth.google.client-id=" + GoogleMock.CLIENT_ID,
        "app.auth.google.client-secret=" + GoogleMock.CLIENT_SECRET,
        "app.auth.google.mock=true",
        "app.auth.google.hosted-domain=vtc.vn.ua"})
class GoogleLoginIT {

    private static final String DOMAIN = "vtc.vn.ua";
    private static final String NOT_ALLOWED = "Цієї адреси немає у списку доступу. Зверніться до навчальної частини.";

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private GoogleMock.Users google;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theSignInPageOffersGoogleWithTheCollegeHint() {
        String html = browsers.open().get("/auth/login").body();

        assertThat(html).contains("Увійти через Google").contains("href=\"/auth/oauth2/authorization/google\"")
                .contains("Використовуйте корпоративну пошту коледжу.");
        // the password form is shown right under «або», not hidden behind an extra «Увійти з паролем» click
        assertThat(html).contains(">або</div>").contains("autocomplete=\"username\"")
                .contains("autocomplete=\"current-password\"").contains(">Увійти</button>")
                .doesNotContain("<details").doesNotContain("Увійти з паролем");
        assertThat(html.indexOf("Увійти через Google")).isLessThan(html.indexOf(">або</div>"));
        assertThat(html.indexOf(">або</div>")).isLessThan(html.indexOf("autocomplete=\"username\""));
        assertThat(browsers.open().get("/auth/login?error=throttled").body())
                .contains("Забагато спроб. Спробуйте пізніше або, якщо ваш вхід прив")
                .contains("язано до Google, увійдіть через Google.");
    }

    @Test
    void aWrongPasswordNextToGoogleMarksTheAlwaysVisibleForm() {
        String html = browsers.open().get("/auth/login?error").body();

        assertThat(html).contains("Увійти через Google").contains("Неправильний логін або пароль.")
                .contains("aria-invalid=\"true\"").contains("aria-describedby=\"auth-message\"")
                .doesNotContain("<details");
    }

    @Test
    void theAuthorizationRequestToGoogleCarriesTheDomainHintPkceAndTheFixedRedirect() {
        Browser browser = browsers.open();

        String location = Browser.location(browser.get("/auth/oauth2/authorization/google"));

        Map<String, String> query = Browser.query(location);
        assertThat(query).containsEntry("hd", DOMAIN)
                .containsEntry("client_id", GoogleMock.CLIENT_ID)
                .containsEntry("redirect_uri", "http://localhost:8088/auth/login/oauth2/code/google")
                .containsEntry("code_challenge_method", "S256")
                .containsKeys("state", "nonce", "code_challenge");
        assertThat(query.get("scope")).contains("openid").contains("email").contains("profile");
    }

    @Test
    void theFirstSignInBindsBySubAndGivesTheSamePrincipalAndTokensAsAPassword() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        String subject = "google-" + UUID.randomUUID();
        Browser browser = browsers.open();
        String verifier = OidcClient.verifier();
        browser.get(OidcClient.authorizeUrl(verifier, "g1", null), "Accept", "text/html");

        google.nextUser(subject, teacher.email(), true, DOMAIN);
        HttpResponse<String> back = viaGoogle(browser);

        HttpResponse<String> callback = browser.follow(back);
        assertThat(Browser.location(callback)).startsWith(OidcClient.WEB_CALLBACK);
        Map<String, String> params = Browser.query(Browser.location(callback));
        assertThat(params).containsEntry("state", "g1");
        OidcClient.Tokens tokens = OidcClient.exchange(browser, params.get("code"), verifier);

        assertThat(tokens.accessClaims().path("sub").asString()).isEqualTo(teacher.id().toString());
        assertThat(tokens.accessClaims().path("roles").toString()).contains("TEACHER");
        assertThat(tokens.accessClaims().path("preferred_username").asString()).isEqualTo(teacher.email());
        assertThat(tokens.idClaims().path("email").asString()).isEqualTo(teacher.email());
        assertThat(tokens.idClaims().has("sid")).as("sid with the JDBC session registry").isTrue();
        assertThat(jdbc.sql("SELECT subject FROM federated_identity WHERE account_id = ?").param(teacher.id())
                .query(String.class).single()).isEqualTo(subject);
        assertThat(accountRepository.findById(teacher.id()).orElseThrow().lastLoginAt()).isNotNull();
        assertThat(outcomes(teacher.id())).containsExactly("SUCCESS");

        // R3: той самий тип принципала, що й після входу паролем, переживає збереження авторизації в JDBC
        OAuth2Authorization authorization = authorizations.findByToken(tokens.accessToken(),
                OAuth2TokenType.ACCESS_TOKEN);
        Object principal = authorization.getAttribute(Principal.class.getName());
        assertThat(principal).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(((UsernamePasswordAuthenticationToken) principal).getPrincipal()).isInstanceOf(User.class);
        assertThat(authorization.getPrincipalName()).isEqualTo(teacher.id().toString());
        String stored = jdbc.sql("SELECT attributes FROM oauth2_authorization WHERE id = ?")
                .param(authorization.getId()).query(String.class).single();
        assertThat(stored).doesNotContain("OidcUser").doesNotContain(subject);
    }

    @Test
    void aBoundAccountSignsInBySubEvenAfterTheAddressChangedAtGoogle() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        String subject = "google-" + UUID.randomUUID();
        google.nextUser(subject, teacher.email(), true, DOMAIN);
        assertSignedIn(viaGoogle(browsers.open()));

        google.nextUser(subject, TestAccounts.email(DOMAIN), true, DOMAIN);

        assertSignedIn(viaGoogle(browsers.open()));
    }

    @Test
    void anAddressThatIsNotInTheListIsRefusedWithTheExactMessage() {
        Browser browser = browsers.open();
        String student = TestAccounts.email(DOMAIN);
        google.nextUser("google-" + UUID.randomUUID(), student, true, DOMAIN);

        HttpResponse<String> back = viaGoogle(browser);

        assertThat(Browser.location(back)).isEqualTo("/auth/login?error=not_allowed");
        assertThat(browser.get(Browser.location(back)).body()).contains(NOT_ALLOWED);
        assertThat(outcomeOf(student)).isEqualTo("NOT_ALLOWED");
    }

    @Test
    void disabledAndRevokedAccountsAreRefused() {
        TestAccounts.Created revoked = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        TestAccounts.Created boundThenRevoked = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        String subject = "google-" + UUID.randomUUID();
        google.nextUser(subject, boundThenRevoked.email(), true, DOMAIN);
        assertSignedIn(viaGoogle(browsers.open()));
        accountRepository.disable(revoked.id(), "REVOKED", java.time.Instant.now(), "test");
        accountRepository.disable(boundThenRevoked.id(), "REVOKED", java.time.Instant.now(), "test");

        google.nextUser("google-" + UUID.randomUUID(), revoked.email(), true, DOMAIN);
        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");
        google.nextUser(subject, boundThenRevoked.email(), true, DOMAIN);
        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");

        assertThat(outcomes(revoked.id())).containsExactly("NOT_ALLOWED");
        assertThat(outcomes(boundThenRevoked.id())).containsExactly("SUCCESS", "REVOKED");
    }

    @Test
    void aCollegeAddressNeedsTheMatchingHostedDomain() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));

        // приватний Google-акаунт, зареєстрований на адресу коледжу: без hd
        google.nextUser("google-" + UUID.randomUUID(), teacher.email(), true, null);
        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");
        google.nextUser("google-" + UUID.randomUUID(), teacher.email(), true, "other.edu.ua");
        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");

        assertThat(outcomeOf(teacher.email())).isEqualTo("DOMAIN_MISMATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM federated_identity WHERE account_id = ?").param(teacher.id())
                .query(Long.class).single()).isZero();
    }

    @Test
    void anotherDomainIsRefusedWithoutConsumerAccounts() {
        TestAccounts.Created outsider = accounts.grant("TEACHER", TestAccounts.email("gmail.com"));
        google.nextUser("google-" + UUID.randomUUID(), outsider.email(), true, null);

        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");
        assertThat(outcomeOf(outsider.email())).isEqualTo("DOMAIN_MISMATCH");
    }

    @Test
    void anUnverifiedAddressShowsTheGoogleError() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        Browser browser = browsers.open();
        google.nextUser("google-" + UUID.randomUUID(), teacher.email(), false, DOMAIN);

        HttpResponse<String> back = viaGoogle(browser);

        assertThat(Browser.location(back)).isEqualTo("/auth/login?error=google");
        assertThat(browser.get(Browser.location(back)).body())
                .contains("Не вдалося увійти через Google. Спробуйте ще раз.");
        assertThat(outcomeOf(teacher.email())).isEqualTo("EMAIL_UNVERIFIED");
    }

    @Test
    void anAccountBoundToAnotherGoogleAccountIsRefused() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        google.nextUser("google-old-" + UUID.randomUUID(), teacher.email(), true, DOMAIN);
        assertSignedIn(viaGoogle(browsers.open()));

        // Workspace-акаунт перестворили: та сама адреса, новий sub — спершу адміністратор відв'язує старий
        google.nextUser("google-new-" + UUID.randomUUID(), teacher.email(), true, DOMAIN);

        assertThat(Browser.location(viaGoogle(browsers.open()))).isEqualTo("/auth/login?error=not_allowed");
        assertThat(outcomes(teacher.id())).containsExactly("SUCCESS", "IDENTITY_CONFLICT");
    }

    @Test
    void theSavedAuthorizeRequestSurvivesARefusalSoAPasswordStillCompletesIt() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser browser = browsers.open();
        String verifier = OidcClient.verifier();
        browser.get(OidcClient.authorizeUrl(verifier, "after-refusal", null), "Accept", "text/html");
        google.nextUser("google-" + UUID.randomUUID(), TestAccounts.email(DOMAIN), true, DOMAIN);
        assertThat(Browser.location(viaGoogle(browser))).isEqualTo("/auth/login?error=not_allowed");

        HttpResponse<String> callback = browser.follow(browser.signIn(teacher.username(), TestAccounts.PASSWORD));

        assertThat(Browser.location(callback)).startsWith(OidcClient.WEB_CALLBACK).contains("state=after-refusal");
    }

    @Test
    void googleIsNotThrottledByPasswordFailures() {
        TestAccounts.Created teacher = accounts.grant("TEACHER", TestAccounts.email(DOMAIN));
        Browser attacker = browsers.open("203.0.113.77");
        for (int i = 0; i < 4; i++) {
            attacker.signIn(teacher.email(), "wrong-password-" + i);
        }
        assertThat(Browser.location(attacker.signIn(teacher.email(), "wrong-again")))
                .isEqualTo("/auth/login?error=throttled");

        Browser sameNetwork = browsers.open("203.0.113.78");
        google.nextUser("google-" + UUID.randomUUID(), teacher.email(), true, DOMAIN);

        assertSignedIn(viaGoogle(sameNetwork));
    }

    @Test
    void aCancelledSignInAtGoogleShowsTheGoogleError() {
        Browser browser = browsers.open();
        String location = Browser.location(browser.get("/auth/oauth2/authorization/google"));
        String state = Browser.query(location).get("state");

        HttpResponse<String> back = browser.get("/auth/login/oauth2/code/google?error=access_denied&state=" + state,
                forwarded());

        assertThat(Browser.location(back)).isEqualTo("/auth/login?error=google");
    }

    /** Starts at the button, passes the mock's authorize step and returns the answer of our callback. */
    private HttpResponse<String> viaGoogle(Browser browser) {
        HttpResponse<String> start = browser.get("/auth/oauth2/authorization/google");
        assertThat(start.statusCode()).isEqualTo(302);
        URI back = google.authorize(Browser.location(start));
        assertThat(back.toString()).startsWith("http://localhost:8088/auth/login/oauth2/code/google?");
        // браузер повертається на адресу видавця; nginx передає її в X-Forwarded-* (як у dev-стенді)
        HttpResponse<String> response = browser.get(back.getRawPath() + "?" + back.getRawQuery(), forwarded());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        return response;
    }

    private static String[] forwarded() {
        return new String[] {"X-Forwarded-Host", "localhost:8088", "X-Forwarded-Port", "8088",
                "X-Forwarded-Proto", "http", "Accept", "text/html"};
    }

    private static void assertSignedIn(HttpResponse<String> back) {
        assertThat(Browser.location(back)).doesNotContain("error");
    }

    private java.util.List<String> outcomes(UUID accountId) {
        return jdbc.sql("""
                SELECT outcome FROM login_attempt WHERE account_id = ? AND method = 'GOOGLE' ORDER BY id
                """).param(accountId).query(String.class).list();
    }

    private String outcomeOf(String email) {
        return jdbc.sql("""
                SELECT outcome FROM login_attempt WHERE identifier_hash = ? AND method = 'GOOGLE'
                 ORDER BY id DESC LIMIT 1
                """).param(ua.vtc.rozklad.auth.login.LoginAttempts.hash(email)).query(String.class).single();
    }
}
