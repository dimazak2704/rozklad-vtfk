package ua.vtc.rozklad.core.identity;

import java.util.Optional;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/**
 * The caller of the current request and the teacher linked to the account
 * ({@code teacher.keycloak_user_id}). Ownership checks («a teacher changes only own absences,
 * preferences and assigned changes») use {@link #requireTeacherId()}.
 */
public interface CurrentUserService {

    /** @throws org.springframework.security.authentication.AuthenticationCredentialsNotFoundException → 401 */
    CurrentUser require();

    Optional<CurrentUser> find();

    UserProfile profile();

    Optional<TeacherRef> currentTeacher();

    /** @throws org.springframework.web.ErrorResponseException 403 when the account is not linked to a teacher */
    UUID requireTeacherId();
}
