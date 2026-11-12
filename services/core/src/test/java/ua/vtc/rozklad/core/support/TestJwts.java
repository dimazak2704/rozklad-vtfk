package ua.vtc.rozklad.core.support;

import java.util.List;
import java.util.UUID;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ua.vtc.rozklad.core.shared.security.TokenRoles;

/**
 * Access tokens for MockMvc: roles in the flat {@code roles} claim, mapped by the production
 * {@link TokenRoles}. Ids match the demo accounts.
 */
public final class TestJwts {

    public static final UUID ADMIN_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    public static final UUID KRENTSIN_ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    public static final UUID NEPYIVODA_ID = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    public static final UUID NOTIFICATION_SERVICE_ID = UUID.fromString("a0000000-0000-4000-8000-000000000011");
    public static final UUID IMPORTER_SERVICE_ID = UUID.fromString("a0000000-0000-4000-8000-000000000012");

    private TestJwts() {
    }

    public static JwtRequestPostProcessor admin() {
        return user(ADMIN_ID, "admin", "ADMIN");
    }

    public static JwtRequestPostProcessor teacher() {
        return user(KRENTSIN_ID, "krentsin", "TEACHER");
    }

    /** The Telegram bot's service account (notification), as auth issues it. */
    public static JwtRequestPostProcessor service() {
        return user(NOTIFICATION_SERVICE_ID, "service-account-rozklad-notification", "SERVICE",
                "SERVICE_NOTIFICATION");
    }

    /** The importer's service account, as auth issues it. */
    public static JwtRequestPostProcessor importerService() {
        return user(IMPORTER_SERVICE_ID, "service-account-rozklad-importer", "SERVICE", "SERVICE_IMPORTER");
    }

    public static JwtRequestPostProcessor user(UUID id, String username, String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(id.toString())
                        .claim("preferred_username", username)
                        .claim("roles", List.of(roles)))
                .authorities(TokenRoles::authorities);
    }
}
