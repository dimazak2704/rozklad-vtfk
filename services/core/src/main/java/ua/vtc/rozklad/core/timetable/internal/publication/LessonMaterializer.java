package ua.vtc.rozklad.core.timetable.internal.publication;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
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
 * (DOMAIN.md §6). Must run inside the publication transaction.
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

    Outcome materialize(TimetableVersion version, Semester semester, List<TemplateLesson> template,
                        ConflictFacts facts, LocalDate from) {
        // JPA-зміни (архівування попередньої версії) мають бути в БД до масового SQL
        entityManager.flush();
        repository.deleteReplaceable(semester.getId(), from);
        List<KeptLesson> kept = repository.kept(semester.getId(), from);
        MaterializationPlanner.Plan plan = planner.plan(new MaterializationPlanner.Input(semester.getId(),
                version.getId(), template, calendar.studyCalendar(semester.getId()), facts, kept, from,
                semester.getEndsOn()));
        repository.insert(plan.lessons(), clock.instant());
        // збій унікальності слотів тут, а не під час commit: план мав його виключити
        lessons.checkSlotConstraintsNow();

        List<ConflictDto> conflicts = new ArrayList<>(plan.clashes());
        List<UUID> keptScheduled = kept.stream().filter(KeptLesson::scheduled).map(KeptLesson::id).toList();
        datedConflicts.conflictsOf(keptScheduled).stream()
                .filter(ConflictDto::isHard)
                .forEach(conflicts::add);
        List<ConflictDto> pending = repository.pinnedByOpenDecisions(semester.getId(), from).stream()
                .filter(lesson -> !version.getId().equals(lesson.versionId()))
                .map(LessonMaterializer::pendingDecision)
                .toList();
        return new Outcome(plan.lessons().size(), kept.size(), plan.coveredByChanges(), conflicts, pending,
                plan.dates());
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
