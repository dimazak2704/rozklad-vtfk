package ua.vtc.rozklad.core.shared.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authenticated caller resolved from the access token: Keycloak user id ({@code sub}),
 * {@code preferred_username} and realm roles. Declare it as a controller method parameter or use
 * {@link CurrentUserProvider} in services. Actor columns ({@code created_by}, …) store
 * {@link #username()}.
 */
public record CurrentUser(UUID id, String username, Set<String> roles) {

    public CurrentUser {
        Objects.requireNonNull(username, "username");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static CurrentUser from(Jwt jwt) {
        String subject = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        return new CurrentUser(parseUuid(subject), username != null ? username : String.valueOf(subject),
                KeycloakRealmRoleConverter.realmRoles(jwt));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(Roles.ADMIN);
    }

    public boolean isTeacher() {
        return hasRole(Roles.TEACHER);
    }

    public boolean isService() {
        return hasRole(Roles.SERVICE);
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
