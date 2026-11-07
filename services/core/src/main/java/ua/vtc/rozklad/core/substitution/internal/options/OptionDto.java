package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.shared.ref.DisciplineRef;
import ua.vtc.rozklad.core.shared.ref.RoomRef;
import ua.vtc.rozklad.core.shared.ref.TeacherRef;
import ua.vtc.rozklad.core.substitution.internal.view.LessonView;

/**
 * {@code Option} of CONTRACTS.md §2. Additive: {@code date} and {@code pair} of a make-up lesson
 * (ADDITIONAL), so the UI can create the change in one click.
 */
public record OptionDto(
        ChangeKind kind,
        int score,
        String title,
        List<String> reasons,
        TeacherRef teacher,
        DisciplineRef discipline,
        RoomRef room,
        LessonView swapLesson,
        List<String> warnings,
        LocalDate date,
        Integer pair) {

    /** {@code GET /api/admin/lessons/{lessonId}/options}. */
    public record LessonOptions(LessonView lesson, List<OptionDto> options) {
    }
}
