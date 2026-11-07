package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Lessons grouped the ways the rules need them: by slot, by group-day and by teacher-day. */
final class LessonIndex {

    private static final Comparator<SlotLesson> LESSON_ORDER = Comparator.comparing(SlotLesson::day)
            .thenComparingInt(SlotLesson::pair)
            .thenComparing(SlotLesson::groupId)
            .thenComparingInt(SlotLesson::subgroup)
            .thenComparing(SlotLesson::id);

    private final SortedMap<SlotKey, List<SlotLesson>> bySlot;
    private final SortedMap<DayKey, List<SlotLesson>> byGroupDay;
    private final SortedMap<DayKey, List<SlotLesson>> byTeacherDay;

    private LessonIndex(List<SlotLesson> lessons) {
        this.bySlot = group(lessons, lesson -> new SlotKey(lesson.day(), lesson.pair()));
        this.byGroupDay = group(lessons, lesson -> new DayKey(lesson.day(), lesson.groupId()));
        this.byTeacherDay = group(lessons.stream().filter(lesson -> lesson.teacherId() != null).toList(),
                lesson -> new DayKey(lesson.day(), lesson.teacherId()));
    }

    static LessonIndex of(Collection<SlotLesson> lessons) {
        return new LessonIndex(lessons.stream().sorted(LESSON_ORDER).toList());
    }

    /** Lessons of each (day, pair), ordered by day and pair. */
    SortedMap<SlotKey, List<SlotLesson>> bySlot() {
        return bySlot;
    }

    /** Lessons of each group per day, ordered by pair. */
    SortedMap<DayKey, List<SlotLesson>> byGroupDay() {
        return byGroupDay;
    }

    /** Lessons of each teacher per day, ordered by pair. */
    SortedMap<DayKey, List<SlotLesson>> byTeacherDay() {
        return byTeacherDay;
    }

    private static <K> SortedMap<K, List<SlotLesson>> group(List<SlotLesson> lessons, Function<SlotLesson, K> key) {
        SortedMap<K, List<SlotLesson>> grouped = new TreeMap<>();
        for (SlotLesson lesson : lessons) {
            grouped.computeIfAbsent(key.apply(lesson), ignored -> new ArrayList<>()).add(lesson);
        }
        grouped.replaceAll((ignored, list) -> List.copyOf(list));
        return grouped;
    }

    record SlotKey(LessonDay day, int pair) implements Comparable<SlotKey> {

        @Override
        public int compareTo(SlotKey other) {
            int byDay = day.compareTo(other.day);
            return byDay != 0 ? byDay : Integer.compare(pair, other.pair);
        }
    }

    /** A day of a group or of a teacher ({@code ownerId}). */
    record DayKey(LessonDay day, UUID ownerId) implements Comparable<DayKey> {

        @Override
        public int compareTo(DayKey other) {
            int byDay = day.compareTo(other.day);
            return byDay != 0 ? byDay : ownerId.compareTo(other.ownerId);
        }
    }

    static Map<UUID, List<SlotLesson>> byOwner(List<SlotLesson> lessons, Function<SlotLesson, UUID> owner) {
        SortedMap<UUID, List<SlotLesson>> grouped = new TreeMap<>();
        for (SlotLesson lesson : lessons) {
            UUID id = owner.apply(lesson);
            if (id != null) {
                grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(lesson);
            }
        }
        return grouped;
    }
}
