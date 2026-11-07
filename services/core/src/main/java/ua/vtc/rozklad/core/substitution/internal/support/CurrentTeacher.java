package ua.vtc.rozklad.core.substitution.internal.support;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/** Resolves the teacher linked to the signed-in account ({@code teacher.keycloak_user_id}). */
@Component
public class CurrentTeacher {

    private final TeacherRepository teachers;

    public CurrentTeacher(TeacherRepository teachers) {
        this.teachers = teachers;
    }

    /** @throws AccessDeniedException (403) when the account is not linked to a teacher */
    public Teacher require(CurrentUser user) {
        if (user.id() == null) {
            throw new AccessDeniedException("Обліковий запис не пов'язаний з викладачем");
        }
        return teachers.findByKeycloakUserId(user.id())
                .orElseThrow(() -> new AccessDeniedException("Обліковий запис не пов'язаний з викладачем"));
    }
}
