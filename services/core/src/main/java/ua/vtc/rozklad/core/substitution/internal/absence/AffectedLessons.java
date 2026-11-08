package ua.vtc.rozklad.core.substitution.internal.absence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.internal.effect.AbsentSubstitutes;
import ua.vtc.rozklad.core.substitution.internal.view.LessonViews;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * Affected lessons of an absence (DOMAIN §7): SCHEDULED lessons of the teacher on the absent dates
 * and pairs, plus lessons already cancelled by an applied decision so the dispatcher still sees
 * what was decided, plus lessons the teacher was to take over by a not yet published change
 * ({@code asSubstitute}): the absence makes that change impossible.
 */
@Service
@Transactional(readOnly = true)
public class AffectedLessons {

    private static final Set<ChangeStatus> OPEN = EnumSet.of(ChangeStatus.DRAFT, ChangeStatus.PENDING_CONFIRMATION,
            ChangeStatus.CONFIRMED);

    private final AbsenceRepository absences;
    private final LessonRepository lessons;
    private final LessonDecisions decisions;
    private final LessonViews views;
    private final ChangeRepository changes;

    public AffectedLessons(AbsenceRepository absences, LessonRepository lessons, LessonDecisions decisions,
                           LessonViews views, ChangeRepository changes) {
        this.absences = absences;
        this.lessons = lessons;
        this.decisions = decisions;
        this.views = views;
        this.changes = changes;
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
            result.add(new AffectedLessonDto(lesson.getDate(), lessonViews.get(index),
                    decision(decided.get(lesson.getId())), false));
        }
        result.addAll(substitutions(absence));
        result.sort(Comparator.comparing(AffectedLessonDto::date)
                .thenComparingInt(item -> item.lesson().pair())
                .thenComparing(AffectedLessonDto::asSubstitute));
        return result;
    }

    /** Open changes that make the absent teacher a substitute at one of the absent pairs. */
    private List<AffectedLessonDto> substitutions(Absence absence) {
        Map<UUID, Change> byLesson = new LinkedHashMap<>();
        changes.findAllByNewTeacherIdAndStatusIn(absence.getTeacherId(), OPEN).stream()
                .filter(AbsentSubstitutes::hasSubstitute)
                .filter(change -> change.getOriginalLessonId() != null)
                .filter(change -> IntStream.rangeClosed(change.getPairFrom(), change.getPairTo())
                        .anyMatch(pair -> absence.covers(change.getDate(), pair)))
                .forEach(change -> byLesson.put(change.getOriginalLessonId(), change));
        if (byLesson.isEmpty()) {
            return List.of();
        }
        List<Lesson> originals = lessons.findAllById(byLesson.keySet());
        List<LessonView> originalViews = views.of(originals);
        List<AffectedLessonDto> result = new ArrayList<>(originals.size());
        for (int index = 0; index < originals.size(); index++) {
            Lesson lesson = originals.get(index);
            result.add(new AffectedLessonDto(lesson.getDate(), originalViews.get(index),
                    decision(byLesson.get(lesson.getId())), true));
        }
        return result;
    }

    private static AffectedLessonDto.Decision decision(Change change) {
        return change == null ? null
                : new AffectedLessonDto.Decision(change.getId(), change.getStatus(), change.getKind());
    }
}
