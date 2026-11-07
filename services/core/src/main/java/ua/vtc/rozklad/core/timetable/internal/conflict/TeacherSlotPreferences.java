package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Unwanted / unavailable slots of teachers in one semester (table {@code teacher_preference}). */
public final class TeacherSlotPreferences {

    private static final TeacherSlotPreferences NONE = new TeacherSlotPreferences(List.of());

    private final Map<UUID, Map<Integer, SlotPreference>> byTeacher;

    public TeacherSlotPreferences(Collection<Entry> entries) {
        Map<UUID, Map<Integer, SlotPreference>> map = new HashMap<>();
        for (Entry entry : entries) {
            map.computeIfAbsent(entry.teacherId(), id -> new HashMap<>())
                    .put(key(entry.weekday(), entry.pair()), entry.preference());
        }
        map.replaceAll((teacherId, slots) -> Map.copyOf(slots));
        this.byTeacher = Map.copyOf(map);
    }

    public static TeacherSlotPreferences none() {
        return NONE;
    }

    public Optional<SlotPreference> of(UUID teacherId, int weekday, int pair) {
        if (teacherId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTeacher.getOrDefault(teacherId, Map.of()).get(key(weekday, pair)));
    }

    /** Slots of a teacher with the given preference, ordered by weekday and pair. */
    public List<Slot> slots(UUID teacherId, SlotPreference preference) {
        return byTeacher.getOrDefault(teacherId, Map.of()).entrySet().stream()
                .filter(entry -> entry.getValue() == preference)
                .map(Map.Entry::getKey)
                .sorted()
                .map(key -> new Slot(key / 10, key % 10))
                .toList();
    }

    public record Slot(int weekday, int pair) {
    }

    private static int key(int weekday, int pair) {
        return weekday * 10 + pair;
    }

    public record Entry(UUID teacherId, int weekday, int pair, SlotPreference preference) {

        public Entry {
            Objects.requireNonNull(teacherId, "teacherId");
            Objects.requireNonNull(preference, "preference");
        }
    }
}
