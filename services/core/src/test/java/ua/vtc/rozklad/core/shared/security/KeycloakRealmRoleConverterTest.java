package ua.vtc.rozklad.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToSpringRoles() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "TEACHER", "default-roles-rozklad"))));

        assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TEACHER", "ROLE_default-roles-rozklad");
    }

    @Test
    void tokenWithoutRealmAccessHasNoRoles() {
        assertThat(converter.convert(jwt(Map.of("scope", "openid")))).isEmpty();
    }

    @Test
    void ignoresMalformedRoleClaims() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("SERVICE", 42, " "))));

        assertThat(KeycloakRealmRoleConverter.realmRoles(jwt)).containsExactly("SERVICE");
    }

    @Test
    void currentUserIsBuiltFromKeycloakClaims() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("a0000000-0000-4000-8000-000000000002")
                .claim("preferred_username", "krentsin")
                .claim("realm_access", Map.of("roles", List.of("TEACHER")))
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60))
                .build();

        CurrentUser user = CurrentUser.from(jwt);

        assertThat(user.id()).hasToString("a0000000-0000-4000-8000-000000000002");
        assertThat(user.username()).isEqualTo("krentsin");
        assertThat(user.isTeacher()).isTrue();
        assertThat(user.isAdmin()).isFalse();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("subject")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
