package ua.vtc.rozklad.core.shared.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Keycloak admin API settings of the identity module ({@code app.keycloak.*}). Token validation
 * lives in {@link AuthProperties}.
 *
 * @param url      base URL for the admin client ({@code KEYCLOAK_URL})
 * @param admin    service account {@code rozklad-core-admin} used by the identity module
 */
@Validated
@ConfigurationProperties("app.keycloak")
public record KeycloakProperties(
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
