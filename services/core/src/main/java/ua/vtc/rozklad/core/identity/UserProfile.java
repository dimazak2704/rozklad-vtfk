package ua.vtc.rozklad.core.identity;

import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.TeacherRef;

/**
 * {@code GET /api/me}: the caller from the access token and the teacher linked to the account.
 *
 * @param userId  Keycloak user id ({@code sub})
 * @param roles   realm roles, sorted
 * @param teacher {@code null} when the account is not linked to a teacher (e.g. an admin)
 */
public record UserProfile(UUID userId, String username, List<String> roles, TeacherRef teacher) {

    public UserProfile {
        roles = List.copyOf(roles);
    }
}
