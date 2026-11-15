package ua.vtc.rozklad.scheduler.replay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.LatePairs;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.StudentGroup;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Quality numbers of a placed timetable as the generator study (genRun, night of 2026-11-15) and the core
 * metrics count them. Computed from the placements alone, independently of the solver's constraint code, so
 * a defect in a constraint shows up as a disagreement instead of being hidden by it. Counts are per kind of
 * week (numerator and denominator) unless the column says otherwise; see {@link #COLUMNS}.
 */
public record ReplayMetrics(Map<String, Long> values) {

    /** Column → meaning. Student metrics first, then teacher metrics, then rooms and stability. */
    public static final Map<String, String> COLUMNS = columns();

    private static final int NUMERATOR = 0;
    private static final int DENOMINATOR = 1;
    private static final Set<RoomType> SPORT_ROOMS = EnumSet.of(RoomType.GYM, RoomType.SPORTS_GROUND);

    private static Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("grpWin", "group windows: per group-day-week the worst cohort (core metric)");
        columns.put("grpWinH", "group windows of the hard rule: grpWin without a lone subgroup pair at the edge of the "
                + "day (no pair with every cohort at a lesson before it, or none after it), in group-days with a free unit");
        columns.put("fri4", "group-weeks with more Friday pairs than the group's limit (cohorts united), all groups");
        columns.put("friOver", "Friday pairs above the group's limit per group-week (cohorts united), in group-days with "
                + "a free unit (the hard rule)");
        columns.put("broken", "broken group-days: numerator and denominator differ in count or first pair (core)");
        columns.put("late", "group-day-weeks with a pair at the 4th position of the shift or later (LatePairs, core)");
        columns.put("grp1p", "group-day-weeks with one occupied pair, cohorts united (core studentOnePairDays)");
        columns.put("grp2p", "group-day-weeks with two occupied pairs, cohorts united (short days)");
        columns.put("spread", "per group and kind of week the most minus the fewest occupied pairs over the weekdays "
                + "of its shift (a day without lessons counts 0), summed: 0 = every day equally long");
        columns.put("coh1p", "cohort-day-weeks with one pair (genRun «student one-pair days»)");
        columns.put("lone", "group-slot-weeks where some, not all, subgroups study (genRun «without sibling»)");
        columns.put("loneScope", "lone, counted only in groups with a free unit (the scope of the run)");
        columns.put("sibApart", "week occurrences of subgroup copies of one occurrence (sibling key, one of them free) "
                + "that are not together: not in one slot and week, or, with one teacher, not on neighbouring pairs");
        columns.put("twice", "group-day-week-disciplines a student attends twice or more (busiest cohort)");
        columns.put("maxDay", "most pairs of one cohort in one day-week");
        columns.put("tchWin", "teacher windows inside a shift (core teacherWindows)");
        columns.put("shWait", "teacher free pairs between a 1st-shift and a 2nd-shift lesson (core teacherShiftWaits)");
        columns.put("idle", "tchWin + shWait: the old «teacher windows» of the genRun table");
        columns.put("tchDays", "teaching weekdays per teacher, both weeks united, summed (genRun «working days»)");
        columns.put("tchDW", "teacher-day-weeks (core teacherDays)");
        columns.put("tch1p", "teacher-day-weeks with one pair (genRun, core teacherOnePairDays)");
        columns.put("tch5p", "teacher-day-weeks with 5 or more pairs (genRun)");
        columns.put("unw", "week occurrences in a teacher's unwanted slots");
        columns.put("peOut", "units of a sport discipline outside a gym or sports ground (genRun)");
        columns.put("moved", "free units off their base slot or week");
        columns.put("roomCh", "free units in their base slot but another room");
        columns.put("unplaced", "units without a slot");
        return Collections.unmodifiableMap(columns);
    }

    /** Where one unit stands (the solver's placement, or its base placement). */
    public record Placement(LessonUnit unit, Timeslot slot, Parity parity, Room room) {

        int[] weeks() {
            return switch (parity) {
                case BOTH -> new int[] {NUMERATOR, DENOMINATOR};
                case NUMERATOR -> new int[] {NUMERATOR};
                case DENOMINATOR -> new int[] {DENOMINATOR};
            };
        }

        boolean activeIn(int week) {
            return parity == Parity.BOTH || (week == NUMERATOR) == (parity == Parity.NUMERATOR);
        }
    }

    /** The units as the solver placed them (units without a slot are left out and counted as unplaced). */
    public static List<Placement> placed(Timetable timetable) {
        return timetable.getUnits().stream()
                .filter(LessonUnit::isPlaced)
                .map(unit -> new Placement(unit, unit.getTimeslot(), unit.getParity(), unit.getRoom()))
                .toList();
    }

    /** Pinned units where they are pinned and free units at their base placement; units without a base are left out. */
    public static List<Placement> atBase(Timetable timetable) {
        Map<UUID, Room> rooms = timetable.getRooms().stream().collect(Collectors.toMap(Room::getId, room -> room));
        List<Placement> placements = new ArrayList<>();
        for (LessonUnit unit : timetable.getUnits()) {
            if (unit.isPinned()) {
                placements.add(new Placement(unit, unit.getTimeslot(), unit.getParity(), unit.getRoom()));
            } else if (unit.hasBase()) {
                Room room = unit.getBaseRoomId() == null ? Room.NONE : rooms.getOrDefault(unit.getBaseRoomId(), Room.NONE);
                placements.add(new Placement(unit, unit.getBaseSlot(), unit.getBaseParity(), room));
            }
        }
        return placements;
    }

    public long get(String column) {
        return values.getOrDefault(column, 0L);
    }

    /**
     * @param units            every unit of the problem (subgroups of a group are taken from all of them, B4)
     * @param placements       where the units stand
     * @param sportDisciplines disciplines that belong in a gym or on a sports ground (data-driven, see
     *                         {@link ReplaySnapshots#sportDisciplines})
     */
    public static ReplayMetrics of(List<LessonUnit> units, List<Placement> placements, Set<UUID> sportDisciplines) {
        Map<String, Long> values = new LinkedHashMap<>();
        COLUMNS.keySet().forEach(column -> values.put(column, 0L));
        Map<UUID, SortedSet<Integer>> subgroups = new HashMap<>();
        for (LessonUnit unit : units) {
            SortedSet<Integer> set = subgroups.computeIfAbsent(unit.getGroupId(), id -> new TreeSet<>());
            if (unit.getSubgroup() > 0) {
                set.add(unit.getSubgroup());
            }
        }
        groupMetrics(values, placements, subgroups);
        teacherMetrics(values, placements);
        siblingMetrics(values, placements);
        for (Placement placement : placements) {
            LessonUnit unit = placement.unit();
            if (unit.hasTeacher() && unit.getTeacher().isUnwantedAt(placement.slot())) {
                add(values, "unw", placement.weeks().length);
            }
            boolean sport = sportDisciplines.contains(unit.getDisciplineId())
                    || SPORT_ROOMS.contains(unit.getRequiredRoomType());
            if (sport && !placement.room().isNone() && !SPORT_ROOMS.contains(placement.room().getType())) {
                add(values, "peOut", 1);
            }
            if (!unit.isPinned() && unit.hasBase()) {
                boolean moved = !unit.getBaseSlot().equals(placement.slot()) || unit.getBaseParity() != placement.parity();
                if (moved) {
                    add(values, "moved", 1);
                } else if (unit.getBaseRoomId() != null && !placement.room().isNone()
                        && !unit.getBaseRoomId().equals(placement.room().getId())) {
                    add(values, "roomCh", 1);
                }
            }
        }
        Set<String> placedIds = placements.stream().map(placement -> placement.unit().getId()).collect(Collectors.toSet());
        values.put("unplaced", units.stream().filter(unit -> !placedIds.contains(unit.getId())).count());
        values.put("idle", values.get("tchWin") + values.get("shWait"));
        return new ReplayMetrics(values);
    }

    private static void groupMetrics(Map<String, Long> values, List<Placement> placements,
                                     Map<UUID, SortedSet<Integer>> subgroupsByGroup) {
        Map<UUID, Map<Integer, List<Placement>>> byGroupDay = new HashMap<>();
        for (Placement placement : placements) {
            byGroupDay.computeIfAbsent(placement.unit().getGroupId(), id -> new HashMap<>())
                    .computeIfAbsent(placement.slot().weekday(), day -> new ArrayList<>()).add(placement);
        }
        long maxDay = 0;
        for (Map.Entry<UUID, Map<Integer, List<Placement>>> group : byGroupDay.entrySet()) {
            addSpread(values, group.getValue());
            SortedSet<Integer> subgroups = subgroupsByGroup.getOrDefault(group.getKey(), new TreeSet<>());
            List<Integer> cohorts = subgroups.isEmpty() ? List.of(0) : List.copyOf(subgroups);
            for (Map.Entry<Integer, List<Placement>> dayEntry : group.getValue().entrySet()) {
                int weekday = dayEntry.getKey();
                List<Placement> day = dayEntry.getValue();
                StudentGroup studentGroup = day.getFirst().unit().getGroup();
                boolean dayInScope = day.stream().anyMatch(placement -> !placement.unit().isPinned());
                List<SortedSet<Integer>> occupiedByWeek = new ArrayList<>();
                for (int week = NUMERATOR; week <= DENOMINATOR; week++) {
                    int currentWeek = week;
                    List<Placement> active = day.stream().filter(placement -> placement.activeIn(currentWeek)).toList();
                    SortedSet<Integer> occupied = pairs(active);
                    occupiedByWeek.add(occupied);
                    long worstWindows = 0;
                    long worstHardWindows = 0;
                    Map<UUID, Integer> busiestDiscipline = new HashMap<>();
                    SortedSet<Integer> full = fullPairs(active, cohorts, occupied);
                    for (int cohort : cohorts) {
                        List<Placement> attended = active.stream()
                                .filter(placement -> placement.unit().getSubgroup() == 0
                                        || placement.unit().getSubgroup() == cohort)
                                .toList();
                        SortedSet<Integer> attendedPairs = pairs(attended);
                        worstWindows = Math.max(worstWindows, gaps(attendedPairs));
                        worstHardWindows = Math.max(worstHardWindows, hardGaps(attendedPairs, occupied, full));
                        maxDay = Math.max(maxDay, attendedPairs.size());
                        if (attendedPairs.size() == 1) {
                            add(values, "coh1p", 1);
                        }
                        Map<UUID, Set<Integer>> disciplinePairs = new HashMap<>();
                        attended.forEach(placement -> disciplinePairs
                                .computeIfAbsent(placement.unit().getDisciplineId(), id -> new HashSet<>())
                                .add(placement.slot().pair()));
                        disciplinePairs.forEach((discipline, set) ->
                                busiestDiscipline.merge(discipline, set.size(), Math::max));
                    }
                    add(values, "grpWin", worstWindows);
                    add(values, "grpWinH", dayInScope ? worstHardWindows : 0);
                    Integer fridayLimit = studentGroup.getFridayMaxPairs();
                    if (weekday == StudentGroup.FRIDAY && fridayLimit != null && occupied.size() > fridayLimit) {
                        add(values, "fri4", 1);
                        add(values, "friOver", dayInScope ? occupied.size() - fridayLimit : 0);
                    }
                    if (occupied.size() == 1) {
                        add(values, "grp1p", 1);
                    } else if (occupied.size() == 2) {
                        add(values, "grp2p", 1);
                    }
                    int firstPair = studentGroup.getWindow().firstPairOn(weekday);
                    if (occupied.stream().anyMatch(pair -> LatePairs.isLate(pair, firstPair))) {
                        add(values, "late", 1);
                    }
                    add(values, "twice", busiestDiscipline.values().stream().filter(count -> count >= 2).count());
                    if (!subgroups.isEmpty()) {
                        boolean inScope = hasFreeUnit(group.getValue());
                        for (int pair : occupied) {
                            Set<Integer> present = active.stream().filter(placement -> placement.slot().pair() == pair)
                                    .map(placement -> placement.unit().getSubgroup()).collect(Collectors.toSet());
                            if (!present.contains(0) && !present.equals(subgroups)) {
                                add(values, "lone", 1);
                                add(values, "loneScope", inScope ? 1 : 0);
                            }
                        }
                    }
                }
                if (isBroken(occupiedByWeek.get(NUMERATOR), occupiedByWeek.get(DENOMINATOR))) {
                    add(values, "broken", 1);
                }
            }
        }
        values.put("maxDay", maxDay);
    }

    /** Most minus fewest occupied pairs over the weekdays of the group's shift, per kind of week. */
    private static void addSpread(Map<String, Long> values, Map<Integer, List<Placement>> days) {
        StudentGroup group = days.values().iterator().next().getFirst().unit().getGroup();
        for (int week = NUMERATOR; week <= DENOMINATOR; week++) {
            int currentWeek = week;
            int most = 0;
            int fewest = Integer.MAX_VALUE;
            for (int weekday = 1; weekday <= Timeslot.MAX_WEEKDAY; weekday++) {
                if (group.getWindow().pairsOn(weekday) == 0) {
                    continue;
                }
                int pairs = pairs(days.getOrDefault(weekday, List.of()).stream()
                        .filter(placement -> placement.activeIn(currentWeek)).toList()).size();
                most = Math.max(most, pairs);
                fewest = Math.min(fewest, pairs);
            }
            if (fewest != Integer.MAX_VALUE) {
                add(values, "spread", most - fewest);
            }
        }
    }

    private static boolean hasFreeUnit(Map<Integer, List<Placement>> days) {
        return days.values().stream().flatMap(List::stream).anyMatch(placement -> !placement.unit().isPinned());
    }

    /**
     * Subgroup copies of one weekly occurrence (equal sibling key) that are not together: with different teachers
     * they must share the slot and the week, with one teacher they must stand on neighbouring pairs of one day in
     * the same week. In a key that cannot be parallel by the data (soft sibling rule) neighbouring pairs are also
     * together for two teachers. Pairs where both copies are pinned are left out, like the hard rule does.
     */
    private static void siblingMetrics(Map<String, Long> values, List<Placement> placements) {
        Map<String, List<Placement>> byKey = new HashMap<>();
        for (Placement placement : placements) {
            if (placement.unit().hasSiblingKey()) {
                byKey.computeIfAbsent(placement.unit().getSiblingKey(), key -> new ArrayList<>()).add(placement);
            }
        }
        for (List<Placement> siblings : byKey.values()) {
            for (int i = 0; i < siblings.size(); i++) {
                for (int j = i + 1; j < siblings.size(); j++) {
                    Placement a = siblings.get(i);
                    Placement b = siblings.get(j);
                    if (a.unit().isPinned() && b.unit().isPinned()) {
                        continue;
                    }
                    boolean oneTeacher = a.unit().hasTeacher() && a.unit().getTeacherId().equals(b.unit().getTeacherId());
                    boolean adjacent = a.slot().weekday() == b.slot().weekday()
                            && Math.abs(a.slot().pair() - b.slot().pair()) == 1;
                    boolean soft = a.unit().hasSoftSiblingRule() || b.unit().hasSoftSiblingRule();
                    boolean together = a.parity() == b.parity()
                            && (oneTeacher ? adjacent : a.slot().equals(b.slot()) || soft && adjacent);
                    if (!together) {
                        add(values, "sibApart", Math.max(a.weeks().length, b.weeks().length));
                    }
                }
            }
        }
    }

    private static void teacherMetrics(Map<String, Long> values, List<Placement> placements) {
        Map<UUID, Map<Integer, List<Placement>>> byTeacherDay = new HashMap<>();
        for (Placement placement : placements) {
            if (placement.unit().hasTeacher()) {
                byTeacherDay.computeIfAbsent(placement.unit().getTeacherId(), id -> new HashMap<>())
                        .computeIfAbsent(placement.slot().weekday(), day -> new ArrayList<>()).add(placement);
            }
        }
        for (Map<Integer, List<Placement>> days : byTeacherDay.values()) {
            add(values, "tchDays", days.size());
            for (List<Placement> day : days.values()) {
                for (int week = NUMERATOR; week <= DENOMINATOR; week++) {
                    int currentWeek = week;
                    List<Placement> active = day.stream().filter(placement -> placement.activeIn(currentWeek)).toList();
                    SortedSet<Integer> pairs = pairs(active);
                    if (pairs.isEmpty()) {
                        continue;
                    }
                    add(values, "tchDW", 1);
                    if (pairs.size() == 1) {
                        add(values, "tch1p", 1);
                    }
                    if (pairs.size() >= 5) {
                        add(values, "tch5p", 1);
                    }
                    Integer previous = null;
                    for (int pair : pairs) {
                        if (previous != null && pair - previous > 1) {
                            int gap = pair - previous - 1;
                            Integer before = singleShift(active, previous);
                            Integer after = singleShift(active, pair);
                            add(values, before != null && after != null && !before.equals(after) ? "shWait" : "tchWin",
                                    gap);
                        }
                        previous = pair;
                    }
                }
            }
        }
    }

    /** The shift all lessons at the pair belong to, {@code null} when they are of several shifts. */
    private static Integer singleShift(List<Placement> active, int pair) {
        Set<Integer> shifts = active.stream().filter(placement -> placement.slot().pair() == pair)
                .map(placement -> placement.unit().getShift()).collect(Collectors.toSet());
        return shifts.size() == 1 ? shifts.iterator().next() : null;
    }

    private static boolean isBroken(SortedSet<Integer> numerator, SortedSet<Integer> denominator) {
        if (numerator.isEmpty() && denominator.isEmpty()) {
            return false;
        }
        if (numerator.size() != denominator.size()) {
            return true;
        }
        return !numerator.first().equals(denominator.first());
    }

    private static SortedSet<Integer> pairs(List<Placement> placements) {
        return placements.stream().map(placement -> placement.slot().pair()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static long gaps(SortedSet<Integer> pairs) {
        return pairs.size() < 2 ? 0 : pairs.last() - pairs.first() + 1 - pairs.size();
    }

    /** Occupied pairs at which every cohort has a lesson (whole group, or all subgroups in parallel). */
    private static SortedSet<Integer> fullPairs(List<Placement> active, List<Integer> cohorts,
                                                SortedSet<Integer> occupied) {
        SortedSet<Integer> full = new TreeSet<>();
        for (int pair : occupied) {
            Set<Integer> present = active.stream().filter(placement -> placement.slot().pair() == pair)
                    .map(placement -> placement.unit().getSubgroup()).collect(Collectors.toSet());
            if (present.contains(0) || present.containsAll(cohorts)) {
                full.add(pair);
            }
        }
        return full;
    }

    /**
     * Free pairs of a cohort between its lessons, without the lone subgroup pairs at the edge of the day: a pair where
     * another cohort studies and no full pair lies before it, or none after it.
     */
    private static long hardGaps(SortedSet<Integer> attended, SortedSet<Integer> occupied, SortedSet<Integer> full) {
        if (attended.size() < 2) {
            return 0;
        }
        long count = 0;
        for (int pair = attended.first() + 1; pair < attended.last(); pair++) {
            if (attended.contains(pair)) {
                continue;
            }
            boolean loneAtTheEdge = occupied.contains(pair)
                    && (full.isEmpty() || pair < full.first() || pair > full.last());
            if (!loneAtTheEdge) {
                count++;
            }
        }
        return count;
    }

    private static void add(Map<String, Long> values, String column, long amount) {
        values.merge(column, amount, Long::sum);
    }
}
