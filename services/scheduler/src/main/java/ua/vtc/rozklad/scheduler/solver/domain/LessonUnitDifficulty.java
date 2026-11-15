package ua.vtc.rozklad.scheduler.solver.domain;

import ai.timefold.solver.core.api.domain.common.ComparatorFactory;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.UnitWeight;

/**
 * Difficulty of placing a unit, ascending (the construction heuristic places the hardest first):
 * WHOLE before HALF (halves then fill the remaining slots in pairs), scarce rooms (the sports hall,
 * labs) first, then busy teachers, then units with fewer allowed timeslots. The subgroup copies of one
 * sibling key (they must stand together, a hard rule) share the difficulty of the hardest of them and
 * come one after another, so the second copy is placed while the slot of the first (or the next pair)
 * is still free.
 */
public class LessonUnitDifficulty implements ComparatorFactory<Timetable, LessonUnit> {

    /** Lexicographic difficulty, ascending: a larger value is harder. */
    private record Difficulty(boolean whole, int fewerRooms, long teacherLoad, int fewerSlots)
            implements Comparable<Difficulty> {

        private static final Comparator<Difficulty> ORDER = Comparator.comparing(Difficulty::whole)
                .thenComparingInt(Difficulty::fewerRooms)
                .thenComparingLong(Difficulty::teacherLoad)
                .thenComparingInt(Difficulty::fewerSlots);

        @Override
        public int compareTo(Difficulty other) {
            return ORDER.compare(this, other);
        }

        Difficulty harder(Difficulty other) {
            return compareTo(other) >= 0 ? this : other;
        }
    }

    @Override
    public Comparator<LessonUnit> createComparator(Timetable timetable) {
        Map<UUID, Long> teacherLoad = timetable.getUnits().stream()
                .filter(LessonUnit::hasTeacher)
                .collect(Collectors.groupingBy(LessonUnit::getTeacherId, Collectors.counting()));
        Map<String, Difficulty> siblings = new HashMap<>();
        for (LessonUnit unit : timetable.getUnits()) {
            if (unit.hasSiblingKey()) {
                siblings.merge(unit.getSiblingKey(), difficulty(unit, teacherLoad), Difficulty::harder);
            }
        }
        return Comparator.comparing((LessonUnit unit) -> unit.hasSiblingKey()
                        ? siblings.getOrDefault(unit.getSiblingKey(), difficulty(unit, teacherLoad))
                        : difficulty(unit, teacherLoad))
                .thenComparing(unit -> unit.hasSiblingKey() ? unit.getSiblingKey() : "")
                .thenComparing(LessonUnit::getId);
    }

    private static Difficulty difficulty(LessonUnit unit, Map<UUID, Long> teacherLoad) {
        return new Difficulty(unit.getWeight() == UnitWeight.WHOLE, -unit.getRoomRange().size(),
                unit.hasTeacher() ? teacherLoad.getOrDefault(unit.getTeacherId(), 0L) : 0L,
                -unit.getTimeslotRange().size());
    }
}
