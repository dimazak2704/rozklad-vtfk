package ua.vtc.rozklad.core.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ua.vtc.rozklad.core.shared.security.KeycloakRealmRoleConverter;

/**
 * Keycloak-like access tokens for MockMvc: realm roles in {@code realm_access.roles} mapped by the
 * production {@link KeycloakRealmRoleConverter}. Ids match the demo users of the realm import.
 */
public final class TestJwts {

    public static final UUID ADMIN_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    public static final UUID KRENTSIN_ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    public static final UUID NEPYIVODA_ID = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    public static final UUID NOTIFICATION_SERVICE_ID = UUID.fromString("a0000000-0000-4000-8000-000000000011");

    private TestJwts() {
    }

    public static JwtRequestPostProcessor admin() {
        return user(ADMIN_ID, "admin", "ADMIN");
    }

    public static JwtRequestPostProcessor teacher() {
        return user(KRENTSIN_ID, "krentsin", "TEACHER");
    }

    public static JwtRequestPostProcessor service() {
        return user(NOTIFICATION_SERVICE_ID, "service-account-rozklad-notification", "SERVICE");
    }

    public static JwtRequestPostProcessor user(UUID id, String username, String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id.toString())
                        .claim("preferred_username", username)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
