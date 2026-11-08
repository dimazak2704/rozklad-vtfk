package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.core.load.LoadPattern;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository.LoadRow;

/**
 * Load → weekly units (DOMAIN.md §4): {@code pairs = hoursPerWeek / hoursPerPair}; the whole part
 * gives WHOLE units, a remainder in [0.25, 0.75) one HALF unit (numerator or denominator, chosen
 * by the solver), a remainder of 0.75 or more one more WHOLE unit. 3 h → 1 + ½, 4 h → 2, 1 h → ½.
 * A split load item gives units per subgroup with that subgroup's teacher.
 */
public final class LoadUnitExpander {

    private LoadUnitExpander() {
    }

    /**
     * Units of one load item.
     *
     * @param storedSubgroups subgroups of the group in the catalog (used when a split item names one teacher)
     * @throws SplitWithoutSubgroupsException when the item is split but neither the item nor the
     *                                        catalog says which subgroups exist
     */
    public static List<PlanUnit> expand(LoadRow item, Set<Integer> storedSubgroups, int hoursPerPair) {
        // правило «пари = години на тиждень / години на пару» одне на систему: LoadPattern модуля load
        LoadPattern pattern = LoadPattern.of(item.hoursPerWeek(), hoursPerPair);
        List<PlanUnit> units = new ArrayList<>();
        cohorts(item, storedSubgroups).forEach((subgroup, teacherId) -> {
            for (int index = 1; index <= pattern.whole(); index++) {
                units.add(unit(item, subgroup, teacherId, UnitWeight.WHOLE, "w" + index));
            }
            if (pattern.half() > 0) {
                units.add(unit(item, subgroup, teacherId, UnitWeight.HALF, "h"));
            }
        });
        return units;
    }

    /** Subgroup → teacher (teacher may be {@code null} for a vacancy); subgroup 0 for an unsplit item. */
    private static Map<Integer, UUID> cohorts(LoadRow item, Set<Integer> storedSubgroups) {
        Map<Integer, UUID> cohorts = new TreeMap<>();
        UUID wholeGroupTeacher = item.teachersBySubgroup().get(0);
        if (!item.splitSubgroups()) {
            cohorts.put(0, wholeGroupTeacher);
            return cohorts;
        }
        item.teachersBySubgroup().forEach((subgroup, teacherId) -> {
            if (subgroup > 0) {
                cohorts.put(subgroup, teacherId);
            }
        });
        if (cohorts.isEmpty()) {
            // поділ є, але викладачів підгруп не вказано: той самий викладач веде кожну підгрупу каталогу
            storedSubgroups.stream().filter(number -> number > 0).forEach(number -> cohorts.put(number,
                    wholeGroupTeacher));
        }
        if (cohorts.isEmpty()) {
            throw new SplitWithoutSubgroupsException(item);
        }
        return cohorts;
    }

    private static PlanUnit unit(LoadRow item, int subgroup, UUID teacherId, UnitWeight weight, String suffix) {
        return new PlanUnit(item.id() + ":" + subgroup + ":" + suffix, item.id(), item.groupId(), subgroup,
                item.disciplineId(), teacherId, weight, item.lessonKind(), item.requiredRoomType(),
                item.preferredRoomId(), item.streamId(), false, null, null);
    }

    /** A split load item without subgroups. */
    public static final class SplitWithoutSubgroupsException extends RuntimeException {

        private final transient LoadRow item;

        SplitWithoutSubgroupsException(LoadRow item) {
            super("Load item " + item.id() + " is split but has no subgroups");
            this.item = item;
        }

        public LoadRow item() {
            return item;
        }
    }
}
