package ua.vtc.rozklad.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token validation settings ({@code app.keycloak.*}): keys from {@code jwksUri}
 * ({@code KEYCLOAK_JWKS_URI}), issuer always {@code KEYCLOAK_ISSUER}.
 */
@Validated
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(@NotBlank String issuer, @NotNull URI jwksUri) {
}
