package ua.vtc.rozklad.core.substitution.internal.effect;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.core.timetable.Lesson;

/** What applying a change does to dated lessons: lessons to cancel and lessons to create. */
public record ChangeEffect(List<Lesson> cancelled, List<PlannedLesson> created) {

    public ChangeEffect {
        cancelled = List.copyOf(cancelled);
        created = List.copyOf(created);
    }

    public Set<UUID> cancelledIds() {
        return cancelled.stream().map(Lesson::getId).collect(Collectors.toUnmodifiableSet());
    }
}
