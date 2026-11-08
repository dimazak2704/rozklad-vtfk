package ua.vtc.rozklad.core.substitution.internal.options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Progress;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Target;

/** Builder of {@link RankingInput} for the ranking tests: a lesson of 4КІ1 on Monday 19.10.2026. */
final class RankingInputs {

    static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    static final UUID LESSON = UUID.fromString("00000000-0000-4000-8000-000000000001");
    static final UUID STP = id("СТП");
    static final UUID ABSENT = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    /** On track: one more pair still fits the plan (plan 40, fact 10, forecast 38). */
    static final Progress ON_TRACK = new Progress(40, 10, 38, AccountingStatus.ON_TRACK, BigDecimal.valueOf(2));

    private final int pair;
    private UUID lessonId = LESSON;
    private int subgroup;
    private LocalDate today = DATE.minusDays(1);
    private boolean online;
    private boolean groupHasBlock;
    private RoomType roomType = RoomType.CLASSROOM;
    private Progress targetProgress = new Progress(40, 10, 30, AccountingStatus.WILL_UNDERRUN, BigDecimal.valueOf(2));
    private final List<GroupLesson> groupDay = new ArrayList<>();
    private final List<GroupDiscipline> pool = new ArrayList<>();
    private final Map<UUID, Set<Integer>> teacherPairs = new HashMap<>();
    private final Map<Integer, Set<UUID>> busy = new HashMap<>();
    private final Map<Integer, Set<UUID>> absent = new HashMap<>();
    private final Map<UUID, PreferenceKind> preferences = new HashMap<>();
    private final Map<UUID, BigDecimal> weekly = new HashMap<>();
    private final Map<UUID, Double> burden = new HashMap<>();
    private final Map<UUID, Integer> coverCount = new HashMap<>();
    private final Map<UUID, LocalDate> lastCover = new HashMap<>();
    private final Set<UUID> declinedHere = new HashSet<>();
    private final Set<UUID> declinedToday = new HashSet<>();

    private RankingInputs(int pair) {
        this.pair = pair;
    }

    static RankingInputs lesson(int pair) {
        return new RankingInputs(pair);
    }

    static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    RankingInputs lessonId(UUID value) {
        lessonId = value;
        return this;
    }

    RankingInputs subgroup(int value) {
        subgroup = value;
        return this;
    }

    RankingInputs today(LocalDate value) {
        today = value;
        return this;
    }

    RankingInputs online() {
        online = true;
        return this;
    }

    RankingInputs block() {
        groupHasBlock = true;
        return this;
    }

    RankingInputs roomType(RoomType value) {
        roomType = value;
        return this;
    }

    RankingInputs targetProgress(Progress value) {
        targetProgress = value;
        return this;
    }

    /** Another discipline of the pool, on track, 2 h/week, no own room. */
    RankingInputs discipline(String shortName, String teacher) {
        return discipline(shortName, teacher, ON_TRACK);
    }

    RankingInputs discipline(String shortName, String teacher, Progress progress) {
        pool.add(new GroupDiscipline(id(shortName), shortName, id(teacher), teacher, progress, null, null));
        weekly.putIfAbsent(id(teacher), BigDecimal.valueOf(10));
        return this;
    }

    RankingInputs discipline(GroupDiscipline item) {
        pool.add(item);
        weekly.putIfAbsent(item.teacherId(), BigDecimal.valueOf(10));
        return this;
    }

    /** A lesson of the group that day (also counted in the teacher's day and busy at its pair). */
    RankingInputs groupLesson(int lessonPair, int lessonSubgroup, String discipline, String teacher, boolean decided) {
        groupDay.add(new GroupLesson(id(discipline + lessonPair + lessonSubgroup), lessonPair, lessonSubgroup,
                id(discipline), discipline, teacher == null ? null : id(teacher), teacher, id("room" + lessonPair),
                decided));
        if (teacher != null) {
            teaches(teacher, lessonPair);
        }
        return this;
    }

    /** The absent teacher's other lesson of the group (STP). */
    RankingInputs absentTeachersLesson(int lessonPair, boolean decided) {
        groupDay.add(new GroupLesson(id("СТП" + lessonPair), lessonPair, subgroup, STP, "СТП", ABSENT, "Кренцін",
                null, decided));
        teacherPairs.computeIfAbsent(ABSENT, key -> new TreeSet<>()).add(lessonPair);
        return this;
    }

    /** The teacher has a lesson in another group at these pairs. */
    RankingInputs teaches(String teacher, int... pairs) {
        for (int value : pairs) {
            teacherPairs.computeIfAbsent(id(teacher), key -> new TreeSet<>()).add(value);
            busy.computeIfAbsent(value, key -> new HashSet<>()).add(id(teacher));
        }
        return this;
    }

    RankingInputs absent(String teacher, int... pairs) {
        for (int value : pairs) {
            absent.computeIfAbsent(value, key -> new HashSet<>()).add(id(teacher));
        }
        return this;
    }

    RankingInputs preference(String teacher, PreferenceKind kind) {
        preferences.put(id(teacher), kind);
        return this;
    }

    RankingInputs weekly(String teacher, double hours) {
        weekly.put(id(teacher), BigDecimal.valueOf(hours));
        return this;
    }

    RankingInputs burden(String teacher, double value, int count, LocalDate last) {
        burden.put(id(teacher), value);
        coverCount.put(id(teacher), count);
        lastCover.put(id(teacher), last);
        return this;
    }

    RankingInputs declinedHere(String teacher) {
        declinedHere.add(id(teacher));
        return this;
    }

    RankingInputs declinedToday(String teacher) {
        declinedToday.add(id(teacher));
        return this;
    }

    RankingInput build() {
        Map<UUID, List<Integer>> pairs = new HashMap<>();
        teacherPairs.forEach((teacher, own) -> pairs.put(teacher, List.copyOf(own)));
        return new RankingInput(new Target(lessonId, DATE, today, pair, subgroup, STP, "СТП", ABSENT, roomType, null,
                online, 2, groupHasBlock, targetProgress), groupDay, pool, pairs, busy, absent, preferences, weekly,
                burden, coverCount, lastCover, declinedHere, declinedToday, Map.of());
    }
}
