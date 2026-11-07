package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.timetable.internal.calendar.ShiftWindows;

/** Readable test data for the conflict engine and metrics: «4КІ1», «Кренцін М.Д.», room «304» … */
public final class ConflictFixtures {

    public static final UUID G_4KI1 = id("group-4KI1");
    public static final UUID G_4KI2 = id("group-4KI2");
    public static final UUID G_2KI1 = id("group-2KI1");
    public static final UUID G_1TK1 = id("group-1TK1");
    public static final UUID T_KRENTSIN = id("teacher-krentsin");
    public static final UUID T_NEPYIVODA = id("teacher-nepyivoda");
    public static final UUID D_STP = id("discipline-stp");
    public static final UUID D_NDEKSM = id("discipline-ndeksm");
    public static final UUID D_ENG = id("discipline-eng");
    public static final UUID D_KM = id("discipline-km");
    public static final UUID D_FV = id("discipline-fv");
    public static final UUID R_304 = id("room-304");
    public static final UUID R_LAB6 = id("room-6");
    public static final UUID R_GYM = id("room-gym");

    private ConflictFixtures() {
    }

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** Template lesson of 4КІ1 (whole group, СТП, every week) at weekday × pair; refine with the builder. */
    public static LessonBuilder at(int weekday, int pair) {
        return new LessonBuilder(LessonDay.ofWeekday(weekday), pair);
    }

    /** Dated lesson of 4КІ1 (whole group, СТП). */
    public static LessonBuilder on(LocalDate date, int pair) {
        return new LessonBuilder(LessonDay.ofDate(date), pair);
    }

    /** Facts with 4КІ1/4КІ2 (25 students, shift 1), 2КІ1 (shift 2), rooms 304, 6 (computer lab), gym. */
    public static FactsBuilder facts() {
        return new FactsBuilder()
                .group(G_4KI1, "4КІ1", 4, 25, 1)
                .group(G_4KI2, "4КІ2", 4, 25, 1)
                .group(G_2KI1, "2КІ1", 2, 24, 2)
                .group(G_1TK1, "1ТК1", 1, 30, 1)
                .teacher(T_KRENTSIN, "Кренцін М.Д.")
                .teacher(T_NEPYIVODA, "Непийвода М.В.")
                .discipline(D_STP, "СТП")
                .discipline(D_NDEKSM, "НДЕКСМ")
                .discipline(D_ENG, "Ін.мова")
                .discipline(D_KM, "КМ")
                .discipline(D_FV, "ФВ")
                .room(R_304, "304", RoomType.CLASSROOM, 30, 1)
                .room(R_LAB6, "6", RoomType.COMPUTER_LAB, 15, 1)
                .room(R_GYM, "с.з.", RoomType.GYM, null, 3);
    }

    public static final class LessonBuilder {

        private UUID id = UUID.randomUUID();
        private final LessonDay day;
        private final int pair;
        private Parity parity = Parity.BOTH;
        private UUID groupId = G_4KI1;
        private int subgroup;
        private UUID disciplineId = D_STP;
        private UUID teacherId;
        private UUID roomId;
        private UUID streamId;
        private RoomType requiredRoomType;

        private LessonBuilder(LessonDay day, int pair) {
            this.day = day;
            this.pair = pair;
        }

        public LessonBuilder id(UUID value) {
            this.id = value;
            return this;
        }

        public LessonBuilder parity(Parity value) {
            this.parity = value;
            return this;
        }

        public LessonBuilder group(UUID value) {
            this.groupId = value;
            return this;
        }

        public LessonBuilder subgroup(int value) {
            this.subgroup = value;
            return this;
        }

        public LessonBuilder discipline(UUID value) {
            this.disciplineId = value;
            return this;
        }

        public LessonBuilder teacher(UUID value) {
            this.teacherId = value;
            return this;
        }

        public LessonBuilder room(UUID value) {
            this.roomId = value;
            return this;
        }

        public LessonBuilder stream(UUID value) {
            this.streamId = value;
            return this;
        }

        public LessonBuilder requires(RoomType value) {
            this.requiredRoomType = value;
            return this;
        }

        public SlotLesson build() {
            return new SlotLesson(id, day, pair, parity, groupId, subgroup, disciplineId, teacherId, roomId, streamId,
                    requiredRoomType);
        }
    }

    public static final class FactsBuilder {

        private final Map<UUID, GroupFact> groups = new HashMap<>();
        private final Map<UUID, RoomFact> rooms = new HashMap<>();
        private final Map<UUID, String> teachers = new HashMap<>();
        private final Map<UUID, String> disciplines = new HashMap<>();
        private final List<TeacherSlotPreferences.Entry> preferences = new ArrayList<>();
        private ShiftWindows shiftWindows = ShiftWindows.domainDefaults();

        public FactsBuilder group(UUID id, String code, int course, Integer size, int shift) {
            groups.put(id, new GroupFact(id, code, course, size, shift, Set.of(), Map.of()));
            return this;
        }

        /** Stored subgroups with sizes ({@code null} = unknown). */
        public FactsBuilder subgroups(UUID groupId, Integer firstSize, Integer secondSize) {
            GroupFact group = groups.get(groupId);
            Map<Integer, Integer> sizes = new HashMap<>();
            if (firstSize != null) {
                sizes.put(1, firstSize);
            }
            if (secondSize != null) {
                sizes.put(2, secondSize);
            }
            groups.put(groupId, new GroupFact(groupId, group.code(), group.course(), group.size(), group.shift(),
                    new HashSet<>(List.of(1, 2)), sizes));
            return this;
        }

        public FactsBuilder room(UUID id, String name, RoomType type, Integer capacity, int maxParallel) {
            rooms.put(id, new RoomFact(id, name, type, capacity, maxParallel));
            return this;
        }

        public FactsBuilder teacher(UUID id, String name) {
            teachers.put(id, name);
            return this;
        }

        public FactsBuilder discipline(UUID id, String name) {
            disciplines.put(id, name);
            return this;
        }

        public FactsBuilder preference(UUID teacherId, int weekday, int pair, SlotPreference preference) {
            preferences.add(new TeacherSlotPreferences.Entry(teacherId, weekday, pair, preference));
            return this;
        }

        public FactsBuilder shiftWindows(ShiftWindows value) {
            this.shiftWindows = value;
            return this;
        }

        public ConflictFacts build() {
            return new ConflictFacts(groups, rooms, teachers, disciplines, shiftWindows,
                    new TeacherSlotPreferences(preferences));
        }
    }
}
