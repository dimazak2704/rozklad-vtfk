package ua.vtc.rozklad.core.timetable;

import java.util.Collection;
import java.util.UUID;

/**
 * Usual rooms of teachers ({@link TeacherRooms}) in the published weekly timetable of a semester.
 * Used by the generator (preferred room of a unit) and by substitutions (the room a substitute
 * takes the group to). One SQL statement per call.
 */
public interface TeacherRoomQueries {

    /**
     * Usual rooms of the teachers in the published version of the semester; {@link TeacherRooms#NONE}
     * when the semester has no published version or none of the teachers has lessons in it.
     */
    TeacherRooms published(UUID semesterId, Collection<UUID> teacherIds);
}
