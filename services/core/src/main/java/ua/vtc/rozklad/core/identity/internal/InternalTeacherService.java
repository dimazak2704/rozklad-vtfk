package ua.vtc.rozklad.core.identity.internal;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.CycleCommissionRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InternalTeacherDto;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/** {@code GET /api/internal/teachers/{id}} for services (notification binds Telegram chats to teachers). */
@Service
@Transactional(readOnly = true)
public class InternalTeacherService {

    private final TeacherRepository teachers;
    private final CycleCommissionRepository commissions;

    public InternalTeacherService(TeacherRepository teachers, CycleCommissionRepository commissions) {
        this.teachers = teachers;
        this.commissions = commissions;
    }

    public InternalTeacherDto get(UUID id) {
        Teacher teacher = teachers.findById(id).orElseThrow(() -> NotFoundException.of("Викладача", id));
        String commission = teacher.getCycleCommissionId() == null ? null
                : commissions.findById(teacher.getCycleCommissionId()).map(CycleCommission::getName).orElse(null);
        return new InternalTeacherDto(teacher.getId(), teacher.getLastName(), teacher.getFirstName(),
                teacher.getMiddleName(), teacher.getShortName(), teacher.getDisplayName(), commission,
                teacher.isActive(), teacher.getKeycloakUserId(), teacher.getKeycloakUserId() != null);
    }
}
