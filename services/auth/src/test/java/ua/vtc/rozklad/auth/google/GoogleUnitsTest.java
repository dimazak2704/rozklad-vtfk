package ua.vtc.rozklad.auth.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.mock.web.MockHttpServletRequest;
import ua.vtc.rozklad.auth.config.GoogleProperties;
import ua.vtc.rozklad.auth.login.LoginAttempts;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

/** The domain rule, the registration builder, the authorization request and the principal normalisation. */
class GoogleUnitsTest {

    private static final String DOMAIN = "vtc.vn.ua";

    @Test
    void withoutAConfiguredDomainAnyVerifiedAddressPassesTheDomainRule() {
        assertThat(GoogleAllowlist.domainAllows(identity("a@gmail.com", null), null, false)).isTrue();
        assertThat(GoogleAllowlist.domainAllows(identity("a@vtc.vn.ua", null), null, false)).isTrue();
    }

    @Test
    void aCollegeAddressNeedsTheSameHostedDomainWhateverConsumerAccountsSay() {
        assertThat(GoogleAllowlist.domainAllows(identity("a@vtc.vn.ua", DOMAIN), DOMAIN, false)).isTrue();
        assertThat(GoogleAllowlist.domainAllows(identity("a@vtc.vn.ua", null), DOMAIN, false)).isFalse();
        assertThat(GoogleAllowlist.domainAllows(identity("a@vtc.vn.ua", null), DOMAIN, true)).isFalse();
        assertThat(GoogleAllowlist.domainAllows(identity("a@vtc.vn.ua", "evil.ua"), DOMAIN, true)).isFalse();
    }

    @Test
    void anotherDomainPassesOnlyWithConsumerAccounts() {
        assertThat(GoogleAllowlist.domainAllows(identity("a@gmail.com", null), DOMAIN, false)).isFalse();
        assertThat(GoogleAllowlist.domainAllows(identity("a@gmail.com", null), DOMAIN, true)).isTrue();
    }

    @Test
    void theIdentityComesFromTheIdTokenInLowerCase() {
        OidcIdToken token = token(Map.of("sub", "123", "email", "Teacher@VTC.vn.ua", "email_verified", true,
                "hd", "VTC.vn.ua"));

        GoogleIdentity identity = GoogleIdentity.of(token);

        assertThat(identity).isEqualTo(new GoogleIdentity("123", "teacher@vtc.vn.ua", true, "vtc.vn.ua"));
        assertThat(identity.emailDomain()).isEqualTo("vtc.vn.ua");
        assertThat(GoogleIdentity.of(token(Map.of("sub", "1", "email", "a@b.c"))).emailVerified()).isFalse();
    }

    @Test
    void googleIsOffWithoutAClientId() {
        assertThat(GoogleSignIn.registration(GoogleProperties.disabled(), PropertiesFixture.dev())).isEmpty();
    }

    @Test
    void realGoogleUsesGooglesOwnEndpointsAndIgnoresOverrides() {
        var google = new GoogleProperties("client", "secret", DOMAIN, false, false, "http://evil/issuer",
                "http://evil/authorize", "http://evil/token", "http://evil/jwks", null);

        ClientRegistration registration = GoogleSignIn.registration(google, PropertiesFixture.dev()).orElseThrow();

        assertThat(registration.getProviderDetails().getAuthorizationUri()).startsWith("https://accounts.google.com/");
        assertThat(registration.getProviderDetails().getTokenUri()).startsWith("https://www.googleapis.com/");
        assertThat(registration.getProviderDetails().getJwkSetUri()).startsWith("https://www.googleapis.com/");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo("https://accounts.google.com");
        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(registration.getRedirectUri()).isEqualTo("http://localhost:8088/auth/login/oauth2/code/google");
        assertThat(registration.getClientSettings().isRequireProofKey()).isTrue();
    }

    @Test
    void theMockNeedsItsEndpoints() {
        var incomplete = new GoogleProperties("rozklad-dev", "s", null, false, true, "http://mock/google", null, null,
                null, null);
        var complete = new GoogleProperties("rozklad-dev", "s", null, false, true, "http://mock-oidc:8090/google",
                "http://localhost:8090/google/authorize", "http://mock-oidc:8090/google/token",
                "http://mock-oidc:8090/google/jwks", "http://mock-oidc:8090/google/userinfo");

        assertThatThrownBy(() -> GoogleSignIn.registration(incomplete, PropertiesFixture.dev()))
                .hasMessageContaining("AUTH_GOOGLE_AUTHORIZATION_URI");
        ClientRegistration registration = GoogleSignIn.registration(complete, PropertiesFixture.dev()).orElseThrow();
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .isEqualTo("http://localhost:8090/google/authorize");
        assertThat(registration.getProviderDetails().getIssuerUri()).isEqualTo("http://mock-oidc:8090/google");
    }

    @Test
    void theAuthorizationRequestCarriesHdOnlyWithADomain() {
        var google = new GoogleProperties("client", "secret", DOMAIN, false, false, null, null, null, null, null);
        var clients = new InMemoryClientRegistrationRepository(
                GoogleSignIn.registration(google, PropertiesFixture.dev()).orElseThrow());
        var request = new MockHttpServletRequest("GET", "/auth/oauth2/authorization/google");
        request.setContextPath("/auth");

        assertThat(GoogleLogin.resolver(clients, DOMAIN).resolve(request).getAdditionalParameters())
                .containsEntry("hd", DOMAIN);
        assertThat(GoogleLogin.resolver(clients, null).resolve(request).getAdditionalParameters())
                .doesNotContainKey("hd");
    }

    @Test
    void aGoogleSignInBecomesTheSamePrincipalAsAPasswordSignIn() {
        UUID accountId = UUID.randomUUID();
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_TEACHER"));
        var user = new GoogleAccountUser(accountId, authorities, token(Map.of("sub", "g-1")), null);
        var google = new OAuth2AuthenticationToken(user, authorities, "google");

        Instant now = Instant.parse("2026-11-12T08:00:00Z");

        var normalised = (UsernamePasswordAuthenticationToken) GoogleLoginHandlers.normalise(google, now);

        assertThat(normalised.getName()).isEqualTo(accountId.toString());
        assertThat(normalised.getPrincipal()).isInstanceOf(User.class);
        assertThat(((User) normalised.getPrincipal()).getPassword()).isNull();
        assertThat(((User) normalised.getPrincipal()).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TEACHER");
        // auth_time ID token-а береться з фактора входу
        assertThat(normalised.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TEACHER", FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
        assertThat(normalised.getAuthorities()).filteredOn(FactorGrantedAuthority.class::isInstance)
                .singleElement().extracting(a -> ((FactorGrantedAuthority) a).getIssuedAt()).isEqualTo(now);
        assertThatThrownBy(() -> GoogleLoginHandlers.normalise(new OAuth2AuthenticationToken(
                new DefaultOidcUser(authorities, token(Map.of("sub", "x"))), authorities, "google"), now))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusalCodesAreTheOutcomesInLowerCase() {
        assertThat(AllowlistOidcUserService.errorCode(LoginAttempts.Outcome.IDENTITY_CONFLICT))
                .isEqualTo("identity_conflict");
        assertThat(GoogleLoginHandlers.NOT_ALLOWED).contains("not_allowed", "domain_mismatch", "disabled", "revoked",
                "identity_conflict").doesNotContain("email_unverified");
    }

    private static GoogleIdentity identity(String email, String hd) {
        return new GoogleIdentity("sub", email, true, hd);
    }

    private static OidcIdToken token(Map<String, Object> claims) {
        return new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims);
    }
}
