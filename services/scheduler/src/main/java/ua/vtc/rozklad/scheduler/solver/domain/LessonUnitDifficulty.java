package ua.vtc.rozklad.scheduler.solver.domain;

import ai.timefold.solver.core.api.domain.common.ComparatorFactory;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.UnitWeight;

/**
 * Difficulty of placing a unit, ascending (the construction heuristic places the hardest first):
 * WHOLE before HALF (halves then fill the remaining slots in pairs), scarce rooms (the sports
 * hall, labs) first, then busy teachers, then units with fewer allowed timeslots.
 */
public class LessonUnitDifficulty implements ComparatorFactory<Timetable, LessonUnit> {

    @Override
    public Comparator<LessonUnit> createComparator(Timetable timetable) {
        Map<UUID, Long> teacherLoad = timetable.getUnits().stream()
                .filter(LessonUnit::hasTeacher)
                .collect(Collectors.groupingBy(LessonUnit::getTeacherId, Collectors.counting()));
        Function<LessonUnit, Long> loadOfTeacher =
                unit -> unit.hasTeacher() ? teacherLoad.get(unit.getTeacherId()) : 0L;
        return Comparator.comparing((LessonUnit unit) -> unit.getWeight() == UnitWeight.WHOLE)
                .thenComparingInt(unit -> -unit.getRoomRange().size())
                .thenComparing(loadOfTeacher)
                .thenComparingInt(unit -> -unit.getTimeslotRange().size())
                .thenComparing(LessonUnit::getId);
    }
}
