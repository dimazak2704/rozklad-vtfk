package ua.vtc.rozklad.importer.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.validation.annotation.Validated;

/**
 * Access-token validation ({@code app.auth.*}). Tokens always carry {@code issuer}
 * ({@code AUTH_ISSUER}), while the keys come from {@code jwksUri} ({@code AUTH_JWKS_URI}), which
 * differs inside docker compose. Every token must name {@code audience} ({@code AUTH_AUDIENCE},
 * {@code rozklad-api}) in {@code aud}.
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(@NotBlank String issuer, @NotNull URI jwksUri, @NotBlank String audience) {

    /** exp/nbf, {@code iss} and {@code aud}. */
    public OAuth2TokenValidator<Jwt> tokenValidator() {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }
}
