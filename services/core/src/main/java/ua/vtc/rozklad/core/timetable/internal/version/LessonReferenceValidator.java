package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;

/**
 * Checks that a template lesson refers to existing catalog entries (422 instead of a foreign-key
 * error) and that its load item belongs to the same semester, group and discipline.
 */
@Component
public class LessonReferenceValidator {

    public static final String REFERENCE_NOT_FOUND = "REFERENCE_NOT_FOUND";
    public static final String LOAD_ITEM_MISMATCH = "LOAD_ITEM_MISMATCH";

    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;
    private final LoadReadRepository loads;

    public LessonReferenceValidator(StudentGroupRepository groups, DisciplineRepository disciplines,
                                    TeacherRepository teachers, RoomRepository rooms, LoadReadRepository loads) {
        this.groups = groups;
        this.disciplines = disciplines;
        this.teachers = teachers;
        this.rooms = rooms;
        this.loads = loads;
    }

    public void validate(TemplateLessonCommand command, UUID semesterId) {
        require(groups.existsById(command.groupId()), "Групу", command.groupId());
        require(disciplines.existsById(command.disciplineId()), "Дисципліну", command.disciplineId());
        if (command.teacherId() != null) {
            require(teachers.existsById(command.teacherId()), "Викладача", command.teacherId());
        }
        validateRoom(command.roomId());
        if (command.loadItemId() != null) {
            LoadReadRepository.LoadItemKey loadItem = loads.findKey(command.loadItemId())
                    .orElseThrow(() -> missing("Навантаження", command.loadItemId()));
            if (!loadItem.semesterId().equals(semesterId) || !loadItem.groupId().equals(command.groupId())
                    || !loadItem.disciplineId().equals(command.disciplineId())) {
                throw new BusinessRuleException(LOAD_ITEM_MISMATCH,
                        "Навантаження не відповідає групі, дисципліні або семестру заняття.");
            }
        }
    }

    public void validateRoom(UUID roomId) {
        if (roomId != null) {
            require(rooms.existsById(roomId), "Аудиторію", roomId);
        }
    }

    private static void require(boolean exists, String subject, UUID id) {
        if (!exists) {
            throw missing(subject, id);
        }
    }

    private static BusinessRuleException missing(String subject, UUID id) {
        return new BusinessRuleException(REFERENCE_NOT_FOUND, NotFoundException.of(subject, id).getMessage());
    }
}
