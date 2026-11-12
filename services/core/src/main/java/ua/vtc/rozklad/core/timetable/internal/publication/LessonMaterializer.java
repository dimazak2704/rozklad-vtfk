package ua.vtc.rozklad.core.timetable.internal.publication;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.timetable.LessonConflictChecker;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;

/**
 * Replaces the dated lessons of a semester from a date on with the lessons of a version
 * (DOMAIN.md §6). Must run inside the publication transaction. A republication effective today
 * keeps today's pairs that already began (bell times, Kyiv clock): they took place, or are taking
 * place, and count in accounting, so the new version starts with the next pair.
 */
@Component
class LessonMaterializer {

    private final LessonMaterializationRepository repository;
    private final LessonRepository lessons;
    private final CalendarQueries calendar;
    private final LessonConflictChecker datedConflicts;
    private final EntityManager entityManager;
    private final Clock clock;
    private final MaterializationPlanner planner = new MaterializationPlanner();

    LessonMaterializer(LessonMaterializationRepository repository, LessonRepository lessons,
                       CalendarQueries calendar, LessonConflictChecker datedConflicts,
                       EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.lessons = lessons;
        this.calendar = calendar;
        this.datedConflicts = datedConflicts;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * @param replacesPublished another version was published: pairs of today that already began stay
     *                          as they are (the first publication may start in the past and fills it)
     */
    Outcome materialize(TimetableVersion version, Semester semester, List<TemplateLesson> template,
                        ConflictFacts facts, LocalDate from, boolean replacesPublished) {
        // JPA-зміни (архівування попередньої версії) мають бути в БД до масового SQL
        entityManager.flush();
        int heldPairs = replacesPublished ? pairsBegunOn(calendar, clock, from) : 0;
        BiPredicate<LocalDate, Integer> held = (date, pair) -> date.equals(from) && pair <= heldPairs;
        repository.deleteReplaceable(semester.getId(), from, heldPairs);
        List<KeptLesson> kept = repository.kept(semester.getId(), from);
        // пари, що вже почалися, не плануємо: їхні заняття лишаються зі старої версії
        MaterializationPlanner.Plan plan = planner.plan(new MaterializationPlanner.Input(semester.getId(),
                version.getId(), template, calendar.studyCalendar(semester.getId()), facts,
                kept.stream().filter(lesson -> !held.test(lesson.date(), lesson.pair())).toList(), from,
                semester.getEndsOn()));
        List<NewLesson> planned = plan.lessons().stream()
                .filter(lesson -> !held.test(lesson.date(), lesson.pair()))
                .toList();
        repository.insert(planned, clock.instant());
        // збій унікальності слотів тут, а не під час commit: план мав його виключити
        lessons.checkSlotConstraintsNow();

        List<ConflictDto> conflicts = new ArrayList<>(plan.clashes().stream()
                .filter(conflict -> conflict.date() == null || !held.test(conflict.date(), conflict.pair()))
                .toList());
        List<UUID> keptScheduled = kept.stream().filter(KeptLesson::scheduled).map(KeptLesson::id).toList();
        datedConflicts.conflictsOf(keptScheduled).stream()
                .filter(ConflictDto::isHard)
                .forEach(conflicts::add);
        List<ConflictDto> pending = repository.pinnedByOpenDecisions(semester.getId(), from).stream()
                .filter(lesson -> !version.getId().equals(lesson.versionId()))
                .map(LessonMaterializer::pendingDecision)
                .toList();
        return new Outcome(planned.size(), kept.size(), plan.coveredByChanges(), conflicts, pending,
                plan.dates());
    }

    /**
     * Pairs {@code 1..n} of {@code date} that already began when it is today; 0 on any other date.
     * {@link LessonRefresher} holds the same pairs of a day that a republication started.
     */
    static int pairsBegunOn(CalendarQueries calendar, Clock clock, LocalDate date) {
        if (!date.equals(LocalDate.now(clock))) {
            return 0;
        }
        LocalTime now = LocalTime.now(clock);
        return calendar.bellSlots(date).stream()
                .filter(slot -> !slot.startsAt().isAfter(now))
                .mapToInt(PairTime::pair)
                .max().orElse(0);
    }

    /**
     * A lesson of the previous version stays while an open decision is about it; once the decision
     * is cancelled or declined, the slot is refreshed with the new version ({@link LessonRefresher}).
     */
    private static ConflictDto pendingDecision(PinnedLesson lesson) {
        String what = "%s%s, %s, %d пара: %s%s".formatted(lesson.groupCode(),
                lesson.subgroup() > 0 ? " (підгр. " + lesson.subgroup() + ")" : "", UkrainianText.date(lesson.date()),
                lesson.pair(), lesson.disciplineName(), lesson.teacherName() == null ? "" : " " + lesson.teacherName());
        return new ConflictDto(ConflictKind.GROUP, ConflictDto.Severity.SOFT,
                what + " — лишилося з попереднього розкладу, бо про нього є відкрите рішення. Після скасування "
                        + "або відмови пару буде замінено новим розкладом.",
                List.of(lesson.id()), lesson.date(), null, lesson.pair());
    }

    record Outcome(int materialized, int kept, int coveredByChanges, List<ConflictDto> conflicts,
                   List<ConflictDto> pendingDecisions, List<LocalDate> dates) {
    }
}
