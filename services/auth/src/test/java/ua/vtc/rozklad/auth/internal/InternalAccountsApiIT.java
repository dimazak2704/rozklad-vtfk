package ua.vtc.rozklad.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browser;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * The write side of the internal accounts API as core uses it (DESIGN §1.3): who may call it, idempotent
 * create, the conflicts and password reasons core maps to Ukrainian texts, sticky revocation and what revoke
 * and a new password do to sessions and authorizations.
 */
@AuthIntegrationTest
class InternalAccountsApiIT {

    private static final String STRONG = "Lviv-Rivne-Kharkiv-2026";

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private JdbcClient jdbc;

    private Browser core;
    private String bearer;

    @BeforeEach
    void setUp() {
        core = browsers.open();
        bearer = "Bearer " + OidcClient.serviceToken(core, "rozklad-core", AuthIntegrationTest.CORE_SECRET,
                "accounts.manage");
    }

    @Test
    void onlyCoreMayWrite() {
        UUID id = UUID.randomUUID();
        String body = newAccount("nobody" + suffix(), null, STRONG);
        String notification = OidcClient.serviceToken(core, "rozklad-notification",
                AuthIntegrationTest.NOTIFICATION_SECRET, null);
        // core без scope accounts.manage — теж ні
        String coreWithoutScope = OidcClient.serviceToken(core, "rozklad-core", AuthIntegrationTest.CORE_SECRET, null);

        assertThat(core.json("PUT", path(id), body).statusCode()).isEqualTo(401);
        assertThat(core.json("PUT", path(id), body, "Authorization", "Bearer " + notification).statusCode())
                .isEqualTo(403);
        assertThat(core.json("PUT", path(id), body, "Authorization", "Bearer " + coreWithoutScope).statusCode())
                .isEqualTo(403);
        assertThat(count("SELECT count(*) FROM account WHERE id = ?", id)).isZero();
    }

    @Test
    void theRequestIdOfCoreIsKeptAndARejectedCallIsCorrelatedToo() {
        UUID id = UUID.randomUUID();
        String body = newAccount("nobody" + suffix(), null, STRONG);

        HttpResponse<String> rejected = core.json("PUT", path(id), body, "X-Request-Id", "req-core-42");
        HttpResponse<String> forged = core.json("PUT", path(id), body, "X-Request-Id", "bad id with spaces");

        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.headers().firstValue("X-Request-Id")).hasValue("req-core-42");
        assertThat(forged.headers().firstValue("X-Request-Id")).get().asString().hasSize(36);
    }

    @Test
    void createIsIdempotentAndTheAccountCanSignIn() {
        UUID id = UUID.randomUUID();
        String username = "teacher" + suffix();
        String body = newAccount(username.toUpperCase(), null, STRONG);

        HttpResponse<String> created = put(path(id), body, "admin");
        HttpResponse<String> again = put(path(id), body, "admin");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(again.statusCode()).isEqualTo(200);
        JsonNode account = OidcClient.json(created.body());
        assertThat(account.path("id").asString()).isEqualTo(id.toString());
        assertThat(account.path("username").asString()).isEqualTo(username);
        assertThat(account.path("roles").valueStream().map(JsonNode::asString).toList()).containsExactly("TEACHER");
        assertThat(account.path("enabled").asBoolean()).isTrue();
        assertThat(account.path("hasPassword").asBoolean()).isTrue();
        assertThat(jdbc.sql("SELECT created_by FROM account WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("admin");
        assertThat(OidcClient.signIn(browsers.open(), username, STRONG).accessClaims().path("sub").asString())
                .isEqualTo(id.toString());
    }

    @Test
    void anAccessGrantHasAnEmailAndNoPassword() {
        UUID id = UUID.randomUUID();
        String email = "Grant" + suffix() + "@College.Example";

        HttpResponse<String> created = put(path(id), """
                {"email":"%s","displayName":"Нова Викладачка","roles":["TEACHER"]}""".formatted(email), null);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode account = OidcClient.json(created.body());
        assertThat(account.path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(account.path("username").isNull()).isTrue();
        assertThat(account.path("hasPassword").asBoolean()).isFalse();
        assertThat(jdbc.sql("SELECT created_by FROM account WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("rozklad-core");
    }

    @Test
    void takenIdentifiersAndBadInputAreRefusedWithStableTypes() {
        TestAccounts.Created existing = accounts.teacher();

        assertProblem(put(path(UUID.randomUUID()), newAccount(existing.username(), null, STRONG), null), 409,
                "username-taken");
        assertProblem(put(path(UUID.randomUUID()), newAccount("fresh" + suffix(), existing.email().toUpperCase(),
                STRONG), null), 409, "email-taken");
        assertProblem(put(path(UUID.randomUUID()), newAccount("9starts-with-digit", null, STRONG), null), 400,
                "validation");
        assertProblem(put(path(UUID.randomUUID()), """
                {"displayName":"Без логіна","roles":["TEACHER"]}""", null), 400, "validation");
        assertProblem(put(path(UUID.randomUUID()), """
                {"username":"x%s","displayName":"Роль","roles":["SERVICE"]}""".formatted(suffix()), null), 400,
                "validation");
        TestAccounts.Created other = accounts.teacher();
        assertProblem(patch(other.id(), "{\"username\":\"%s\"}".formatted(existing.username())), 409,
                "username-taken");
    }

    @Test
    void passwordsAreCheckedByThePolicyAndRejectionsCarryTheReason() {
        String username = "pass" + suffix();

        assertReason(put(path(UUID.randomUUID()), newAccount(username, null, "short-1"), null), "too-short");
        assertReason(put(path(UUID.randomUUID()), newAccount(username, null, username.toUpperCase()), null),
                "same-as-username");
        assertReason(put(path(UUID.randomUUID()), newAccount(username, null, "й".repeat(40)), null), "too-long");
        assertThat(count("SELECT count(*) FROM account WHERE username = ?", username)).isZero();

        TestAccounts.Created teacher = accounts.teacher();
        assertReason(core.json("PUT", path(teacher.id()) + "/password", "{\"password\":\"password1234\"}",
                "Authorization", bearer), "too-common");
        assertReason(core.json("PUT", path(teacher.id()) + "/password",
                "{\"password\":\"%s\"}".formatted(teacher.username()), "Authorization", bearer), "same-as-username");
    }

    @Test
    void settingAPasswordNeverEnablesTheAccount() {
        TestAccounts.Created teacher = accounts.teacher();
        assertThat(post(teacher.id(), "/revoke", null).statusCode()).isEqualTo(200);

        HttpResponse<String> changed = core.json("PUT", path(teacher.id()) + "/password",
                "{\"password\":\"%s\"}".formatted(STRONG), "Authorization", bearer);

        assertThat(changed.statusCode()).isEqualTo(204);
        JsonNode account = OidcClient.json(core.get(path(teacher.id()), "Authorization", bearer).body());
        assertThat(account.path("enabled").asBoolean()).isFalse();
        assertThat(account.path("disabledReason").asString()).isEqualTo("REVOKED");
    }

    @Test
    void aNewPasswordEndsTheSignInsOpenedWithTheOldOne() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser stolen = browsers.open();
        OidcClient.signIn(stolen, teacher.username(), TestAccounts.PASSWORD);
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", teacher.id().toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                teacher.id().toString())).isEqualTo(1);

        HttpResponse<String> changed = core.json("PUT", path(teacher.id()) + "/password",
                "{\"password\":\"%s\"}".formatted(STRONG), "Authorization", bearer, "X-Actor", "admin");

        assertThat(changed.statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", teacher.id().toString()))
                .isZero();
        assertThat(count("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                teacher.id().toString())).isZero();
        HttpResponse<String> silent = stolen.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r1", "prompt=none"),
                "Accept", "text/html");
        assertThat(Browser.location(silent)).contains("error=login_required");
        assertThat(OidcClient.signIn(browsers.open(), teacher.username(), STRONG).accessToken()).isNotBlank();
    }

    @Test
    void revokeEndsSessionsAndAuthorizationsAndOnlyAnExplicitRestoreClearsIt() {
        TestAccounts.Created teacher = accounts.teacher();
        Browser laptop = browsers.open();
        OidcClient.signIn(laptop, teacher.username(), TestAccounts.PASSWORD);
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", teacher.id().toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                teacher.id().toString())).isEqualTo(1);

        HttpResponse<String> revoked = post(teacher.id(), "/revoke", null);

        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(OidcClient.json(revoked.body()).path("disabledReason").asString()).isEqualTo("REVOKED");
        assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name = ?", teacher.id().toString()))
                .isZero();
        assertThat(count("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                teacher.id().toString())).isZero();
        HttpResponse<String> silent = laptop.get(OidcClient.authorizeUrl(OidcClient.verifier(), "r1", "prompt=none"),
                "Accept", "text/html");
        assertThat(Browser.location(silent)).contains("error=login_required");

        assertProblem(post(teacher.id(), "/enable", "{\"clearRevocation\":false}"), 409, "account-revoked");
        assertProblem(post(teacher.id(), "/enable", null), 409, "account-revoked");
        HttpResponse<String> restored = post(teacher.id(), "/enable", "{\"clearRevocation\":true}");
        assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(OidcClient.json(restored.body()).path("enabled").asBoolean()).isTrue();
        assertThat(OidcClient.json(restored.body()).path("disabledReason").isNull()).isTrue();
        assertThat(OidcClient.signIn(browsers.open(), teacher.username(), TestAccounts.PASSWORD).accessToken())
                .isNotBlank();
    }

    @Test
    void patchChangesProfileAndRoles() {
        TestAccounts.Created teacher = accounts.teacher();
        String username = "renamed" + suffix();

        HttpResponse<String> patched = patch(teacher.id(), """
                {"username":"%s","displayName":"Інше Ім'я","roles":["ADMIN","TEACHER"]}""".formatted(username));

        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode account = OidcClient.json(patched.body());
        assertThat(account.path("username").asString()).isEqualTo(username);
        assertThat(account.path("email").asString()).isEqualTo(teacher.email());
        assertThat(account.path("displayName").asString()).isEqualTo("Інше Ім'я");
        assertThat(account.path("roles").valueStream().map(JsonNode::asString).toList())
                .containsExactly("ADMIN", "TEACHER");
        assertThat(jdbc.sql("SELECT updated_by FROM account WHERE id = ?").param(teacher.id()).query(String.class)
                .single()).isEqualTo("admin");
    }

    @Test
    void unknownAccountsAre404AndDeletesAndUnbindsAreIdempotent() {
        UUID unknown = UUID.randomUUID();
        assertProblem(patch(unknown, "{\"displayName\":\"X\"}"), 404, "not-found");
        assertProblem(post(unknown, "/revoke", null), 404, "not-found");
        assertProblem(core.json("DELETE", path(unknown) + "/federated/google", null, "Authorization", bearer), 404,
                "not-found");
        assertThat(core.json("DELETE", path(unknown), null, "Authorization", bearer).statusCode()).isEqualTo(204);

        TestAccounts.Created teacher = accounts.teacher();
        jdbc.sql("""
                INSERT INTO federated_identity (id, account_id, provider, subject, email_at_link, linked_at)
                VALUES (?, ?, 'google', ?, ?, now())
                """).params(UUID.randomUUID(), teacher.id(), "google-" + teacher.id(), teacher.email()).update();
        assertThat(OidcClient.json(core.get(path(teacher.id()), "Authorization", bearer).body())
                .path("google").path("linked").asBoolean()).isTrue();
        assertThat(core.json("DELETE", path(teacher.id()) + "/federated/google", null, "Authorization", bearer)
                .statusCode()).isEqualTo(204);
        assertThat(core.json("DELETE", path(teacher.id()) + "/federated/google", null, "Authorization", bearer)
                .statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM federated_identity WHERE account_id = ?", teacher.id())).isZero();

        assertThat(core.json("DELETE", path(teacher.id()), null, "Authorization", bearer).statusCode()).isEqualTo(204);
        assertThat(core.json("DELETE", path(teacher.id()), null, "Authorization", bearer).statusCode()).isEqualTo(204);
        assertThat(core.get(path(teacher.id()), "Authorization", bearer).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> put(String path, String body, String actor) {
        return actor == null ? core.json("PUT", path, body, "Authorization", bearer)
                : core.json("PUT", path, body, "Authorization", bearer, "X-Actor", actor);
    }

    private HttpResponse<String> patch(UUID id, String body) {
        return core.json("PATCH", path(id), body, "Authorization", bearer, "X-Actor", "admin");
    }

    private HttpResponse<String> post(UUID id, String action, String body) {
        return core.json("POST", path(id) + action, body, "Authorization", bearer, "X-Actor", "admin");
    }

    private long count(String sql, Object param) {
        return jdbc.sql(sql).param(param).query(Long.class).single();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String type) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(OidcClient.json(response.body()).path("type").asString()).isEqualTo("urn:rozklad:auth:" + type);
    }

    private static void assertReason(HttpResponse<String> response, String reason) {
        assertProblem(response, 400, "password-rejected");
        assertThat(OidcClient.json(response.body()).path("reason").asString()).isEqualTo(reason);
    }

    private static String newAccount(String username, String email, String password) {
        return """
                {"username":"%s",%s"displayName":"Тест","roles":["TEACHER"],"password":"%s"}"""
                .formatted(username, email == null ? "" : "\"email\":\"" + email + "\",", password);
    }

    private static String path(UUID id) {
        return "/auth/internal/accounts/" + id;
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
