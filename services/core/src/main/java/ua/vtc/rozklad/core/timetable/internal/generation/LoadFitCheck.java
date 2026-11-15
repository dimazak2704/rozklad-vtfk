package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.catalog.CatalogLabels;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/**
 * B9: does the load fit the timetable grid at all? Checked on the built snapshot (all groups, pinned
 * lessons included) before the job is stored, so an impossible request is a clear 422 instead of a run
 * that spends its whole time limit and ends with hard conflicts. Counts are week occurrences (a BOTH unit
 * is 2, a numerator or denominator half is 1) against 2 × slots, so 18,5 pairs fit 19 slots.
 * <ul>
 *   <li>GROUP: whole-group units plus one cohort's own units against the slots of the group's shift window; on
 *       Friday a group the run may change gets at most its Friday limit (the scheduler's hard rule), or the pairs
 *       pinned there when they are more;</li>
 *   <li>TEACHER: the teacher's units against the union of the windows of their groups minus UNAVAILABLE slots;</li>
 *   <li>ROOM_TYPE: units that require a type against its rooms' parallel places over their groups' windows.</li>
 * </ul>
 * A day without windows needs no extra slot (the pairs of a day can always stand one after another), so it is not
 * counted here: whether the days can be packed depends on teachers and rooms, which the solver reports.
 */
public final class LoadFitCheck {

    public static final String LOAD_EXCEEDS_SLOTS = "GENERATION_LOAD_EXCEEDS_SLOTS";

    private static final int FRIDAY = 5;

    private LoadFitCheck() {
    }

    static Result check(ProblemSnapshot snapshot) {
        Map<Integer, Set<Integer>> slotsOfShift = slotsOfShift(snapshot.shiftWindows());
        Map<UUID, ProblemSnapshot.Group> groups = snapshot.groups().stream()
                .collect(Collectors.toMap(ProblemSnapshot.Group::id, Function.identity()));
        List<Problem> problems = new ArrayList<>();
        groupProblems(snapshot, slotsOfShift, problems);
        teacherProblems(snapshot, groups, slotsOfShift, problems);
        roomTypeProblems(snapshot, groups, slotsOfShift, problems);
        problems.sort(Comparator.comparing(Problem::kind).thenComparing(Problem::label, UkrainianText.collator()));
        return new Result(problems);
    }

    private static void groupProblems(ProblemSnapshot snapshot, Map<Integer, Set<Integer>> slotsOfShift,
                                      List<Problem> problems) {
        Map<UUID, List<ProblemSnapshot.Unit>> unitsByGroup = snapshot.units().stream()
                .collect(Collectors.groupingBy(ProblemSnapshot.Unit::groupId));
        for (ProblemSnapshot.Group group : snapshot.groups()) {
            List<ProblemSnapshot.Unit> units = unitsByGroup.getOrDefault(group.id(), List.of());
            if (units.isEmpty()) {
                continue;
            }
            Set<Integer> shiftSlots = slotsOfShift.getOrDefault(group.shift(), Set.of());
            int fridaySlots = (int) shiftSlots.stream().filter(slot -> slot / 10 == FRIDAY).count();
            // межа пʼятниці — жорстке правило лише для групи, яку генерація може змінити (є вільна пара)
            boolean limited = group.fridayMaxPairs() != null && group.fridayMaxPairs() < fridaySlots
                    && units.stream().anyMatch(unit -> unit.pinned() == null);
            int whole = occurrences(units.stream().filter(unit -> unit.subgroup() == 0).toList());
            Set<Integer> cohorts = new TreeSet<>(group.subgroups());
            units.stream().map(ProblemSnapshot.Unit::subgroup).filter(subgroup -> subgroup > 0).forEach(cohorts::add);
            if (cohorts.isEmpty()) {
                cohorts.add(0);
            }
            for (int cohort : cohorts) {
                int own = cohort == 0 ? 0 : occurrences(units.stream().filter(unit -> unit.subgroup() == cohort)
                        .toList());
                int slots = shiftSlots.size();
                String friday = "";
                if (limited) {
                    // закріплені пʼятничні пари понад межу стоять, але вільна пара до них уже не стане
                    int pinnedFriday = occurrences(units.stream()
                            .filter(unit -> unit.subgroup() == 0 || unit.subgroup() == cohort)
                            .filter(unit -> unit.pinned() != null && unit.pinned().weekday() == FRIDAY).toList());
                    slots = slots - fridaySlots + Math.max(group.fridayMaxPairs(), (pinnedFriday + 1) / 2);
                    friday = " (у пʼятницю не більше %s)".formatted(fridayPairs(group.fridayMaxPairs()));
                }
                if (whole + own > 2 * slots) {
                    String label = cohort == 0 ? group.code() : group.code() + ", підгрупа " + cohort;
                    problems.add(new Problem(Kind.GROUP, group.id().toString(), label, (whole + own) / 2.0, slots,
                            "Група %s: %s на тиждень, а в її зміні %s%s.".formatted(label, pairs(whole + own),
                                    slots(slots), friday)));
                }
            }
        }
    }

    private static void teacherProblems(ProblemSnapshot snapshot, Map<UUID, ProblemSnapshot.Group> groups,
                                        Map<Integer, Set<Integer>> slotsOfShift, List<Problem> problems) {
        Map<UUID, List<ProblemSnapshot.Unit>> unitsByTeacher = snapshot.units().stream()
                .filter(unit -> unit.teacherId() != null)
                .collect(Collectors.groupingBy(ProblemSnapshot.Unit::teacherId));
        for (ProblemSnapshot.Teacher teacher : snapshot.teachers()) {
            List<ProblemSnapshot.Unit> units = unitsByTeacher.getOrDefault(teacher.id(), List.of());
            if (units.isEmpty()) {
                continue;
            }
            Set<Integer> slots = new HashSet<>();
            units.stream().map(unit -> groups.get(unit.groupId())).filter(java.util.Objects::nonNull)
                    .map(ProblemSnapshot.Group::shift).distinct()
                    .forEach(shift -> slots.addAll(slotsOfShift.getOrDefault(shift, Set.of())));
            teacher.unavailable().forEach(slot -> slots.remove(key(slot.weekday(), slot.pair())));
            int needed = occurrences(units);
            if (needed > 2 * slots.size()) {
                String label = teacher.name();
                problems.add(new Problem(Kind.TEACHER, teacher.id().toString(), label, needed / 2.0, slots.size(),
                        "Викладач %s: %s на тиждень, а вільних слотів у змінах його груп — %d.".formatted(label,
                                pairs(needed), slots.size())));
            }
        }
    }

    private static void roomTypeProblems(ProblemSnapshot snapshot, Map<UUID, ProblemSnapshot.Group> groups,
                                         Map<Integer, Set<Integer>> slotsOfShift, List<Problem> problems) {
        Map<RoomType, Integer> places = new EnumMap<>(RoomType.class);
        snapshot.rooms().forEach(room -> places.merge(room.type(), Math.max(room.maxParallel(), 1), Integer::sum));
        Map<RoomType, List<ProblemSnapshot.Unit>> unitsByType = new EnumMap<>(RoomType.class);
        snapshot.units().stream()
                .filter(unit -> unit.requiredRoomType() != null && !unit.online())
                .forEach(unit -> unitsByType.computeIfAbsent(unit.requiredRoomType(), type -> new ArrayList<>())
                        .add(unit));
        unitsByType.forEach((type, units) -> {
            Integer parallel = places.get(type);
            if (parallel == null) {
                return; // типу без жодної аудиторії стосується перевірка планувальника
            }
            Set<Integer> slots = new HashSet<>();
            units.stream().map(unit -> groups.get(unit.groupId())).filter(java.util.Objects::nonNull)
                    .map(ProblemSnapshot.Group::shift).distinct()
                    .forEach(shift -> slots.addAll(slotsOfShift.getOrDefault(shift, Set.of())));
            int available = parallel * slots.size();
            int needed = occurrences(units);
            if (needed > 2 * available) {
                String label = CatalogLabels.roomType(type);
                problems.add(new Problem(Kind.ROOM_TYPE, type.name(), label, needed / 2.0, available,
                        "%s: %s на тиждень, а місць у змінах цих груп — %d.".formatted(label, pairs(needed),
                                available)));
            }
        });
    }

    /** Week occurrences: a BOTH unit takes both weeks, a half one. */
    private static int occurrences(List<ProblemSnapshot.Unit> units) {
        return units.stream().mapToInt(unit -> unit.weight() == UnitWeight.WHOLE ? 2 : 1).sum();
    }

    /** Shift → weekday × pair keys of its windows. */
    private static Map<Integer, Set<Integer>> slotsOfShift(List<ProblemSnapshot.ShiftWindow> windows) {
        Map<Integer, Set<Integer>> slots = new HashMap<>();
        for (ProblemSnapshot.ShiftWindow window : windows) {
            for (int pair = window.firstPair(); pair <= window.lastPair(); pair++) {
                slots.computeIfAbsent(window.shift(), shift -> new HashSet<>()).add(key(window.weekday(), pair));
            }
        }
        return slots;
    }

    private static int key(int weekday, int pair) {
        return weekday * 10 + pair;
    }

    /** «20 пар», «18,5 пари» from week occurrences. */
    static String pairs(int occurrences) {
        if (occurrences % 2 == 1) {
            return (occurrences / 2) + ",5 пари";
        }
        return UkrainianText.pairs(occurrences / 2);
    }

    /** «3 пар», «1 пари» after «не більше». */
    private static String fridayPairs(int count) {
        return count + (count % 10 == 1 && count % 100 != 11 ? " пари" : " пар");
    }

    private static String slots(int count) {
        return count + " " + UkrainianText.plural(count, "слот", "слоти", "слотів");
    }

    public enum Kind {
        GROUP, TEACHER, ROOM_TYPE
    }

    /**
     * One reason the load does not fit ({@code problems[]} of the 422).
     *
     * @param id             group id, teacher id or room type name
     * @param requiredPairs  pairs a week the load needs (halves count ½)
     * @param availablePairs slots (places for a room type) a week
     */
    public record Problem(Kind kind, String id, String label, double requiredPairs, int availablePairs,
                          String message) {
    }

    record Result(List<Problem> problems) {

        Result {
            problems = List.copyOf(problems);
        }

        /** 422 {@code GENERATION_LOAD_EXCEEDS_SLOTS} with every problem, or nothing when the load fits. */
        void throwIfAny() {
            if (problems.isEmpty()) {
                return;
            }
            throw new BusinessRuleException(LOAD_EXCEEDS_SLOTS, ("Навантаження не вміщується в сітку занять (%d). "
                    + "Виправте навантаження, недоступність викладачів або аудиторії й запустіть генерацію ще раз.")
                    .formatted(problems.size()), Map.of("problems", problems));
        }
    }
}
