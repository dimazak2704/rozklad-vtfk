package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleView;
import ua.vtc.rozklad.core.timetable.internal.calendar.GroupScope;

/** Owner of a schedule with its public label: group code, teacher short name or room name. */
@Component
public class ScheduleOwners {

    private final StudentGroupRepository groups;
    private final TeacherRepository teachers;
    private final RoomRepository rooms;

    public ScheduleOwners(StudentGroupRepository groups, TeacherRepository teachers, RoomRepository rooms) {
        this.groups = groups;
        this.teachers = teachers;
        this.rooms = rooms;
    }

    /** @throws NotFoundException when the owner does not exist */
    public Resolved resolve(ScheduleOwnerType type, UUID ownerId) {
        return switch (type) {
            case GROUP -> {
                StudentGroup group = groups.findById(ownerId).orElseThrow(() -> NotFoundException.of("Групу", ownerId));
                yield new Resolved(new ScheduleView.Owner(type, ownerId, group.getCode()),
                        new GroupScope(group.getId(), group.getCourse()));
            }
            case TEACHER -> {
                Teacher teacher = teachers.findById(ownerId)
                        .orElseThrow(() -> NotFoundException.of("Викладача", ownerId));
                yield new Resolved(new ScheduleView.Owner(type, ownerId, teacher.getShortName()), null);
            }
            case ROOM -> {
                Room room = rooms.findById(ownerId).orElseThrow(() -> NotFoundException.of("Аудиторію", ownerId));
                yield new Resolved(new ScheduleView.Owner(type, ownerId, room.getName()), null);
            }
        };
    }

    /** @param scope the group for calendar periods with a group/course scope, {@code null} otherwise */
    public record Resolved(ScheduleView.Owner owner, GroupScope scope) {
    }
}
