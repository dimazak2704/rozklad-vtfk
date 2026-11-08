package ua.vtc.rozklad.core.load;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read API of the teaching load (навантаження) for other modules. */
public interface LoadQueries {

    List<LoadItemView> semesterItems(UUID semesterId);

    List<LoadItemView> groupItems(UUID semesterId, UUID groupId);

    Optional<LoadItemView> item(UUID id);

    /**
     * Weekly units of all load items of the semester (DOMAIN §4): per item {@code whole} WHOLE and
     * {@code half} HALF units for the whole group, or for every subgroup of a split group.
     */
    List<WeeklyUnit> weeklyUnits(UUID semesterId);

    /** Pattern of {@code hoursPerWeek} with the current {@code hoursPerPair} setting. */
    LoadPattern pattern(BigDecimal hoursPerWeek);

    /** The teacher has a load item with the discipline in the semester (candidate ranking, DOMAIN §7). */
    boolean teachesInSemester(UUID teacherId, UUID disciplineId, UUID semesterId);
}
