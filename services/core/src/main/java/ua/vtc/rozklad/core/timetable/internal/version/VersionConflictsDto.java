package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/** {@code GET …/versions/{id}/conflicts}: hard violations and soft warnings. */
public record VersionConflictsDto(List<ConflictDto> hard, List<ConflictDto> soft) {

    public VersionConflictsDto {
        hard = List.copyOf(hard);
        soft = List.copyOf(soft);
    }
}
