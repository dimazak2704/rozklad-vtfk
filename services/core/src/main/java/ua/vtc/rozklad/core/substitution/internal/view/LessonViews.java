package ua.vtc.rozklad.core.substitution.internal.view;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;

/**
 * Dated lessons as {@link LessonView}s in the order given, through the timetable's
 * {@link ScheduleQueries} so that admin screens and public schedules show the same kind, times and
 * original of a lesson.
 */
@Component
public class LessonViews {

    private final ScheduleQueries schedules;

    public LessonViews(ScheduleQueries schedules) {
        this.schedules = schedules;
    }

    public LessonView of(Lesson lesson) {
        return of(List.of(lesson)).getFirst();
    }

    public List<LessonView> of(List<Lesson> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        Map<UUID, LessonView> views = schedules.lessonViews(source.stream().map(Lesson::getId).toList());
        return source.stream()
                .map(lesson -> Objects.requireNonNull(views.get(lesson.getId()),
                        () -> "lesson " + lesson.getId() + " has no view"))
                .toList();
    }
}
