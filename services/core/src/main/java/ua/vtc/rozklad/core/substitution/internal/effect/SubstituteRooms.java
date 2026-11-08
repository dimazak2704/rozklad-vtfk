package ua.vtc.rozklad.core.substitution.internal.effect;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.TeacherRoomQueries;

/**
 * The room a substitute takes the group to (DOMAIN §7, round 7): the room belongs to the teacher,
 * so a cover of another discipline (REPLACE_DISCIPLINE) goes to the substitute's usual room for
 * that discipline in the group ({@code TeacherRooms.forGroup}: else for the discipline, else his
 * usual room) in the published timetable of the lesson's semester, when that room is free at the
 * lesson's date and pair (no lesson or open change there beyond its parallel limit), fits the group
 * and has the room type the discipline needs. Used by {@code POST /api/admin/changes} when no
 * {@code newRoomId} is given; the recommender may call it for its suggested room.
 */
@Component
public class SubstituteRooms {

    private final TeacherRoomQueries teacherRooms;
    private final DatedSlotChecker checker;
    private final CatalogLookup catalog;

    public SubstituteRooms(TeacherRoomQueries teacherRooms, DatedSlotChecker checker, CatalogLookup catalog) {
        this.teacherRooms = teacherRooms;
        this.checker = checker;
        this.catalog = catalog;
    }

    /**
     * The substitute's usual room when it differs from the lesson's room and is free; empty means
     * «keep the lesson's room»: an online lesson, a teacher without lessons in the published
     * timetable, the same room, a room taken out of use, or the room is busy, too small or of the wrong
     * type.
     *
     * @param ownChangeId the change being edited (its own promise of the room does not count), or null
     */
    public Optional<UUID> usualFreeRoom(Lesson lesson, UUID teacherId, UUID disciplineId, UUID ownChangeId) {
        if (lesson.isOnline() || teacherId == null || disciplineId == null) {
            return Optional.empty();
        }
        Optional<UUID> usual = teacherRooms.published(lesson.getSemesterId(), List.of(teacherId))
                .forGroup(teacherId, disciplineId, lesson.getGroupId())
                .filter(room -> !room.equals(lesson.getRoomId()));
        if (usual.isEmpty() || !catalog.room(usual.get()).isActive()) {
            return Optional.empty();
        }
        PlannedLesson planned = new PlannedLesson(lesson.getSemesterId(), lesson.getDate(), lesson.getPairNumber(),
                lesson.getGroupId(), lesson.getSubgroupNumber(), disciplineId, teacherId, usual.get(),
                lesson.getLessonKind(), false, null, LessonOrigin.CHANGE);
        return checker.roomConflicts(planned, Set.of(lesson.getId()), ownChangeId).isEmpty() ? usual
                : Optional.empty();
    }
}
