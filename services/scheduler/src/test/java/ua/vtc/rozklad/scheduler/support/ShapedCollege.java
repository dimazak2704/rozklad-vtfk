package ua.vtc.rozklad.scheduler.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Synthetic colleges shaped like the real one (seed Excel 2025/26, genModel statistics), in several
 * {@link Shape}s that stress one feature each: a full second shift, many split subgroups, teachers with
 * UNAVAILABLE and unwanted slots and part-time teachers, a small college with a pinned stream and a gym.
 *
 * <p>Like {@link SyntheticCollege}, the generator plants a complete timetable without hard violations first and
 * then forgets it, so each problem is known to be feasible. Unlike it, a group gets the real number of pairs a
 * week (14–19, a pair of halves counts as one), its days start at the shift's first pair as a rule and Friday is
 * the lightest day (at most the college's Friday limit of 3 pairs, which the snapshot carries); its days have no
 * windows; each load item has one teacher for all its occurrences; split items are parallel (one
 * teacher per subgroup) or crossed (one teacher teaches both subgroups on neighbouring pairs, like a lab); every
 * teacher has a cabinet (the desired room) and the college's daily limit of 4 pairs.
 */
public final class ShapedCollege {

    static final int MAX_PAIRS_PER_DAY = 4;
    /** The college's Friday limit of a group (core setting {@code generation.fridayMaxPairs}); the plant keeps to it. */
    static final int FRIDAY_MAX_PAIRS = 3;
    static final int TEACHERS_PER_CABINET = 2;
    static final int PART_TIME_LOAD = 8;

    private static final String[] DISCIPLINES = {
            "Фізична культура", "Математика", "Українська мова", "Українська література", "Іноземна мова",
            "Історія України", "Фізика", "Хімія", "Інформатика", "Біологія", "Географія", "Громадянська освіта",
            "Захист України", "Електротехніка", "Програмування", "Комп'ютерні мережі", "Економіка",
            "Бухгалтерський облік", "Охорона праці", "Креслення", "Метрологія", "Матеріалознавство", "Радіотехніка",
            "Менеджмент", "Правознавство", "Соціологія", "Психологія"};
    private static final String STREAM_DISCIPLINE = "Лекція потоку";
    private static final String[] LETTERS = {"КІ", "ЕА", "РТ", "ТК", "ОП", "ВЕ", "ОБ", "Ф", "М", "ОД", "ЕП"};
    private static final int NUMERATOR = 0;
    private static final int DENOMINATOR = 1;

    /**
     * Shape of a synthetic college.
     *
     * @param minPairs           fewest pairs a week of a 1st-shift group ({@code maxPairs} the most)
     * @param splitShare         share of groups with two subgroups
     * @param parallelSplits     a split group gets 1..n parallel subgroup slots (two teachers, one slot)
     * @param crossShare         share of split groups with a crossed pair (one teacher, both subgroups, neighbouring pairs)
     * @param labShare           share of crossed items that need a computer lab
     * @param halves             a group gets 1..n slots with a numerator and a denominator half
     * @param peRequiresGym      PE units require the gym (core derives it from a base, B11); otherwise only the
     *                           PE teacher's room is the gym, as in a request from scratch
     * @param unavailableShare   share of teachers with UNAVAILABLE slots (a free weekday or single pairs)
     * @param unwantedShare      share of teachers with unwanted slots (some of them where the plant uses them)
     * @param partTimeTeachers   teachers who work only two weekdays (the other days UNAVAILABLE)
     * @param streams            pinned streams of two groups (leader with teacher and room, follower without)
     * @param spareRooms         classrooms above what the plant needs
     */
    public record Shape(String name, int shiftOneGroups, int shiftTwoGroups, int minPairs, int maxPairs,
                        int minPairsShiftTwo, int maxPairsShiftTwo, double splitShare, int parallelSplits,
                        double crossShare, double labShare, int halves, boolean peRequiresGym, int gymPlaces,
                        int teacherLoad, double unavailableShare, double unwantedShare, int partTimeTeachers,
                        int streams, int spareRooms, long seed, long defaultSeconds) {

        /** The real college: 41 + 15 groups, 14–19 pairs a week, about half of the groups split. */
        public static Shape realShaped() {
            return new Shape("real-shaped", 41, 15, 14, 19, 14, 19, 0.55, 2, 0.15, 0.0, 2, true, 5, 20, 0.1, 0.2, 0,
                    0, 2, 2026, 180);
        }

        /** A full second shift: 12 groups with 18–19 pairs for 19 slots, a gym with 2 places. */
        public static Shape tightShiftTwo() {
            return new Shape("tight-shift2", 8, 12, 14, 17, 18, 19, 0.5, 2, 0.2, 0.0, 2, true, 2, 20, 0.1, 0.2, 0, 0,
                    1, 11, 120);
        }

        /** Every group split: up to 3 parallel subgroup slots and, for 60 % of them, a crossed pair (half in labs). */
        public static Shape manySplits() {
            return new Shape("many-splits", 18, 6, 15, 18, 15, 18, 1.0, 3, 0.6, 0.5, 1, true, 3, 20, 0.1, 0.2, 0, 0,
                    1, 12, 120);
        }

        /** 40 % of teachers with UNAVAILABLE slots, half with unwanted ones, two part-time teachers. */
        public static Shape teacherLimits() {
            return new Shape("teacher-limits", 18, 6, 15, 18, 15, 18, 0.5, 2, 0.2, 0.0, 2, true, 3, 20, 0.4, 0.5, 2,
                    0, 1, 13, 120);
        }

        /** Six groups from scratch with two pinned streams; PE does not require the gym (no base to derive it from). */
        public static Shape smallStream() {
            return new Shape("small-stream", 4, 2, 14, 18, 14, 18, 0.5, 1, 0.5, 0.0, 1, false, 2, 20, 0.1, 0.2, 0, 2,
                    1, 14, 60);
        }

        /** The same shape with another random seed (another college of the same shape). */
        public Shape withSeed(long otherSeed) {
            return new Shape(name, shiftOneGroups, shiftTwoGroups, minPairs, maxPairs, minPairsShiftTwo,
                    maxPairsShiftTwo, splitShare, parallelSplits, crossShare, labShare, halves, peRequiresGym,
                    gymPlaces, teacherLoad, unavailableShare, unwantedShare, partTimeTeachers, streams, spareRooms,
                    otherSeed, defaultSeconds);
        }

        public static List<Shape> all() {
            return List.of(realShaped(), tightShiftTwo(), manySplits(), teacherLimits(), smallStream());
        }

        public static Shape named(String name) {
            return all().stream().filter(shape -> shape.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown shape " + name));
        }
    }

    /** The problem as core would send it from scratch, plus the planted feasible placement of every unit. */
    public record Problem(ProblemSnapshot snapshot, List<Assignment> planted) {

        /** The same problem with the planted timetable as the base version (warm start and stability). */
        public ProblemSnapshot withInitial() {
            Map<String, Assignment> byUnit = new HashMap<>();
            planted.forEach(assignment -> byUnit.put(assignment.unitId(), assignment));
            List<ProblemSnapshot.Unit> units = snapshot.units().stream().map(unit -> {
                if (unit.pinned() != null) {
                    return unit;
                }
                Assignment placed = byUnit.get(unit.unitId());
                ProblemSnapshot.PinnedSlot initial = new ProblemSnapshot.PinnedSlot(placed.weekday(), placed.pair(),
                        placed.parity(), placed.roomId());
                return new ProblemSnapshot.Unit(unit.unitId(), unit.loadItemId(), unit.groupId(), unit.subgroup(),
                        unit.disciplineId(), unit.teacherId(), unit.weight(), unit.requiredRoomType(),
                        unit.preferredRoomId(), unit.homeRoomId(), null, initial, unit.teacherRoomId(), unit.online(),
                        unit.siblingKey());
            }).toList();
            return new ProblemSnapshot(snapshot.timeslots(), snapshot.shiftWindows(), snapshot.groups(),
                    snapshot.rooms(), snapshot.teachers(), units);
        }

        /** Puts every unit of a timetable built from {@link #snapshot()} where the plant has it. */
        public Timetable place(Timetable timetable) {
            return Placements.apply(timetable, planted);
        }
    }

    private ShapedCollege() {
    }

    public static Problem generate(Shape shape) {
        return new Generator(shape).run();
    }

    private record Slot(int weekday, int pair) {
    }

    /** A slot in one kind of week. */
    private record SlotWeek(int weekday, int pair, int week) {
    }

    private static List<SlotWeek> weeksOf(Slot slot, Parity parity) {
        return switch (parity) {
            case BOTH -> List.of(new SlotWeek(slot.weekday(), slot.pair(), NUMERATOR),
                    new SlotWeek(slot.weekday(), slot.pair(), DENOMINATOR));
            case NUMERATOR -> List.of(new SlotWeek(slot.weekday(), slot.pair(), NUMERATOR));
            case DENOMINATOR -> List.of(new SlotWeek(slot.weekday(), slot.pair(), DENOMINATOR));
        };
    }

    /** One occurrence of a load item: where and in which weeks. */
    private record Occurrence(Slot slot, Parity parity) {
    }

    private static final class RoomState {
        final UUID id;
        final String name;
        final RoomType type;
        final int capacity;
        final int maxParallel;
        final Map<SlotWeek, Integer> use = new HashMap<>();
        int cabinetOf;

        RoomState(UUID id, String name, RoomType type, int capacity, int maxParallel) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.capacity = capacity;
            this.maxParallel = maxParallel;
        }

        boolean isFree(Slot slot, Parity parity) {
            return weeksOf(slot, parity).stream().allMatch(week -> use.getOrDefault(week, 0) < maxParallel);
        }

        void occupy(Slot slot, Parity parity) {
            weeksOf(slot, parity).forEach(week -> use.merge(week, 1, Integer::sum));
        }
    }

    private static final class TeacherState {
        final UUID id;
        final String name;
        final RoomState cabinet;
        final Set<Integer> allowedDays;
        final int maxLoad;
        final Set<SlotWeek> busy = new HashSet<>();
        int load;

        TeacherState(UUID id, String name, RoomState cabinet, Set<Integer> allowedDays, int maxLoad) {
            this.id = id;
            this.name = name;
            this.cabinet = cabinet;
            this.allowedDays = allowedDays;
            this.maxLoad = maxLoad;
        }

        boolean canTeach(List<Occurrence> occurrences) {
            if (load + occurrences.size() > maxLoad) {
                return false;
            }
            Map<Integer, Integer> perDayWeek = new HashMap<>();
            for (Occurrence occurrence : occurrences) {
                if (!allowedDays.contains(occurrence.slot().weekday())) {
                    return false;
                }
                for (SlotWeek week : weeksOf(occurrence.slot(), occurrence.parity())) {
                    if (busy.contains(week)) {
                        return false;
                    }
                    int key = week.weekday() * 2 + week.week();
                    int pairs = perDayWeek.merge(key, 1, Integer::sum) + pairsOn(week.weekday(), week.week());
                    if (pairs > MAX_PAIRS_PER_DAY) {
                        return false;
                    }
                }
            }
            return true;
        }

        int pairsOn(int weekday, int week) {
            return (int) busy.stream().filter(slot -> slot.weekday() == weekday && slot.week() == week).count();
        }

        boolean worksOn(int weekday) {
            return busy.stream().anyMatch(slot -> slot.weekday() == weekday);
        }

        void take(List<Occurrence> occurrences) {
            for (Occurrence occurrence : occurrences) {
                busy.addAll(weeksOf(occurrence.slot(), occurrence.parity()));
            }
            load += occurrences.size();
        }
    }

    private enum Role { PE, HALVES, PARALLEL, CROSS_FIRST, CROSS_SECOND, STREAM, WHOLE }

    /** A stream: two groups of one shift at the first pair of the window on Tuesday, pinned. */
    private record StreamPlan(int index, int shift, Slot slot) {
    }

    private static final class Generator {

        private final Shape shape;
        private final Random random;
        private final List<ProblemSnapshot.Timeslot> timeslots = new ArrayList<>();
        private final List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        private final List<ProblemSnapshot.Group> groups = new ArrayList<>();
        private final List<RoomState> classrooms = new ArrayList<>();
        private final List<RoomState> labs = new ArrayList<>();
        private final RoomState gym;
        private final List<UUID> disciplines = new ArrayList<>();
        private final Map<UUID, List<TeacherState>> teachersByDiscipline = new HashMap<>();
        private final List<TeacherState> teachers = new ArrayList<>();
        private final List<TeacherState> partTime = new ArrayList<>();
        private final List<ProblemSnapshot.Unit> units = new ArrayList<>();
        private final List<Assignment> planted = new ArrayList<>();
        private final Map<UUID, Integer> occurrenceCounter = new HashMap<>();
        private RoomState hall;
        private int idCounter;

        Generator(Shape shape) {
            this.shape = shape;
            this.random = new Random(shape.seed());
            for (int weekday = 1; weekday <= 5; weekday++) {
                int pairs = weekday == 5 ? 6 : 8;
                for (int pair = 1; pair <= pairs; pair++) {
                    timeslots.add(new ProblemSnapshot.Timeslot(weekday, pair));
                }
                windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
                windows.add(weekday == 5 ? new ProblemSnapshot.ShiftWindow(2, weekday, 4, 6)
                        : new ProblemSnapshot.ShiftWindow(2, weekday, 5, 8));
            }
            for (String name : DISCIPLINES) {
                disciplines.add(uuid("discipline-" + name));
            }
            gym = new RoomState(uuid("room-gym"), "с.з.", RoomType.GYM, 90, shape.gymPlaces());
        }

        Problem run() {
            for (int i = 0; i < shape.shiftOneGroups() + shape.spareRooms(); i++) {
                classrooms.add(newClassroom());
            }
            for (int i = 0; i < shape.partTimeTeachers(); i++) {
                List<Integer> days = new ArrayList<>(List.of(1, 2, 3, 4, 5));
                Collections.shuffle(days, random);
                UUID discipline = disciplines.get(1 + random.nextInt(disciplines.size() - 1));
                TeacherState teacher = newTeacher(teachersByDiscipline.computeIfAbsent(discipline, key -> new ArrayList<>()),
                        Set.copyOf(days.subList(0, 2)), PART_TIME_LOAD);
                partTime.add(teacher);
            }
            int total = shape.shiftOneGroups() + shape.shiftTwoGroups();
            Map<Integer, StreamPlan> streamOf = new HashMap<>();
            for (int stream = 0; stream < shape.streams(); stream++) {
                // потік k: групи 2k і 2k+1; другий потік — у другій зміні, якщо там є дві групи
                int shift = stream % 2 == 1 && shape.shiftTwoGroups() >= 2 ? 2 : 1;
                int first = shift == 2 ? 0 : shape.shiftTwoGroups() + 2 * (stream / 2);
                StreamPlan plan = new StreamPlan(stream, shift, new Slot(2, firstPair(shift, 2)));
                streamOf.put(first, plan);
                streamOf.put(first + 1, plan);
            }
            for (int index = 0; index < total; index++) {
                plantGroup(index, index < shape.shiftTwoGroups() ? 2 : 1, streamOf.get(index));
            }
            List<ProblemSnapshot.Room> rooms = new ArrayList<>();
            rooms.add(snapshotRoom(gym));
            classrooms.forEach(room -> rooms.add(snapshotRoom(room)));
            labs.forEach(room -> rooms.add(snapshotRoom(room)));
            if (hall != null) {
                rooms.add(snapshotRoom(hall));
            }
            return new Problem(new ProblemSnapshot(timeslots, windows, groups, rooms, teacherFacts(), units), planted);
        }

        private void plantGroup(int index, int shift, StreamPlan stream) {
            boolean split = random.nextDouble() < shape.splitShare();
            int course = shift == 2 ? 2 : new int[] {1, 3, 4}[index % 3];
            String code = "%d%s%d".formatted(course, LETTERS[index % LETTERS.length], 1 + index / LETTERS.length);
            UUID groupId = uuid("group-" + index);
            groups.add(new ProblemSnapshot.Group(groupId, code, 18 + random.nextInt(13), shift,
                    split ? List.of(1, 2) : List.of(), FRIDAY_MAX_PAIRS));
            int min = shift == 1 ? shape.minPairs() : shape.minPairsShiftTwo();
            int max = shift == 1 ? shape.maxPairs() : shape.maxPairsShiftTwo();
            List<Slot> slots = chooseSlots(shift, min + random.nextInt(max - min + 1), stream);
            Map<Slot, Role> roles = assignRoles(slots, split, stream);

            List<UUID> curriculum = new ArrayList<>(disciplines.subList(1, disciplines.size()));
            Collections.shuffle(curriculum, random);
            UUID parallel = curriculum.get(0);
            UUID crossA = curriculum.get(1);
            UUID crossB = curriculum.get(2);
            UUID halfA = curriculum.get(3);
            UUID halfB = curriculum.get(4);
            boolean crossLab = random.nextDouble() < shape.labShare();

            Map<String, List<Occurrence>> items = new LinkedHashMap<>();
            Map<String, UUID> itemDiscipline = new HashMap<>();
            Map<String, Integer> itemSubgroup = new HashMap<>();
            List<Slot> wholeSlots = new ArrayList<>();
            for (Slot slot : sorted(roles.keySet())) {
                switch (roles.get(slot)) {
                    case PE -> item(items, itemDiscipline, itemSubgroup, "pe", disciplines.get(0), 0,
                            new Occurrence(slot, Parity.BOTH));
                    case HALVES -> {
                        item(items, itemDiscipline, itemSubgroup, "halfA", halfA, 0, new Occurrence(slot, Parity.NUMERATOR));
                        item(items, itemDiscipline, itemSubgroup, "halfB", halfB, 0,
                                new Occurrence(slot, Parity.DENOMINATOR));
                    }
                    case PARALLEL -> {
                        item(items, itemDiscipline, itemSubgroup, "par1", parallel, 1, new Occurrence(slot, Parity.BOTH));
                        item(items, itemDiscipline, itemSubgroup, "par2", parallel, 2, new Occurrence(slot, Parity.BOTH));
                    }
                    case CROSS_FIRST -> {
                        item(items, itemDiscipline, itemSubgroup, "crossA1", crossA, 1, new Occurrence(slot, Parity.BOTH));
                        item(items, itemDiscipline, itemSubgroup, "crossB2", crossB, 2, new Occurrence(slot, Parity.BOTH));
                    }
                    case CROSS_SECOND -> {
                        item(items, itemDiscipline, itemSubgroup, "crossA2", crossA, 2, new Occurrence(slot, Parity.BOTH));
                        item(items, itemDiscipline, itemSubgroup, "crossB1", crossB, 1, new Occurrence(slot, Parity.BOTH));
                    }
                    case STREAM -> plantStreamUnit(groupId, stream);
                    case WHOLE -> wholeSlots.add(slot);
                }
            }
            Map<Slot, UUID> whole = wholeDisciplines(wholeSlots, roles, curriculum.subList(5, curriculum.size()),
                    Map.of(parallel, true, crossA, true, crossB, true, halfA, true, halfB, true));
            whole.forEach((slot, discipline) -> item(items, itemDiscipline, itemSubgroup, "w-" + discipline,
                    discipline, 0, new Occurrence(slot, Parity.BOTH)));

            // викладач: один на позицію навантаження; у схрещеній парі обидві підгрупи веде той самий
            Map<String, TeacherState> teacherOf = new HashMap<>();
            for (Map.Entry<String, List<Occurrence>> entry : items.entrySet()) {
                String key = entry.getKey();
                if (key.equals("crossA2") || key.equals("crossB1")) {
                    continue;
                }
                List<Occurrence> occurrences = new ArrayList<>(entry.getValue());
                String partner = key.equals("crossA1") ? "crossA2" : key.equals("crossB2") ? "crossB1" : null;
                if (partner != null) {
                    occurrences.addAll(items.get(partner));
                }
                TeacherState teacher = teacherFor(itemDiscipline.get(key), occurrences, key.equals("pe"));
                teacherOf.put(key, teacher);
                if (partner != null) {
                    teacherOf.put(partner, teacher);
                }
            }
            for (Map.Entry<String, List<Occurrence>> entry : items.entrySet()) {
                String key = entry.getKey();
                UUID discipline = itemDiscipline.get(key);
                int subgroup = itemSubgroup.get(key);
                boolean splitItem = subgroup > 0;
                UUID loadItemId = uuid("load-" + groupId + "-" + discipline + (splitItem ? "-split" : "-" + key));
                RoomType required = key.equals("pe") && shape.peRequiresGym() ? RoomType.GYM
                        : key.startsWith("cross") && crossLab ? RoomType.COMPUTER_LAB : null;
                for (Occurrence occurrence : entry.getValue()) {
                    String siblingKey = null;
                    if (splitItem) {
                        // ключ копій одного заняття: перше заняття підгрупи — w1, друге — w2 (як core)
                        String counterKey = groupId + "-" + discipline + "-" + subgroup;
                        int nth = occurrenceCounter.merge(uuid(counterKey), 1, Integer::sum);
                        siblingKey = loadItemId + ":w" + nth;
                    }
                    plantUnit(groupId, subgroup, discipline, loadItemId, teacherOf.get(key), occurrence, required,
                            siblingKey);
                }
            }
        }

        private static void item(Map<String, List<Occurrence>> items, Map<String, UUID> disciplines,
                                 Map<String, Integer> subgroups, String key, UUID discipline, int subgroup,
                                 Occurrence occurrence) {
            items.computeIfAbsent(key, k -> new ArrayList<>()).add(occurrence);
            disciplines.put(key, discipline);
            subgroups.put(key, subgroup);
        }

        /**
         * The group's slots: days from the first pair of the window (a fifth of the shorter days start one pair
         * later), as long as the window allows; pairs are taken off Friday first (down to 2), then off the
         * longest other days.
         */
        private List<Slot> chooseSlots(int shift, int pairs, StreamPlan stream) {
            int[] lengths = new int[6];
            int capacity = 0;
            for (int weekday = 1; weekday <= 5; weekday++) {
                lengths[weekday] = lastPair(shift, weekday) - firstPair(shift, weekday) + 1;
                capacity += lengths[weekday];
            }
            int excess = Math.max(0, capacity - pairs);
            while (excess > 0 && lengths[5] > 2) {
                lengths[5]--;
                excess--;
            }
            for (int floor = 2; excess > 0 && floor >= 1; floor--) {
                while (excess > 0) {
                    int longest = 0;
                    List<Integer> candidates = new ArrayList<>();
                    for (int weekday = 1; weekday <= 4; weekday++) {
                        if (lengths[weekday] > floor) {
                            if (lengths[weekday] > longest) {
                                longest = lengths[weekday];
                                candidates.clear();
                            }
                            if (lengths[weekday] == longest) {
                                candidates.add(weekday);
                            }
                        }
                    }
                    if (candidates.isEmpty()) {
                        break;
                    }
                    lengths[candidates.get(random.nextInt(candidates.size()))]--;
                    excess--;
                }
            }
            List<Slot> slots = new ArrayList<>();
            for (int weekday = 1; weekday <= 5; weekday++) {
                int first = firstPair(shift, weekday);
                int last = lastPair(shift, weekday);
                boolean streamDay = stream != null && stream.slot().weekday() == weekday;
                int start = !streamDay && first + lengths[weekday] <= last && random.nextInt(5) == 0 ? first + 1 : first;
                for (int pair = start; pair < start + lengths[weekday]; pair++) {
                    slots.add(new Slot(weekday, pair));
                }
            }
            return slots;
        }

        private Map<Slot, Role> assignRoles(List<Slot> slots, boolean split, StreamPlan stream) {
            Map<Slot, Role> roles = new HashMap<>();
            List<Slot> free = new ArrayList<>(slots);
            Collections.shuffle(free, random);
            if (stream != null) {
                roles.put(stream.slot(), Role.STREAM);
                free.remove(stream.slot());
            }
            if (split && random.nextDouble() < shape.crossShare()) {
                for (Slot slot : List.copyOf(free)) {
                    Slot next = new Slot(slot.weekday(), slot.pair() + 1);
                    if (free.contains(next)) {
                        roles.put(slot, Role.CROSS_FIRST);
                        roles.put(next, Role.CROSS_SECOND);
                        free.remove(slot);
                        free.remove(next);
                        break;
                    }
                }
            }
            free.stream().filter(slot -> gym.isFree(slot, Parity.BOTH)).findFirst().ifPresent(slot -> {
                roles.put(slot, Role.PE);
                gym.occupy(slot, Parity.BOTH);
                free.remove(slot);
            });
            int parallel = split ? 1 + random.nextInt(shape.parallelSplits()) : 0;
            for (int i = 0; i < parallel && !free.isEmpty(); i++) {
                roles.put(free.removeFirst(), Role.PARALLEL);
            }
            int halves = shape.halves() == 0 ? 0 : 1 + random.nextInt(shape.halves());
            for (int i = 0; i < halves && !free.isEmpty(); i++) {
                roles.put(free.removeFirst(), Role.HALVES);
            }
            free.forEach(slot -> roles.put(slot, Role.WHOLE));
            return roles;
        }

        /** Disciplines of the whole-group slots: at most two a week each, never twice a day, none used by other roles. */
        private Map<Slot, UUID> wholeDisciplines(List<Slot> wholeSlots, Map<Slot, Role> roles, List<UUID> pool,
                                                 Map<UUID, Boolean> taken) {
            Map<UUID, Integer> count = new HashMap<>();
            Map<Slot, UUID> result = new HashMap<>();
            for (Slot slot : wholeSlots) {
                UUID chosen = pool.stream()
                        .filter(discipline -> !taken.containsKey(discipline))
                        .filter(discipline -> count.getOrDefault(discipline, 0) < 2)
                        .filter(discipline -> result.entrySet().stream().noneMatch(entry ->
                                entry.getKey().weekday() == slot.weekday() && entry.getValue().equals(discipline)))
                        .min(Comparator.comparingInt(discipline -> count.getOrDefault(discipline, 0)))
                        .orElseThrow(() -> new IllegalStateException("not enough disciplines for " + roles.size()));
                count.merge(chosen, 1, Integer::sum);
                result.put(slot, chosen);
            }
            return result;
        }

        /**
         * A teacher of the discipline free at every occurrence (and within the daily limit and their weekdays),
         * part-time teachers first, then those already working on the occurrences' weekdays; a new one otherwise.
         */
        private TeacherState teacherFor(UUID discipline, List<Occurrence> occurrences, boolean pe) {
            List<TeacherState> pool = teachersByDiscipline.computeIfAbsent(discipline, key -> new ArrayList<>());
            List<TeacherState> candidates = pool.stream().filter(teacher -> teacher.canTeach(occurrences)).toList();
            TeacherState teacher;
            if (candidates.isEmpty()) {
                teacher = newTeacher(pool, Set.of(1, 2, 3, 4, 5), shape.teacherLoad());
            } else {
                teacher = candidates.stream().filter(partTime::contains).findFirst().orElseGet(() -> {
                    List<TeacherState> sameDays = candidates.stream()
                            .filter(candidate -> occurrences.stream()
                                    .allMatch(occurrence -> candidate.worksOn(occurrence.slot().weekday())))
                            .toList();
                    List<TeacherState> from = !sameDays.isEmpty() && random.nextInt(10) < 7 ? sameDays : candidates;
                    return from.get(random.nextInt(from.size()));
                });
            }
            teacher.take(occurrences);
            return teacher;
        }

        private void plantUnit(UUID groupId, int subgroup, UUID discipline, UUID loadItemId, TeacherState teacher,
                               Occurrence occurrence, RoomType required, String siblingKey) {
            Slot slot = occurrence.slot();
            Parity parity = occurrence.parity();
            RoomState room;
            if (discipline.equals(disciplines.get(0))) {
                room = gym;
            } else if (required == RoomType.COMPUTER_LAB) {
                room = labs.stream().filter(lab -> lab.isFree(slot, parity)).findFirst().orElseGet(() -> {
                    RoomState lab = newLab();
                    labs.add(lab);
                    return lab;
                });
                room.occupy(slot, parity);
            } else {
                room = teacher.cabinet.isFree(slot, parity) ? teacher.cabinet
                        : classrooms.stream().filter(candidate -> candidate.isFree(slot, parity)).findFirst()
                                .orElseGet(() -> {
                                    RoomState classroom = newClassroom();
                                    classrooms.add(classroom);
                                    return classroom;
                                });
                room.occupy(slot, parity);
            }
            String unitId = "u" + units.size();
            UUID teacherRoom = discipline.equals(disciplines.get(0)) ? gym.id : teacher.cabinet.id;
            units.add(new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, discipline, teacher.id,
                    parity == Parity.BOTH ? UnitWeight.WHOLE : UnitWeight.HALF, required, null, null, null, null,
                    teacherRoom, false, siblingKey));
            planted.add(new Assignment(unitId, slot.weekday(), slot.pair(), parity, room.id));
        }

        /** The stream's leader (first group: teacher and lecture hall) or follower (no teacher, no room), pinned. */
        private void plantStreamUnit(UUID groupId, StreamPlan stream) {
            UUID discipline = uuid("discipline-" + STREAM_DISCIPLINE);
            UUID loadItemId = uuid("load-stream-" + stream.index() + "-" + groupId);
            boolean leader = units.stream().noneMatch(unit -> unit.unitId().startsWith("s" + stream.index() + "-"));
            Slot slot = stream.slot();
            UUID teacherId = null;
            UUID roomId = null;
            if (leader) {
                if (hall == null) {
                    hall = new RoomState(uuid("room-hall"), "Актова зала", RoomType.CLASSROOM, 120, 1);
                }
                TeacherState lecturer = newTeacher(new ArrayList<>(), Set.of(1, 2, 3, 4, 5), shape.teacherLoad());
                lecturer.take(List.of(new Occurrence(slot, Parity.BOTH)));
                hall.occupy(slot, Parity.BOTH);
                teacherId = lecturer.id;
                roomId = hall.id;
            }
            String unitId = "s" + stream.index() + "-" + (leader ? "leader" : "follower");
            units.add(new ProblemSnapshot.Unit(unitId, loadItemId, groupId, 0, discipline, teacherId, UnitWeight.WHOLE,
                    null, null, null, new ProblemSnapshot.PinnedSlot(slot.weekday(), slot.pair(), Parity.BOTH, roomId),
                    null, null, false, null));
            planted.add(new Assignment(unitId, slot.weekday(), slot.pair(), Parity.BOTH, roomId));
        }

        /**
         * Teachers with the college's daily limit; part-time teachers are UNAVAILABLE outside their two weekdays.
         * A share of the others gets UNAVAILABLE slots where the plant does not use them (a free weekday, else
         * single pairs), a share gets unwanted slots, two of them where the plant teaches.
         */
        private List<ProblemSnapshot.Teacher> teacherFacts() {
            List<ProblemSnapshot.Teacher> result = new ArrayList<>();
            for (TeacherState teacher : teachers) {
                List<ProblemSnapshot.Timeslot> unavailable = new ArrayList<>();
                List<ProblemSnapshot.Timeslot> unwanted = new ArrayList<>();
                if (partTime.contains(teacher)) {
                    timeslots.stream().filter(slot -> !teacher.allowedDays.contains(slot.weekday()))
                            .forEach(unavailable::add);
                } else if (random.nextDouble() < shape.unavailableShare()) {
                    List<Integer> freeDays = new ArrayList<>();
                    for (int weekday = 1; weekday <= 5; weekday++) {
                        if (!teacher.worksOn(weekday)) {
                            freeDays.add(weekday);
                        }
                    }
                    if (!freeDays.isEmpty() && random.nextBoolean()) {
                        int day = freeDays.get(random.nextInt(freeDays.size()));
                        timeslots.stream().filter(slot -> slot.weekday() == day).forEach(unavailable::add);
                    } else {
                        List<ProblemSnapshot.Timeslot> freeSlots = new ArrayList<>(timeslots.stream()
                                .filter(slot -> !teacher.busy.contains(new SlotWeek(slot.weekday(), slot.pair(), NUMERATOR))
                                        && !teacher.busy.contains(new SlotWeek(slot.weekday(), slot.pair(), DENOMINATOR)))
                                .toList());
                        Collections.shuffle(freeSlots, random);
                        unavailable.addAll(freeSlots.subList(0, Math.min(4, freeSlots.size())));
                    }
                }
                if (random.nextDouble() < shape.unwantedShare()) {
                    List<SlotWeek> busy = new ArrayList<>(teacher.busy);
                    busy.sort(Comparator.comparingInt(SlotWeek::weekday).thenComparingInt(SlotWeek::pair)
                            .thenComparingInt(SlotWeek::week));
                    Collections.shuffle(busy, random);
                    Set<ProblemSnapshot.Timeslot> chosen = new HashSet<>();
                    busy.stream().limit(2).forEach(slot -> chosen.add(new ProblemSnapshot.Timeslot(slot.weekday(),
                            slot.pair())));
                    chosen.add(timeslots.get(random.nextInt(timeslots.size())));
                    chosen.removeAll(unavailable);
                    unwanted.addAll(chosen.stream().sorted(Comparator.comparingInt(ProblemSnapshot.Timeslot::weekday)
                            .thenComparingInt(ProblemSnapshot.Timeslot::pair)).toList());
                }
                result.add(new ProblemSnapshot.Teacher(teacher.id, teacher.name, unavailable, unwanted,
                        MAX_PAIRS_PER_DAY));
            }
            return result;
        }

        private TeacherState newTeacher(List<TeacherState> pool, Set<Integer> days, int maxLoad) {
            RoomState cabinet = classrooms.stream().filter(room -> room.cabinetOf < TEACHERS_PER_CABINET).findFirst()
                    .orElseGet(() -> {
                        RoomState classroom = newClassroom();
                        classrooms.add(classroom);
                        return classroom;
                    });
            cabinet.cabinetOf++;
            TeacherState teacher = new TeacherState(uuid("teacher-" + teachers.size()), "Викладач " + teachers.size(),
                    cabinet, days, maxLoad);
            teachers.add(teacher);
            pool.add(teacher);
            return teacher;
        }

        private RoomState newClassroom() {
            int number = 101 + idCounter++;
            return new RoomState(uuid("room-" + number), String.valueOf(number), RoomType.CLASSROOM,
                    30 + random.nextInt(5), 1);
        }

        private RoomState newLab() {
            int number = 501 + idCounter++;
            return new RoomState(uuid("lab-" + number), String.valueOf(number), RoomType.COMPUTER_LAB, 16, 1);
        }

        private static List<Slot> sorted(Set<Slot> slots) {
            return slots.stream().sorted(Comparator.comparingInt(Slot::weekday).thenComparingInt(Slot::pair)).toList();
        }

        private static ProblemSnapshot.Room snapshotRoom(RoomState room) {
            return new ProblemSnapshot.Room(room.id, room.name, room.type, room.capacity, room.maxParallel);
        }

        private static int firstPair(int shift, int weekday) {
            return shift == 1 ? 1 : weekday == 5 ? 4 : 5;
        }

        private static int lastPair(int shift, int weekday) {
            return shift == 1 ? 4 : weekday == 5 ? 6 : 8;
        }

        private static UUID uuid(String name) {
            return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }
    }
}
