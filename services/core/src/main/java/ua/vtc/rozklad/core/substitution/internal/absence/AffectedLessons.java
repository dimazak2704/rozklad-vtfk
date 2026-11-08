package ua.vtc.rozklad.core.substitution.internal.absence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.view.LessonViews;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * Affected lessons of an absence (DOMAIN §7): SCHEDULED lessons of the teacher on the absent dates
 * and pairs, plus lessons already cancelled by an applied decision so the dispatcher still sees
 * what was decided.
 */
@Service
@Transactional(readOnly = true)
public class AffectedLessons {

    private final AbsenceRepository absences;
    private final LessonRepository lessons;
    private final LessonDecisions decisions;
    private final LessonViews views;

    public AffectedLessons(AbsenceRepository absences, LessonRepository lessons, LessonDecisions decisions,
                           LessonViews views) {
        this.absences = absences;
        this.lessons = lessons;
        this.decisions = decisions;
        this.views = views;
    }

    public List<AffectedLessonDto> of(UUID absenceId) {
        Absence absence = absences.findById(absenceId)
                .orElseThrow(() -> NotFoundException.of("Відсутність", absenceId));
        List<Lesson> covered = lessons.findAllByTeacherIdAndDateBetweenOrderByDateAscPairNumberAsc(
                        absence.getTeacherId(), absence.getDateFrom(), absence.getDateTo()).stream()
                .filter(lesson -> absence.covers(lesson.getDate(), lesson.getPairNumber()))
                .toList();
        Map<UUID, Change> decided = decisions.of(covered);
        List<Lesson> shown = covered.stream()
                .filter(lesson -> lesson.isScheduled() || decided.containsKey(lesson.getId()))
                .toList();
        List<LessonView> lessonViews = views.of(shown);
        List<AffectedLessonDto> result = new ArrayList<>(shown.size());
        for (int index = 0; index < shown.size(); index++) {
            Lesson lesson = shown.get(index);
            Change decision = decided.get(lesson.getId());
            result.add(new AffectedLessonDto(lesson.getDate(), lessonViews.get(index), decision == null ? null
                    : new AffectedLessonDto.Decision(decision.getId(), decision.getStatus(), decision.getKind())));
        }
        return result;
    }
}
