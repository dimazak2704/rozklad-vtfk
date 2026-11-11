package ua.vtc.rozklad.core.shared.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Access-token validation ({@code app.auth.*}). Tokens always carry {@code issuer}
 * ({@code AUTH_ISSUER}), while the keys come from {@code jwksUri} ({@code AUTH_JWKS_URI}), which
 * differs inside docker compose. A non-empty {@code audience} ({@code AUTH_AUDIENCE}) must be in
 * {@code aud}.
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(@NotBlank String issuer, @NotNull URI jwksUri, String audience) {

    /** exp/nbf and {@code iss}, plus {@code aud} when an audience is configured. */
    public OAuth2TokenValidator<Jwt> tokenValidator() {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        if (StringUtils.hasText(audience)) {
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(audience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
