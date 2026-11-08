package ua.vtc.rozklad.core.substitution.internal.absence;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * Element of {@code GET /api/admin/absences/{id}/affected}: the date and a lesson of the absent teacher and
 * the latest decision about it ({@code null} or DECLINED = still needs a decision). {@link LessonView} has no
 * date, and an absence may span several days, so the date is given per item.
 * <p>
 * {@code asSubstitute == true} (additive): the absent teacher is not the lesson's teacher but the
 * substitute of the open change {@code decision}; that change can no longer happen (it cannot be
 * confirmed or published, 409 UNAVAILABLE) and the lesson needs a new decision once it is cancelled.
 */
public record AffectedLessonDto(LocalDate date, LessonView lesson, Decision decision, boolean asSubstitute) {

    public record Decision(UUID changeId, ChangeStatus status, ChangeKind kind) {
    }
}
