package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/**
 * Answer of a draft edit: the lesson after the edit ({@code null} after a delete) and the conflicts
 * of the touched slots (hard and soft), so the editor can colour the grid at once.
 */
public record TemplateLessonMutationResponse(TemplateLessonDto lesson, List<ConflictDto> conflicts) {

    public TemplateLessonMutationResponse {
        conflicts = List.copyOf(conflicts);
    }
}
