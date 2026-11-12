package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable copy of a calendar period (DOMAIN.md §2) for materialization and schedule views.
 * HOLIDAY, NO_CLASSES and PRACTICE remove lessons of everybody in scope; STUDY_MODE ONLINE/MIXED
 * keeps them but marks them online; EXAM_SESSION only adds a note. Overlapping STUDY_MODE periods do not
 * add up: the most specific one decides ({@link #decidingStudyMode}), so a group OFFLINE period inside a
 * course ONLINE period brings that group back to classes.
 *
 * @param id id of the calendar period; {@code null} only in hand-made test periods
 */
public record StudyPeriod(
        CalendarPeriodKind kind,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        StudyMode studyMode,
        PeriodScope scope,
        Set<Integer> courses,
        Set<UUID> groupIds,
        UUID id) {

    /** Groups over courses over everybody, then the later start, then the id (DOMAIN §2). */
    private static final Comparator<StudyPeriod> STUDY_MODE_PRECEDENCE =
            Comparator.comparingInt((StudyPeriod period) -> specificity(period.scope()))
                    .thenComparing(StudyPeriod::startsOn)
                    .thenComparing(StudyPeriod::id, Comparator.nullsFirst(Comparator.naturalOrder()));

    public StudyPeriod {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        Objects.requireNonNull(scope, "scope");
        courses = courses == null ? Set.of() : Set.copyOf(courses);
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** A period without an id (tests and previews). */
    public StudyPeriod(CalendarPeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn, StudyMode studyMode,
                       PeriodScope scope, Set<Integer> courses, Set<UUID> groupIds) {
        this(kind, title, startsOn, endsOn, studyMode, scope, courses, groupIds, null);
    }

    public static StudyPeriod of(CalendarPeriod period) {
        return new StudyPeriod(period.getKind(), period.getTitle(), period.getStartsOn(), period.getEndsOn(),
                period.getStudyMode(), period.getScope(), Set.copyOf(period.getCourses()), period.getGroupIds(),
                period.getId());
    }

    /**
     * The STUDY_MODE period that decides the study mode among {@code active} (periods that already cover
     * the date and apply to the group): the most specific scope wins, between equally specific ones the
     * later started decision, then the larger id. Empty when no STUDY_MODE period is active.
     */
    public static Optional<StudyPeriod> decidingStudyMode(Collection<StudyPeriod> active) {
        return active.stream()
                .filter(period -> period.kind() == CalendarPeriodKind.STUDY_MODE && period.studyMode() != null)
                .max(STUDY_MODE_PRECEDENCE);
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

    /** ONLINE or MIXED study mode; whether it applies on a date is decided by {@link #decidingStudyMode}. */
    public boolean marksOnline() {
        return kind == CalendarPeriodKind.STUDY_MODE
                && (studyMode == StudyMode.ONLINE || studyMode == StudyMode.MIXED);
    }

    private static int specificity(PeriodScope scope) {
        return switch (scope) {
            case ALL -> 1;
            case COURSES -> 2;
            case GROUPS -> 3;
        };
    }
}
