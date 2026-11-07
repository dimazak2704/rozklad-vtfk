package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/** Result of a conflict check: hard violations (block publication) and soft warnings. */
public record ConflictReport(List<ConflictDto> hard, List<ConflictDto> soft) {

    public ConflictReport {
        hard = List.copyOf(hard);
        soft = List.copyOf(soft);
    }

    public static ConflictReport empty() {
        return new ConflictReport(List.of(), List.of());
    }

    public boolean hasHard() {
        return !hard.isEmpty();
    }

    /** Hard conflicts first, then soft warnings. */
    public List<ConflictDto> all() {
        return Stream.concat(hard.stream(), soft.stream()).toList();
    }

    /** Conflicts that list at least one of the lessons. */
    public ConflictReport involving(Collection<UUID> lessonIds) {
        Set<UUID> wanted = Set.copyOf(lessonIds);
        return filter(conflict -> conflict.lessonIds().stream().anyMatch(wanted::contains));
    }

    public ConflictReport filter(Predicate<ConflictDto> predicate) {
        return new ConflictReport(hard.stream().filter(predicate).toList(), soft.stream().filter(predicate).toList());
    }
}
