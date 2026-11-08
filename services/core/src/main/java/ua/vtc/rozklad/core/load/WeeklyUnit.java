package ua.vtc.rozklad.core.load;

import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;

/**
 * One weekly unit to place in the timetable (a {@code ProblemSnapshot} unit without the
 * group's home room and pins, which belong to catalog and timetable): a WHOLE pair every week or a
 * HALF pair every other week, per subgroup with that subgroup's teacher.
 *
 * @param unitId    opaque id, unique within a semester: {@code <loadItemId>:<subgroup>:<index>}
 * @param teacherId {@code null} while no teacher is assigned to the subgroup
 */
public record WeeklyUnit(
        String unitId,
        UUID loadItemId,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UnitWeight weight,
        RoomType requiredRoomType,
        UUID preferredRoomId,
        UUID streamId) {
}
