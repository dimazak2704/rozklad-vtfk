package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.version.MoveCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonCommand;

/** Request bodies of the timetable API (CONTRACTS.md §2) with server-side validation. */
final class TimetableRequests {

    static final int MAX_SUBGROUP = 9;
    static final int MAX_IMPORTED_LESSONS = 5000;

    private TimetableRequests() {
    }

    /** {@code POST /api/admin/timetable/versions}. */
    record CreateVersion(@NotNull UUID semesterId, @NotBlank @Size(max = 200) String name, UUID copyFromVersionId) {
    }

    /** {@code POST /api/admin/timetable/versions/{id}/publish}. */
    record Publish(@NotNull LocalDate effectiveFrom) {
    }

    /**
     * Body of {@code POST/PUT …/lessons}; {@code subgroup} 0 (default) = the whole group;
     * {@code version} (PUT) — the lesson's version the edit is based on (stale → 409).
     */
    record TemplateLesson(
            @NotNull @Min(1) @Max(5) Integer weekday,
            @NotNull @Min(1) @Max(8) Integer pair,
            @NotNull Parity parity,
            @NotNull UUID groupId,
            @Min(0) @Max(MAX_SUBGROUP) Integer subgroup,
            @NotNull UUID disciplineId,
            UUID teacherId,
            UUID roomId,
            LessonKind lessonKind,
            UUID loadItemId,
            Boolean pinned,
            Boolean online,
            UUID streamId,
            Long version) {

        TemplateLessonCommand toCommand() {
            return new TemplateLessonCommand(weekday, pair, parity, groupId, subgroup == null ? 0 : subgroup,
                    disciplineId, teacherId, roomId, lessonKind == null ? LessonKind.MIXED : lessonKind, loadItemId,
                    Boolean.TRUE.equals(pinned), Boolean.TRUE.equals(online), streamId);
        }
    }

    /**
     * {@code POST …/lessons/{lessonId}/move}; without {@code roomId} the room stays; {@code version} —
     * the lesson's version the move is based on (stale → 409).
     */
    record Move(@NotNull @Min(1) @Max(5) Integer weekday, @NotNull @Min(1) @Max(8) Integer pair,
                @NotNull Parity parity, UUID roomId, Long version) {

        MoveCommand toCommand() {
            return new MoveCommand(weekday, pair, parity, roomId);
        }
    }

    /** {@code POST /api/admin/import/timetable}: names instead of ids, resolved through the catalog. */
    record ImportTimetable(
            @NotNull UUID semesterId,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Size(max = MAX_IMPORTED_LESSONS) List<@NotNull @Valid ImportedLesson> lessons) {

        ImportTimetableCommand toCommand() {
            return new ImportTimetableCommand(semesterId, name, lessons.stream()
                    .map(lesson -> new ImportTimetableCommand.Lesson(lesson.weekday(), lesson.pair(), lesson.parity(),
                            lesson.groupCode(), lesson.subgroup() == null ? 0 : lesson.subgroup(),
                            lesson.disciplineShortName(), lesson.teacherShortName(), lesson.roomName(),
                            Boolean.TRUE.equals(lesson.online())))
                    .toList());
        }
    }

    record ImportedLesson(
            @NotNull @Min(1) @Max(5) Integer weekday,
            @NotNull @Min(1) @Max(8) Integer pair,
            @NotNull Parity parity,
            @NotBlank @Size(max = 16) String groupCode,
            @Min(0) @Max(MAX_SUBGROUP) Integer subgroup,
            @NotBlank @Size(max = 300) String disciplineShortName,
            @Size(max = 100) String teacherShortName,
            @Size(max = 50) String roomName,
            Boolean online) {
    }
}
