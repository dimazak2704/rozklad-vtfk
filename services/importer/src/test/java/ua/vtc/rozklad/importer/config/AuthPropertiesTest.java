package ua.vtc.rozklad.importer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class AuthPropertiesTest {

    private static final String ISSUER = "http://localhost:8088/auth";
    private static final URI JWKS = URI.create("http://auth:8085/auth/oauth2/jwks");

    @Test
    void emptyAudienceChecksOnlyIssuerAndLifetime() {
        OAuth2TokenValidator<Jwt> validator = new AuthProperties(ISSUER, JWKS, "").tokenValidator();

        assertThat(validator.validate(token(ISSUER, null)).hasErrors()).isFalse();
        assertThat(validator.validate(token("http://evil.example/auth", null)).hasErrors()).isTrue();
    }

    @Test
    void configuredAudienceMustBeInTheToken() {
        OAuth2TokenValidator<Jwt> validator = new AuthProperties(ISSUER, JWKS, "rozklad-api").tokenValidator();

        assertThat(validator.validate(token(ISSUER, List.of("rozklad-web", "rozklad-api"))).hasErrors()).isFalse();
        assertThat(validator.validate(token(ISSUER, List.of("rozklad-web"))).hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, null)).hasErrors()).isTrue();
    }

    private static Jwt token(String issuer, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("subject")
                .issuer(issuer).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }
}
