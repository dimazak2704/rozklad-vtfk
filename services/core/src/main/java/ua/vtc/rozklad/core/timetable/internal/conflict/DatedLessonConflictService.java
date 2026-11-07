package ua.vtc.rozklad.core.timetable.internal.conflict;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.timetable.LessonConflictChecker;
import ua.vtc.rozklad.core.timetable.PlannedLesson;

/**
 * {@link LessonConflictChecker} for dated lessons: the same {@link ConflictEngine} as the template
 * editor, fed with the SCHEDULED lessons of the affected dates. Pending JPA changes of the caller's
 * transaction are flushed first so the SQL reads see them.
 */
@Service
@Transactional(readOnly = true)
public class DatedLessonConflictService implements LessonConflictChecker {

    private final DatedLessonReadRepository lessons;
    private final ConflictFactsLoader factsLoader;
    private final ConflictEngine engine;
    private final SemesterRepository semesters;
    private final EntityManager entityManager;

    DatedLessonConflictService(DatedLessonReadRepository lessons, ConflictFactsLoader factsLoader,
                               ConflictEngine engine, SemesterRepository semesters, EntityManager entityManager) {
        this.lessons = lessons;
        this.factsLoader = factsLoader;
        this.engine = engine;
        this.semesters = semesters;
        this.entityManager = entityManager;
    }

    @Override
    public List<ConflictDto> conflictsOf(Collection<UUID> lessonIds) {
        if (lessonIds.isEmpty()) {
            return List.of();
        }
        flushPendingChanges();
        Set<LocalDate> dates = lessons.scheduledByIds(lessonIds).stream()
                .map(dated -> dated.lesson().day().date())
                .collect(Collectors.toSet());
        Map<UUID, List<SlotLesson>> bySemester = new LinkedHashMap<>();
        lessons.scheduledOn(dates).forEach(dated -> bySemester
                .computeIfAbsent(dated.semesterId(), id -> new ArrayList<>()).add(dated.lesson()));
        return check(bySemester, lessonIds);
    }

    @Override
    public List<ConflictDto> preview(Collection<PlannedLesson> planned, Collection<UUID> ignoredLessonIds) {
        if (planned.isEmpty()) {
            return List.of();
        }
        flushPendingChanges();
        Set<UUID> plannedIds = planned.stream().map(PlannedLesson::id).collect(Collectors.toSet());
        Set<UUID> skipped = new HashSet<>(ignoredLessonIds);
        skipped.addAll(plannedIds);
        Set<LocalDate> dates = planned.stream().map(PlannedLesson::date).collect(Collectors.toSet());
        Map<UUID, List<SlotLesson>> bySemester = new LinkedHashMap<>();
        lessons.scheduledOn(dates).stream()
                .filter(dated -> !skipped.contains(dated.lesson().id()))
                .forEach(dated -> bySemester.computeIfAbsent(dated.semesterId(), id -> new ArrayList<>())
                        .add(dated.lesson()));
        for (PlannedLesson lesson : planned) {
            UUID semesterId = semesters.findContaining(lesson.date()).map(Semester::getId).orElse(null);
            bySemester.computeIfAbsent(semesterId, id -> new ArrayList<>()).add(toSlotLesson(lesson));
        }
        return check(bySemester, plannedIds);
    }

    private List<ConflictDto> check(Map<UUID, List<SlotLesson>> bySemester, Collection<UUID> involved) {
        List<ConflictDto> conflicts = new ArrayList<>();
        bySemester.forEach((semesterId, semesterLessons) -> {
            ConflictReport report = engine.check(semesterLessons, factsLoader.load(semesterLessons, semesterId));
            conflicts.addAll(report.involving(involved).all());
        });
        return List.copyOf(conflicts);
    }

    private void flushPendingChanges() {
        if (entityManager.isJoinedToTransaction()) {
            entityManager.flush();
        }
    }

    private static SlotLesson toSlotLesson(PlannedLesson lesson) {
        return new SlotLesson(lesson.id(), LessonDay.ofDate(lesson.date()), lesson.pair(), Parity.BOTH,
                lesson.groupId(), lesson.subgroup(), lesson.disciplineId(), lesson.teacherId(), lesson.roomId(),
                lesson.streamId(), null);
    }
}
