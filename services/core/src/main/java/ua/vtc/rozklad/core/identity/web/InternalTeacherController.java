package ua.vtc.rozklad.core.identity.web;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ua.vtc.rozklad.core.identity.internal.InternalTeacherService;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InternalTeacherDto;

/** {@code GET /api/internal/teachers/{id}} (SERVICE): a teacher with the link to the Keycloak account. */
@RestController
class InternalTeacherController {

    private final InternalTeacherService teachers;

    InternalTeacherController(InternalTeacherService teachers) {
        this.teachers = teachers;
    }

    @GetMapping("/api/internal/teachers/{id}")
    InternalTeacherDto get(@PathVariable UUID id) {
        return teachers.get(id);
    }
}
