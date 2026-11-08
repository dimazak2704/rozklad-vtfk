package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable copy of a calendar period (DOMAIN.md §2) for materialization and schedule views.
 * HOLIDAY, NO_CLASSES and PRACTICE remove lessons of everybody in scope; STUDY_MODE ONLINE/MIXED
 * keeps them but marks them online; EXAM_SESSION only adds a note.
 */
public record StudyPeriod(
        CalendarPeriodKind kind,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        StudyMode studyMode,
        PeriodScope scope,
        Set<Integer> courses,
        Set<UUID> groupIds) {

    public StudyPeriod {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        Objects.requireNonNull(scope, "scope");
        courses = courses == null ? Set.of() : Set.copyOf(courses);
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    public static StudyPeriod of(CalendarPeriod period) {
        return new StudyPeriod(period.getKind(), period.getTitle(), period.getStartsOn(), period.getEndsOn(),
                period.getStudyMode(), period.getScope(), Set.copyOf(period.getCourses()), period.getGroupIds());
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    /** {@code group == null} means "the whole college" (teacher and room schedules): only scope ALL applies. */
    public boolean appliesTo(GroupScope group) {
        return switch (scope) {
            case ALL -> true;
            case COURSES -> group != null && courses.contains(group.course());
            case GROUPS -> group != null && groupIds.contains(group.groupId());
        };
    }

    public boolean closesLessons() {
        return kind == CalendarPeriodKind.HOLIDAY || kind == CalendarPeriodKind.NO_CLASSES
                || kind == CalendarPeriodKind.PRACTICE;
    }

    public boolean marksOnline() {
        return kind == CalendarPeriodKind.STUDY_MODE
                && (studyMode == StudyMode.ONLINE || studyMode == StudyMode.MIXED);
    }
}
