package ua.vtc.rozklad.core.shared.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Keycloak settings ({@code app.keycloak.*}). Tokens are always issued for {@code issuer}
 * ({@code http://localhost:8180/realms/rozklad}), while keys are fetched from {@code jwksUri}
 * which differs inside docker compose ({@code http://keycloak:8080/...}).
 *
 * @param url      base URL for the admin client ({@code KEYCLOAK_URL})
 * @param admin    service account {@code rozklad-core-admin} used by the identity module
 */
@Validated
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(
        @NotBlank String issuer,
        @NotNull URI jwksUri,
        @NotNull URI url,
        @NotBlank String realm,
        @Valid @NotNull AdminClient admin) {

    public record AdminClient(@NotBlank String clientId, String clientSecret) {

        @Override
        public String toString() {
            return "AdminClient[clientId=" + clientId + ", clientSecret=***]";
        }
    }
}
