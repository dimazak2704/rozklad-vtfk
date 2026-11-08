package ua.vtc.rozklad.core.load;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Read model of a load item for other modules (generation, accounting, substitution options).
 *
 * @param teachers subgroup number → teacher (0 when the group is not split)
 * @param pattern  weekly units with the current {@code hoursPerPair}
 */
public record LoadItemView(
        UUID id,
        UUID semesterId,
        UUID groupId,
        UUID disciplineId,
        LessonKind lessonKind,
        BigDecimal hoursPerWeek,
        int plannedHours,
        boolean splitSubgroups,
        Map<Integer, UUID> teachers,
        RoomType requiredRoomType,
        UUID preferredRoomId,
        ControlForm controlForm,
        UUID streamId,
        LoadSource source,
        LoadPattern pattern) {

    public LoadItemView {
        teachers = Map.copyOf(teachers);
    }

    public Optional<UUID> teacherOf(int subgroup) {
        return Optional.ofNullable(teachers.get(subgroup));
    }
}
