package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.timetable.LessonView;

/**
 * {@code Option} of CONTRACTS.md §2, in server order. Additive fields: {@code band} and
 * {@code recommended} (only #1, only when clearly best), {@code suggestedRoom} (REPLACE_DISCIPLINE:
 * the substitute's own room when free, else the lesson's room; MERGE_SUBGROUPS: the room of the
 * merged class). {@code swapLesson} is the moved lesson of a SWAP or the other subgroup's lesson of
 * a MERGE_SUBGROUPS. {@code score} only reflects the position (100, 90, …) and is deprecated;
 * {@code date}/{@code pair} are always {@code null} now (make-up pairs are created by hand).
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
        Integer pair,
        Band band,
        boolean recommended,
        RoomRef suggestedRoom) {

    /**
     * {@code GET /api/admin/lessons/{lessonId}/options}; {@code declined}: who declined a request for
     * this lesson and when; {@code past}: the lesson is before today (record what happened, nothing
     * is recommended).
     */
    public record LessonOptions(LessonView lesson, List<OptionDto> options, List<Declined> declined, boolean past) {
    }

    /** A teacher who declined a request for the lesson. */
    public record Declined(TeacherRef teacher, Instant at) {
    }
}
