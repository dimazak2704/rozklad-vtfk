package ua.vtc.rozklad.core.substitution.internal.overview;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.SubstitutionDaySummary;
import ua.vtc.rozklad.core.substitution.SubstitutionOverview;
import ua.vtc.rozklad.core.substitution.internal.absence.LessonDecisions;
import ua.vtc.rozklad.core.substitution.internal.support.CatalogLookup;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/** Substitution figures of a date for the dashboard (implementation of {@link SubstitutionOverview}). */
@Service
@Transactional(readOnly = true)
class DailySubstitutionSummary implements SubstitutionOverview {

    private final AbsenceRepository absences;
    private final LessonRepository lessons;
    private final ChangeRepository changes;
    private final LessonDecisions decisions;
    private final CatalogLookup catalog;
    private final Clock clock;

    DailySubstitutionSummary(AbsenceRepository absences, LessonRepository lessons, ChangeRepository changes,
                             LessonDecisions decisions, CatalogLookup catalog, Clock clock) {
        this.absences = absences;
        this.lessons = lessons;
        this.changes = changes;
        this.decisions = decisions;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public SubstitutionDaySummary summarize(LocalDate date) {
        List<Absence> active = absences.findActiveOverlapping(date, date, null);
        Map<UUID, List<Absence>> absencesByTeacher = active.stream()
                .collect(Collectors.groupingBy(Absence::getTeacherId));
        List<Lesson> affected = lessons.findAllByDateAndStatus(date, LessonStatus.SCHEDULED).stream()
                .filter(lesson -> lesson.getTeacherId() != null)
                .filter(lesson -> absencesByTeacher.getOrDefault(lesson.getTeacherId(), List.of()).stream()
                        .anyMatch(absence -> absence.covers(date, lesson.getPairNumber())))
                .toList();
        Map<UUID, Change> decided = decisions.of(affected);
        int uncovered = (int) affected.stream()
                .filter(lesson -> LessonDecisions.needsDecision(decided.get(lesson.getId())))
                .count();

        List<Change> dayChanges = changes.findAllByDateOrderByCreatedAt(date);
        Instant now = clock.instant();
        List<Change> unresolvedDeclined = dayChanges.stream()
                .filter(change -> change.getStatus() == ChangeStatus.DECLINED)
                .filter(change -> dayChanges.stream().noneMatch(other -> replaces(other, change)))
                .toList();
        return new SubstitutionDaySummary(
                date,
                active.size(),
                uncovered,
                count(dayChanges, ChangeStatus.PENDING_CONFIRMATION),
                count(dayChanges, ChangeStatus.CONFIRMED),
                unresolvedDeclined.size(),
                (int) dayChanges.stream()
                        .filter(change -> change.getStatus() == ChangeStatus.PENDING_CONFIRMATION)
                        .filter(change -> change.getConfirmDeadline() != null
                                && change.getConfirmDeadline().isBefore(now))
                        .count(),
                declinedDetails(unresolvedDeclined));
    }

    /** A later live decision about the same lesson (or the same slot for changes without a lesson). */
    private static boolean replaces(Change other, Change declined) {
        if (other.getId().equals(declined.getId()) || other.getStatus() == ChangeStatus.CANCELLED
                || other.getStatus() == ChangeStatus.DECLINED || other.getCreatedAt().isBefore(declined.getCreatedAt())) {
            return false;
        }
        if (declined.getOriginalLessonId() != null) {
            return declined.getOriginalLessonId().equals(other.getOriginalLessonId());
        }
        return other.getGroupId().equals(declined.getGroupId()) && other.getPairFrom() == declined.getPairFrom()
                && other.getSubgroupNumber() == declined.getSubgroupNumber();
    }

    private List<SubstitutionDaySummary.DeclinedChange> declinedDetails(List<Change> declined) {
        Map<UUID, StudentGroup> groups = catalog.groups(declined.stream().map(Change::getGroupId).toList());
        Map<UUID, Teacher> teachers = catalog.teachers(declined.stream().map(Change::getNewTeacherId)
                .filter(Objects::nonNull).toList());
        return declined.stream().map(change -> new SubstitutionDaySummary.DeclinedChange(change.getId(),
                groups.get(change.getGroupId()).getCode(), change.getPairFrom(), change.getPairTo(),
                change.getNewTeacherId() == null ? null : teachers.get(change.getNewTeacherId()).getShortName()))
                .toList();
    }

    private static int count(List<Change> changes, ChangeStatus status) {
        return (int) changes.stream().filter(change -> change.getStatus() == status).count();
    }
}
