package ua.vtc.rozklad.scheduler.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

/**
 * Deterministic synthetic college shaped like ВТФК (seed Excel 2025/26): two shifts with the real
 * windows, 16 weekly units per group (one PE lesson in the sports hall with three parallel
 * places, a pair of halves, subgroup labs for split groups), teachers with ~18 lessons at most,
 * shared teacher cabinets and home rooms.
 *
 * <p>The generator first plants a complete timetable without hard violations and only then
 * forgets the placement, so every generated problem is known to be feasible (the planted
 * assignment is returned for checks). Rooms are created only when the plant needs them, which
 * keeps the room supply as tight as in the real college.
 */
public final class SyntheticCollege {

    static final int UNITS_PER_GROUP = 16;
    static final int MAX_TEACHER_LOAD = 18;
    static final int TEACHERS_PER_CABINET = 2;
    static final int GYM_PLACES = 3;
    static final int INITIAL_LABS = 6;

    private static final String[] DISCIPLINES = {
            "Математика", "Українська мова", "Українська література", "Іноземна мова", "Історія України",
            "Фізика", "Хімія", "Інформатика", "Біологія", "Географія", "Громадянська освіта", "Захист України",
            "Електротехніка", "Програмування", "Комп'ютерні мережі", "Економіка", "Бухгалтерський облік",
            "Охорона праці", "Креслення", "Метрологія", "Матеріалознавство", "Радіотехніка", "Менеджмент",
            "Правознавство", "Соціологія", "Психологія"};
    private static final String[] LETTERS = {"КІ", "ЕА", "РТ", "ТК", "ОП", "ВЕ", "ОБ", "Ф", "М", "ОД", "ЕП"};

    /** Size of the synthetic college. */
    public record Options(int groups, int shiftTwoGroups, double splitShare, long seed) {

        /** About the real college: 56 groups, the 2nd course (15 groups) in the second shift. */
        public static Options realSize() {
            return new Options(56, 15, 0.5, 2026);
        }

        /** Enough for solve tests: 24 groups in two shifts. */
        public static Options medium() {
            return new Options(24, 6, 0.5, 7);
        }

        /** Small college for quick solver checks. */
        public static Options small() {
            return new Options(4, 1, 0.5, 3);
        }

        /** Two split groups (one per shift) for very slow checks such as full score assertions. */
        public static Options tiny() {
            return new Options(2, 1, 1.0, 5);
        }
    }

    /** The problem as core would send it, plus a known feasible placement of every unit. */
    public record Problem(ProblemSnapshot snapshot, List<Assignment> planted) {
    }

    private SyntheticCollege() {
    }

    public static Problem generate(Options options) {
        return new Generator(options).run();
    }

    private record Slot(int weekday, int pair) {
    }

    private static final class RoomState {
        final UUID id;
        final String name;
        final RoomType type;
        final int capacity;
        final int maxParallel;
        final Map<Slot, Integer> use = new HashMap<>();
        int cabinetOf;

        RoomState(UUID id, String name, RoomType type, int capacity, int maxParallel) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.capacity = capacity;
            this.maxParallel = maxParallel;
        }

        boolean isFree(Slot slot) {
            return use.getOrDefault(slot, 0) < maxParallel;
        }

        void occupy(Slot slot) {
            use.merge(slot, 1, Integer::sum);
        }
    }

    private static final class TeacherState {
        final UUID id;
        final String name;
        final RoomState cabinet;
        final Set<Slot> busy = new HashSet<>();
        int load;

        TeacherState(UUID id, String name, RoomState cabinet) {
            this.id = id;
            this.name = name;
            this.cabinet = cabinet;
        }

        boolean canTeach(Slot slot) {
            return load < MAX_TEACHER_LOAD && !busy.contains(slot);
        }
    }

    private enum Role { PE, HALVES, SPLIT, WHOLE }

    private static final class Generator {

        private final Options options;
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
        private final List<ProblemSnapshot.Unit> units = new ArrayList<>();
        private final List<Assignment> planted = new ArrayList<>();
        private int idCounter;

        Generator(Options options) {
            this.options = options;
            this.random = new Random(options.seed());
            for (int weekday = 1; weekday <= 5; weekday++) {
                int pairs = weekday == 5 ? 6 : 8;
                for (int pair = 1; pair <= pairs; pair++) {
                    timeslots.add(new ProblemSnapshot.Timeslot(weekday, pair));
                }
                windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
                windows.add(weekday == 5
                        ? new ProblemSnapshot.ShiftWindow(2, weekday, 4, 6)
                        : new ProblemSnapshot.ShiftWindow(2, weekday, 5, 8));
            }
            for (String name : DISCIPLINES) {
                disciplines.add(uuid("discipline-" + name));
            }
            gym = new RoomState(uuid("room-gym"), "с.з.", RoomType.GYM, 90, GYM_PLACES);
            for (int i = 0; i < INITIAL_LABS; i++) {
                labs.add(newLab());
            }
        }

        Problem run() {
            int shiftOneGroups = options.groups() - options.shiftTwoGroups();
            for (int i = 0; i < shiftOneGroups; i++) {
                classrooms.add(newClassroom());
            }
            for (int index = 0; index < options.groups(); index++) {
                plantGroup(index, index < options.shiftTwoGroups() ? 2 : 1);
            }
            List<ProblemSnapshot.Room> rooms = new ArrayList<>();
            rooms.add(snapshotRoom(gym));
            classrooms.forEach(room -> rooms.add(snapshotRoom(room)));
            labs.forEach(room -> rooms.add(snapshotRoom(room)));
            ProblemSnapshot snapshot = new ProblemSnapshot(timeslots, windows, groups, rooms, teacherPreferences(), units);
            return new Problem(snapshot, planted);
        }

        private void plantGroup(int index, int shift) {
            boolean split = random.nextDouble() < options.splitShare();
            int course = shift == 2 ? 2 : new int[] {1, 3, 4}[index % 3];
            String code = "%d%s%d".formatted(course, LETTERS[index % LETTERS.length], 1 + index / LETTERS.length);
            UUID groupId = uuid("group-" + index);
            int size = 18 + random.nextInt(13);
            RoomState home = shift == 1 ? classrooms.get(index - options.shiftTwoGroups())
                    : classrooms.get(random.nextInt(classrooms.size()));
            groups.add(new ProblemSnapshot.Group(groupId, code, size, shift, split ? List.of(1, 2) : List.of()));

            List<Slot> slots = chooseSlots(shift, split ? 14 : 15);
            Map<Slot, Role> roles = assignRoles(slots, split);
            List<UUID> curriculum = new ArrayList<>(disciplines.subList(1, disciplines.size()));
            Collections.shuffle(curriculum, random);
            UUID splitDiscipline = curriculum.get(0);
            UUID halfA = curriculum.get(1);
            UUID halfB = curriculum.get(2);
            Map<Slot, UUID> wholeDisciplines = wholeDisciplines(roles, curriculum.subList(3, 11));

            for (Slot slot : slots) {
                switch (roles.get(slot)) {
                    case PE -> plantUnit(groupId, home, 0, disciplines.get(0), UnitWeight.WHOLE, Parity.BOTH, slot,
                            RoomType.GYM);
                    case HALVES -> {
                        plantUnit(groupId, home, 0, halfA, UnitWeight.HALF, Parity.NUMERATOR, slot, null);
                        plantUnit(groupId, home, 0, halfB, UnitWeight.HALF, Parity.DENOMINATOR, slot, null);
                    }
                    case SPLIT -> {
                        plantUnit(groupId, home, 1, splitDiscipline, UnitWeight.WHOLE, Parity.BOTH, slot,
                                RoomType.COMPUTER_LAB);
                        plantUnit(groupId, home, 2, splitDiscipline, UnitWeight.WHOLE, Parity.BOTH, slot,
                                RoomType.COMPUTER_LAB);
                    }
                    case WHOLE -> plantUnit(groupId, home, 0, wholeDisciplines.get(slot), UnitWeight.WHOLE,
                            Parity.BOTH, slot, null);
                }
            }
        }

        /** Compact days of 2–3 pairs that start at the first or the second pair of the shift. */
        private List<Slot> chooseSlots(int shift, int count) {
            List<Integer> lengths = new ArrayList<>(List.of(3, 3, 3, 3, 3));
            for (int extra = 15 - count; extra > 0; extra--) {
                lengths.set(extra - 1, 2);
            }
            Collections.shuffle(lengths, random);
            List<Slot> slots = new ArrayList<>();
            for (int weekday = 1; weekday <= 5; weekday++) {
                int first = firstPair(shift, weekday);
                int last = lastPair(shift, weekday);
                int length = lengths.get(weekday - 1);
                int start = first + length <= last && random.nextInt(4) == 0 ? first + 1 : first;
                for (int pair = start; pair < start + length; pair++) {
                    slots.add(new Slot(weekday, pair));
                }
            }
            return slots;
        }

        private Map<Slot, Role> assignRoles(List<Slot> slots, boolean split) {
            List<Slot> shuffled = new ArrayList<>(slots);
            Collections.shuffle(shuffled, random);
            Map<Slot, Role> roles = new HashMap<>();
            Slot peSlot = shuffled.stream().filter(gym::isFree).findFirst()
                    .orElseThrow(() -> new IllegalStateException("sports hall is full"));
            roles.put(peSlot, Role.PE);
            gym.occupy(peSlot);
            List<Slot> rest = new ArrayList<>(shuffled);
            rest.remove(peSlot);
            roles.put(rest.remove(0), Role.HALVES);
            if (split) {
                roles.put(rest.remove(0), Role.SPLIT);
            }
            rest.forEach(slot -> roles.put(slot, Role.WHOLE));
            return roles;
        }

        /** Disciplines of the WHOLE slots: up to two lessons each, never twice on one day. */
        private Map<Slot, UUID> wholeDisciplines(Map<Slot, Role> roles, List<UUID> pool) {
            List<Slot> wholeSlots = roles.entrySet().stream()
                    .filter(entry -> entry.getValue() == Role.WHOLE)
                    .map(Map.Entry::getKey)
                    .sorted((a, b) -> a.weekday() != b.weekday() ? a.weekday() - b.weekday() : a.pair() - b.pair())
                    .toList();
            Map<UUID, Integer> count = new HashMap<>();
            Map<Slot, UUID> result = new HashMap<>();
            for (Slot slot : wholeSlots) {
                UUID chosen = pool.stream()
                        .filter(discipline -> count.getOrDefault(discipline, 0) < 2)
                        .filter(discipline -> result.entrySet().stream().noneMatch(entry ->
                                entry.getKey().weekday() == slot.weekday() && entry.getValue().equals(discipline)))
                        .min((a, b) -> count.getOrDefault(a, 0) - count.getOrDefault(b, 0))
                        .orElseThrow(() -> new IllegalStateException("not enough disciplines"));
                count.merge(chosen, 1, Integer::sum);
                result.put(slot, chosen);
            }
            return result;
        }

        private void plantUnit(UUID groupId, RoomState home, int subgroup, UUID discipline, UnitWeight weight,
                               Parity parity, Slot slot, RoomType requiredType) {
            TeacherState teacher = teacherFor(discipline, slot);
            RoomState room;
            if (requiredType == RoomType.GYM) {
                room = gym;
            } else if (requiredType == RoomType.COMPUTER_LAB) {
                room = labs.stream().filter(lab -> lab.isFree(slot)).findFirst().orElseGet(() -> {
                    RoomState lab = newLab();
                    labs.add(lab);
                    return lab;
                });
                room.occupy(slot);
            } else {
                room = teacher.cabinet.isFree(slot) ? teacher.cabinet
                        : classrooms.stream().filter(candidate -> candidate.isFree(slot)).findFirst().orElseGet(() -> {
                            RoomState classroom = newClassroom();
                            classrooms.add(classroom);
                            return classroom;
                        });
                room.occupy(slot);
            }
            String unitId = "u" + units.size();
            UUID preferred = requiredType == null ? teacher.cabinet.id : null;
            units.add(new ProblemSnapshot.Unit(unitId, uuid("load-" + groupId + discipline + subgroup), groupId,
                    subgroup, discipline, teacher.id, weight, requiredType, preferred, home.id, null));
            planted.add(new Assignment(unitId, slot.weekday(), slot.pair(), parity, room.id));
        }

        private TeacherState teacherFor(UUID discipline, Slot slot) {
            List<TeacherState> pool = teachersByDiscipline.computeIfAbsent(discipline, key -> new ArrayList<>());
            List<TeacherState> candidates = pool.stream().filter(teacher -> teacher.canTeach(slot)).toList();
            TeacherState teacher = candidates.isEmpty() ? newTeacher(pool)
                    : candidates.get(random.nextInt(candidates.size()));
            teacher.busy.add(slot);
            teacher.load++;
            return teacher;
        }

        private TeacherState newTeacher(List<TeacherState> pool) {
            RoomState cabinet = classrooms.stream()
                    .filter(room -> room.cabinetOf < TEACHERS_PER_CABINET)
                    .findFirst()
                    .orElseGet(() -> {
                        RoomState classroom = newClassroom();
                        classrooms.add(classroom);
                        return classroom;
                    });
            cabinet.cabinetOf++;
            TeacherState teacher = new TeacherState(uuid("teacher-" + teachers.size()), "Викладач " + teachers.size(),
                    cabinet);
            teachers.add(teacher);
            pool.add(teacher);
            return teacher;
        }

        /** Every 8th teacher cannot work on a free weekday; every 5th dislikes two slots. */
        private List<ProblemSnapshot.Teacher> teacherPreferences() {
            List<ProblemSnapshot.Teacher> result = new ArrayList<>();
            for (int i = 0; i < teachers.size(); i++) {
                TeacherState teacher = teachers.get(i);
                List<ProblemSnapshot.Timeslot> unavailable = new ArrayList<>();
                List<ProblemSnapshot.Timeslot> unwanted = new ArrayList<>();
                if (i % 8 == 3) {
                    for (int weekday = 5; weekday >= 1 && unavailable.isEmpty(); weekday--) {
                        int day = weekday;
                        if (teacher.busy.stream().noneMatch(slot -> slot.weekday() == day)) {
                            timeslots.stream().filter(slot -> slot.weekday() == day).forEach(unavailable::add);
                        }
                    }
                }
                if (i % 5 == 1) {
                    unwanted.add(new ProblemSnapshot.Timeslot(1, 1));
                    unwanted.add(new ProblemSnapshot.Timeslot(5, 4));
                }
                result.add(new ProblemSnapshot.Teacher(teacher.id, teacher.name, unavailable, unwanted));
            }
            return result;
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
