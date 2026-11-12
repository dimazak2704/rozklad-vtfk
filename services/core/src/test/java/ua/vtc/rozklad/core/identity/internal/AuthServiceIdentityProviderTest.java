package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.AccountRevokedException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.EmailTakenException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.LastAdminException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.NewAccount;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.PasswordRejectedException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.UsernameTakenException;

/**
 * The HTTP contract of the internal accounts API as core speaks it (DESIGN §1.3): paths, bodies, the
 * {@code X-Actor} header, and how answers of {@code auth} map to the exceptions of {@link IdentityProvider}.
 */
class AuthServiceIdentityProviderTest {

    private static final String BASE = "http://auth.test/auth/internal";
    private static final UUID ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");

    private MockRestServiceServer server;
    private AuthServiceIdentityProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE).requestInterceptor(AccountsApiConfig.actorHeader());
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new AuthServiceIdentityProvider(builder.build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createPutsTheAccountUnderTheIdCoreChose() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Actor", "rozklad-core"))
                .andExpect(content().json("""
                        {"username":"krentsin","email":null,"displayName":"Кренцін Микола","roles":["TEACHER"],
                         "password":"secret-password-1","enabled":true}"""))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(account(true)));

        assertThat(provider.createUser(newAccount())).isEqualTo(ID);
        server.verify();
    }

    @Test
    void anExistingIdIsAnIdempotentSuccess() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));

        assertThat(provider.createUser(newAccount())).isEqualTo(ID);
    }

    @Test
    void aTimeoutIsRetriedOnceWithTheSameId() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andRespond(withException(new SocketTimeoutException()));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));

        assertThat(provider.createUser(newAccount())).isEqualTo(ID);
        server.verify();
    }

    @Test
    void twoTimeoutsMeanTheSignInServiceIsUnavailable() {
        server.expect(twice(), requestTo(BASE + "/accounts/" + ID))
                .andRespond(withException(new SocketTimeoutException()));

        assertThatThrownBy(() -> provider.createUser(newAccount())).isInstanceOf(IdentityProviderException.class);
        server.verify();
    }

    @Test
    void problemsOfAuthMapToThePortExceptions() {
        expectProblem(HttpMethod.PUT, "/accounts/" + ID, 409, "username-taken", null);
        expectProblem(HttpMethod.PUT, "/accounts/" + ID, 409, "email-taken", null);
        expectProblem(HttpMethod.PUT, "/accounts/" + ID, 400, "password-rejected", "too-common");
        expectProblem(HttpMethod.POST, "/accounts/" + ID + "/revoke", 409, "last-admin", null);
        expectProblem(HttpMethod.PUT, "/accounts/" + ID + "/password", 404, "not-found", null);

        assertThatThrownBy(() -> provider.createUser(newAccount())).isInstanceOf(UsernameTakenException.class)
                .hasMessageContaining("krentsin");
        assertThatThrownBy(() -> provider.createUser(newAccount())).isInstanceOf(EmailTakenException.class);
        assertThatThrownBy(() -> provider.createUser(newAccount()))
                .isInstanceOfSatisfying(PasswordRejectedException.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo("too-common"));
        assertThatThrownBy(() -> provider.revoke(ID)).isInstanceOf(LastAdminException.class);
        assertThatThrownBy(() -> provider.setPassword(ID, "secret-password-1"))
                .isInstanceOf(IdentityProvider.AccountNotFoundException.class);
        server.verify();
    }

    @Test
    void serverErrorsAndRefusedTokensAreUnavailability() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andRespond(withServerError());
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectProblem(HttpMethod.PUT, "/accounts/" + ID, 400, "validation", null);

        assertThatThrownBy(() -> provider.findUser(ID)).isInstanceOf(IdentityProviderException.class);
        assertThatThrownBy(() -> provider.findUser(ID)).isInstanceOf(IdentityProviderException.class);
        assertThatThrownBy(() -> provider.createUser(newAccount())).isInstanceOf(IdentityProviderException.class);
        server.verify();
    }

    @Test
    void lookupsReadTheAccount() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID))
                .andRespond(withSuccess(account(false), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts/" + UUID.fromString("00000000-0000-4000-8000-000000000000")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:rozklad:auth:not-found\",\"status\":404}"));
        server.expect(once(), requestTo(BASE + "/accounts?username=krentsin"))
                .andRespond(withSuccess("[" + account(true) + "]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts?email=k@college.example"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        Account found = provider.findUser(ID).orElseThrow();
        assertThat(found.username()).isEqualTo("krentsin");
        assertThat(found.roles()).containsExactly("TEACHER");
        assertThat(found.revoked()).isTrue();
        assertThat(found.enabled()).isFalse();
        assertThat(found.googleLinked()).isTrue();
        assertThat(found.hasPassword()).isTrue();
        assertThat(provider.findUser(UUID.fromString("00000000-0000-4000-8000-000000000000"))).isEmpty();
        assertThat(provider.findUserByUsername("krentsin")).map(Account::id).contains(ID);
        assertThat(provider.findUserByEmail("k@college.example")).isEmpty();
        server.verify();
    }

    @Test
    void manyIdsAreAskedInBatches() {
        List<UUID> ids = java.util.stream.IntStream.range(0, 150).mapToObj(i -> UUID.randomUUID()).toList();
        server.expect(twice(), request -> assertThat(request.getURI().getQuery()).startsWith("ids="))
                .andRespond(withSuccess("[" + account(true) + "]", MediaType.APPLICATION_JSON));

        assertThat(provider.findUsers(ids)).hasSize(2);
        assertThat(provider.findUsers(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void restoreAccessNeverClearsARevocationButRestoreDoes() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID + "/password")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"password\":\"secret-password-1\"}"))
                .andRespond(withNoContent());
        server.expect(once(), requestTo(BASE + "/accounts/" + ID + "/enable")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"clearRevocation\":false}"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"type\":\"urn:rozklad:auth:account-revoked\",\"status\":409}"));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID + "/enable"))
                .andExpect(content().json("{\"clearRevocation\":true}"))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.restoreAccess(ID, "secret-password-1"))
                .isInstanceOf(AccountRevokedException.class);
        provider.restore(ID);
        server.verify();
    }

    @Test
    void writesNameTheAdminAndRolesArePatched() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none").subject("a0000000-0000-4000-8000-000000000001")
                .claim("preferred_username", "admin").build()));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.PATCH))
                .andExpect(header("X-Actor", "admin"))
                .andExpect(content().json("{\"roles\":[\"ADMIN\",\"TEACHER\"]}"))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"username\":\"krentsin\"}"))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts/" + ID + "/federated/google"))
                .andExpect(method(HttpMethod.DELETE)).andExpect(header("X-Actor", "admin"))
                .andRespond(withNoContent());
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        provider.grantRole(ID, "ADMIN");
        provider.setUsername(ID, "krentsin");
        provider.unbindGoogle(ID);
        provider.deleteUser(ID);
        server.verify();
    }

    @Test
    void listUsersReadsEveryPageOfTheSearch() {
        String full = "[" + String.join(",", java.util.Collections.nCopies(AuthServiceIdentityProvider.LIST_PAGE_SIZE,
                account(true))) + "]";
        server.expect(once(), requestTo(BASE + "/accounts?page=0&size=200")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(full, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/accounts?page=1&size=200"))
                .andRespond(withSuccess("[" + account(false) + "]", MediaType.APPLICATION_JSON));

        assertThat(provider.listUsers()).hasSize(201).last().satisfies(account -> {
            assertThat(account.revoked()).isTrue();
            assertThat(account.googleLinked()).isTrue();
        });
        server.verify();
    }

    @Test
    void displayNameIsAPatch() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID)).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"displayName\":\"Кренцін М. Д.\"}"))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));

        provider.setDisplayName(ID, "Кренцін М. Д.");
        server.verify();
    }

    @Test
    void aRoleTheAccountHasNeedsNoWrite() {
        server.expect(once(), requestTo(BASE + "/accounts/" + ID))
                .andRespond(withSuccess(account(true), MediaType.APPLICATION_JSON));

        provider.grantRole(ID, "TEACHER");
        server.verify();
    }

    private void expectProblem(HttpMethod httpMethod, String path, int status, String code, String reason) {
        String body = "{\"type\":\"urn:rozklad:auth:%s\",\"status\":%d%s}".formatted(code, status,
                reason == null ? "" : ",\"reason\":\"" + reason + "\"");
        server.expect(once(), requestTo(BASE + path)).andExpect(method(httpMethod))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(body));
    }

    private static NewAccount newAccount() {
        return new NewAccount(ID, "krentsin", null, "Кренцін Микола", Set.of("TEACHER"), "secret-password-1");
    }

    /** {@code AccountDto} of auth; {@code enabled=false} means revoked. */
    private static String account(boolean enabled) {
        return """
                {"id":"%s","username":"krentsin","email":null,"displayName":"Кренцін Микола","roles":["TEACHER"],
                 "enabled":%s,"disabledReason":%s,"hasPassword":true,"demo":false,
                 "google":{"linked":true,"email":"k@college.example","linkedAt":"2026-11-01T08:00:00Z"},
                 "lastLoginAt":"2026-11-10T08:00:00Z","createdAt":"2026-11-01T08:00:00Z",
                 "updatedAt":"2026-11-01T08:00:00Z"}"""
                .formatted(ID, enabled, enabled ? "null" : "\"REVOKED\"");
    }
}
