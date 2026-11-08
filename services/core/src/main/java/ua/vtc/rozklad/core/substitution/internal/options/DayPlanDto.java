package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * {@code GET /api/admin/absences/{id}/plan?date=}: every undecided lesson of the absent teacher on
 * the date, by pair, with its options and the proposed one ({@code proposedIndex} into
 * {@code options}, {@code null} for a past lesson or when nothing fits). Nothing is created or sent.
 */
public record DayPlanDto(LocalDate date, List<PlannedLesson> lessons) {

    public record PlannedLesson(LessonView lesson, List<OptionDto> options, Integer proposedIndex,
                                List<OptionDto.Declined> declined, boolean past) {
    }
}
