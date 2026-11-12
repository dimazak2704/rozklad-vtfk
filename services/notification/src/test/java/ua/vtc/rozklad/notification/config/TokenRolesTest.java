package ua.vtc.rozklad.notification.config;

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
        assertThat(TokenRoles.authorities(jwt(Map.of("roles", List.of("ADMIN", "SERVICE")))))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SERVICE");
    }

    @Test
    void malformedEntriesAndRealmAccessAreIgnored() {
        assertThat(TokenRoles.of(jwt(Map.of("roles", List.of("TEACHER", 42, " "))))).containsExactly("TEACHER");
        assertThat(TokenRoles.of(jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN")))))).isEmpty();
    }

    @Test
    void missingOrMalformedClaimMeansNoRoles() {
        assertThat(TokenRoles.of(jwt(Map.of("scope", "openid")))).isEmpty();
        assertThat(TokenRoles.of(jwt(Map.of("roles", "ADMIN")))).isEmpty();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("subject")
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
