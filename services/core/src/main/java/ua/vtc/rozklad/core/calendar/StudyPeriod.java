package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable copy of a calendar period (DOMAIN §2) for materialization and schedule views. Behaviour comes
 * from the effect of its kind only: DAY_OFF and SUSPEND remove lessons of everybody in scope; ONLINE with
 * study mode ONLINE/MIXED keeps them but marks them online; INFO only adds a note. Overlapping ONLINE periods
 * do not add up: the most specific one decides ({@link #decidingStudyMode}), so a group OFFLINE period inside
 * a course ONLINE period brings that group back to classes.
 *
 * @param id            id of the calendar period; {@code null} only in hand-made test periods
 * @param supervisorIds teachers supervising a period of a SUSPEND kind (керівники); empty otherwise
 * @param roomId        room of a SUSPEND period (display only), {@code null} when none
 */
public record StudyPeriod(
        PeriodKindInfo kind,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        StudyMode studyMode,
        PeriodScope scope,
        Set<Integer> courses,
        Set<UUID> groupIds,
        UUID id,
        Set<UUID> supervisorIds,
        UUID roomId) {

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
        supervisorIds = supervisorIds == null ? Set.of() : Set.copyOf(supervisorIds);
    }

    /** A period of the builtin kind a legacy kind maps to (tests and previews). */
    public StudyPeriod(CalendarPeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn,
                       StudyMode studyMode, PeriodScope scope, Set<Integer> courses, Set<UUID> groupIds, UUID id,
                       Set<UUID> supervisorIds) {
        this(PeriodKindInfo.legacy(kind), title, startsOn, endsOn, studyMode, scope, courses, groupIds, id,
                supervisorIds, null);
    }

    /** A period without supervisors. */
    public StudyPeriod(CalendarPeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn, StudyMode studyMode,
                       PeriodScope scope, Set<Integer> courses, Set<UUID> groupIds, UUID id) {
        this(kind, title, startsOn, endsOn, studyMode, scope, courses, groupIds, id, Set.of());
    }

    /** A period without an id (tests and previews). */
    public StudyPeriod(CalendarPeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn, StudyMode studyMode,
                       PeriodScope scope, Set<Integer> courses, Set<UUID> groupIds) {
        this(kind, title, startsOn, endsOn, studyMode, scope, courses, groupIds, null, Set.of());
    }

    /** A period of any kind without an id, supervisors or room (tests and previews). */
    public StudyPeriod(PeriodKindInfo kind, String title, LocalDate startsOn, LocalDate endsOn, StudyMode studyMode,
                       PeriodScope scope, Set<Integer> courses, Set<UUID> groupIds) {
        this(kind, title, startsOn, endsOn, studyMode, scope, courses, groupIds, null, Set.of(), null);
    }

    /**
     * Supervisors are read only for a SUSPEND kind: other kinds never touch that (lazy) collection. The kind
     * must be loaded with the period ({@code join fetch p.kind}).
     */
    public static StudyPeriod of(CalendarPeriod period) {
        boolean suspends = period.getEffect() == PeriodEffect.SUSPEND;
        Set<UUID> supervisors = suspends ? period.getSupervisorIds() : Set.of();
        return new StudyPeriod(PeriodKindInfo.of(period.getKind()), period.getTitle(), period.getStartsOn(),
                period.getEndsOn(), period.getStudyMode(), period.getScope(), Set.copyOf(period.getCourses()),
                period.getGroupIds(), period.getId(), supervisors, suspends ? period.getRoomId() : null);
    }

    /**
     * The ONLINE period that decides the study mode among {@code active} (periods that already cover the date
     * and apply to the group): the most specific scope wins, between equally specific ones the later started
     * decision, then the larger id. Empty when no ONLINE period is active.
     */
    public static Optional<StudyPeriod> decidingStudyMode(Collection<StudyPeriod> active) {
        return active.stream()
                .filter(period -> period.effect() == PeriodEffect.ONLINE && period.studyMode() != null)
                .max(STUDY_MODE_PRECEDENCE);
    }

    public PeriodEffect effect() {
        return kind.effect();
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

    /** DAY_OFF or SUSPEND: nobody in scope has lessons. */
    public boolean closesLessons() {
        return effect().closesLessons();
    }

    /** DAY_OFF: a day off for everybody in scope; it wins over every other effect. */
    public boolean isDayOff() {
        return effect() == PeriodEffect.DAY_OFF;
    }

    /** SUSPEND: the groups in scope are elsewhere (a block of the day: practice, НС, сесія…). */
    public boolean suspends() {
        return effect() == PeriodEffect.SUSPEND;
    }

    /** ONLINE or MIXED study mode; whether it applies on a date is decided by {@link #decidingStudyMode}. */
    public boolean marksOnline() {
        return effect() == PeriodEffect.ONLINE
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
