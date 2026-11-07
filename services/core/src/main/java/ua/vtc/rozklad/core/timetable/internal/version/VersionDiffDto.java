package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.List;

/** {@code GET …/versions/{a}/diff/{b}}: what changes when going from version a to version b. */
public record VersionDiffDto(List<TemplateLessonDto> added, List<TemplateLessonDto> removed, List<MovedLesson> moved) {

    public VersionDiffDto {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        moved = List.copyOf(moved);
    }

    public record MovedLesson(TemplateLessonDto from, TemplateLessonDto to) {
    }
}
