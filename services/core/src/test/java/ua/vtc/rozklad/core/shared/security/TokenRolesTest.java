package ua.vtc.rozklad.core.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class TokenRolesTest {

    @Test
    void flatRolesClaimBecomesSpringRoles() {
        Jwt jwt = jwt(Map.of("roles", List.of("ADMIN", "TEACHER")));

        assertThat(TokenRoles.authorities(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TEACHER");
    }

    @Test
    void keycloakRealmRolesAreReadWhenTheFlatClaimIsMissing() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "TEACHER", "default-roles-rozklad"))));

        assertThat(TokenRoles.authorities(jwt)).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_TEACHER", "ROLE_default-roles-rozklad");
    }

    @Test
    void flatClaimWinsOverRealmAccess() {
        Jwt jwt = jwt(Map.of("roles", List.of("TEACHER"), "realm_access", Map.of("roles", List.of("ADMIN"))));

        assertThat(TokenRoles.of(jwt)).containsExactly("TEACHER");
    }

    @Test
    void tokenWithoutRolesHasNoRoles() {
        assertThat(TokenRoles.authorities(jwt(Map.of("scope", "openid")))).isEmpty();
        assertThat(TokenRoles.of(jwt(Map.of("realm_access", Map.of("roles", "ADMIN"))))).isEmpty();
        assertThat(TokenRoles.of(jwt(Map.of("realm_access", "ADMIN")))).isEmpty();
        assertThat(TokenRoles.of(jwt(Map.of("roles", "ADMIN")))).isEmpty();
    }

    @Test
    void ignoresMalformedRoleEntries() {
        assertThat(TokenRoles.of(jwt(Map.of("roles", List.of("SERVICE", 42, " "))))).containsExactly("SERVICE");
        assertThat(TokenRoles.of(jwt(Map.of("realm_access", Map.of("roles", List.of("SERVICE", 42, " "))))))
                .containsExactly("SERVICE");
    }

    @Test
    void currentUserIsBuiltFromEitherClaimShape() {
        CurrentUser flat = CurrentUser.from(jwt(Map.of("preferred_username", "krentsin", "roles", List.of("TEACHER"))));
        CurrentUser keycloak = CurrentUser.from(jwt(Map.of("preferred_username", "krentsin",
                "realm_access", Map.of("roles", List.of("TEACHER")))));

        for (CurrentUser user : List.of(flat, keycloak)) {
            assertThat(user.id()).hasToString("a0000000-0000-4000-8000-000000000002");
            assertThat(user.username()).isEqualTo("krentsin");
            assertThat(user.isTeacher()).isTrue();
            assertThat(user.isAdmin()).isFalse();
        }
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("a0000000-0000-4000-8000-000000000002")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
