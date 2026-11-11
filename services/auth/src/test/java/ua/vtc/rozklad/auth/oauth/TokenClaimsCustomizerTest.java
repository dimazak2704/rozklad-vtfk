package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class TokenClaimsCustomizerTest {

    private static final UUID ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final Account TEACHER = new Account(ID, "krentsin", "krentsin@rozklad.local", "М. Д. Кренцін",
            "{bcrypt}x", true, null, false, Set.of("TEACHER"), null);

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TokenClaimsCustomizer customizer = new TokenClaimsCustomizer(accounts, PropertiesFixture.dev());
    private final List<RegisteredClient> clients = ClientRegistrations.build(PropertiesFixture.dev(), s -> "{noop}" + s);

    @Test
    void userAccessTokensCarryTheAccountRolesAudienceAndActor() {
        when(accounts.findById(ID)).thenReturn(Optional.of(TEACHER));
        JwtEncodingContext context = context("rozklad-web", AuthorizationGrantType.AUTHORIZATION_CODE,
                OAuth2TokenType.ACCESS_TOKEN, ID.toString());

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getSubject()).isEqualTo(ID.toString());
        assertThat(claims.getAudience()).containsExactly("rozklad-web", "rozklad-api");
        assertThat(claims.<List<String>>getClaim("roles")).containsExactly("TEACHER").isInstanceOf(ArrayList.class);
        assertThat(claims.<String>getClaim("preferred_username")).isEqualTo("krentsin");
        assertThat(claims.hasClaim("realm_access")).isFalse();
    }

    @Test
    void anAccountWithoutUsernameUsesItsEmailAsActor() {
        when(accounts.findById(ID)).thenReturn(Optional.of(new Account(ID, null, "teacher@vtc.vn.ua", "Викладач", null,
                true, null, false, Set.of("TEACHER", "ADMIN"), null)));
        JwtEncodingContext context = context("rozklad-web", AuthorizationGrantType.AUTHORIZATION_CODE,
                OAuth2TokenType.ACCESS_TOKEN, ID.toString());

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.<String>getClaim("preferred_username")).isEqualTo("teacher@vtc.vn.ua");
        assertThat(claims.<List<String>>getClaim("roles")).containsExactly("ADMIN", "TEACHER");
    }

    @Test
    void serviceTokensKeepTheKeycloakActorNameAndGetClientRoles() {
        JwtEncodingContext context = context("rozklad-notification", AuthorizationGrantType.CLIENT_CREDENTIALS,
                OAuth2TokenType.ACCESS_TOKEN, "rozklad-notification");

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.getSubject()).isEqualTo("rozklad-notification");
        assertThat(claims.<String>getClaim("preferred_username")).isEqualTo("service-account-rozklad-notification");
        assertThat(claims.<List<String>>getClaim("roles")).containsExactly("SERVICE");
        assertThat(claims.getAudience()).containsExactly("rozklad-notification", "rozklad-api");
    }

    @Test
    void idTokensCarryProfileButNoRoles() {
        when(accounts.findById(ID)).thenReturn(Optional.of(TEACHER));
        JwtEncodingContext context = context("rozklad-web", AuthorizationGrantType.AUTHORIZATION_CODE,
                new OAuth2TokenType(OidcParameterNames.ID_TOKEN), ID.toString());

        customizer.customize(context);

        JwtClaimsSet claims = context.getClaims().build();
        assertThat(claims.<String>getClaim("name")).isEqualTo("М. Д. Кренцін");
        assertThat(claims.<String>getClaim("email")).isEqualTo("krentsin@rozklad.local");
        assertThat(claims.<Boolean>getClaim("email_verified")).isTrue();
        assertThat(claims.hasClaim("roles")).isFalse();
        assertThat(claims.getAudience()).containsExactly("rozklad-web");
    }

    @Test
    void aDisabledOrMissingAccountEndsTheGrant() {
        when(accounts.findById(ID)).thenReturn(Optional.of(new Account(ID, "x.y", null, "x", null, false, "REVOKED",
                false, Set.of("TEACHER"), null)));

        assertThatThrownBy(() -> customizer.customize(context("rozklad-web", AuthorizationGrantType.AUTHORIZATION_CODE,
                OAuth2TokenType.ACCESS_TOKEN, ID.toString())))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(e -> ((OAuth2AuthenticationException) e).getError().getErrorCode()).isEqualTo("invalid_grant");
        assertThatThrownBy(() -> customizer.customize(context("rozklad-web", AuthorizationGrantType.AUTHORIZATION_CODE,
                OAuth2TokenType.ACCESS_TOKEN, "not-a-uuid"))).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void theEncoderPutsTheActiveKidIntoTheHeader() {
        RSAKey key = ua.vtc.rozklad.auth.keys.SigningKeyLoaderAccess.generated("2026-11-a");
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("x").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims));

        assertThat(jwt.getHeaders()).containsEntry("kid", "2026-11-a");
    }

    private JwtEncodingContext context(String clientId, AuthorizationGrantType grant, OAuth2TokenType type,
            String principal) {
        RegisteredClient client = clients.stream().filter(c -> c.getClientId().equals(clientId)).findFirst().orElseThrow();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(principal).audience(List.of(clientId));
        return JwtEncodingContext.with(JwsHeader.with(SignatureAlgorithm.RS256), claims)
                .registeredClient(client)
                .principal(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                        AuthorityUtils.NO_AUTHORITIES))
                .authorizationGrantType(grant)
                .tokenType(type)
                .build();
    }
}
