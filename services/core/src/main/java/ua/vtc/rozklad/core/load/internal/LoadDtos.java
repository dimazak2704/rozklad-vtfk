package ua.vtc.rozklad.core.load.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.load.ControlForm;
import ua.vtc.rozklad.core.load.LoadPattern;
import ua.vtc.rozklad.core.load.LoadSource;

/** DTOs of the load admin API, its import and the derivation from a timetable version. */
public final class LoadDtos {

    public static final String MAX_HOURS_PER_WEEK = "40.0";
    public static final int MAX_PLANNED_HOURS = 2000;

    private LoadDtos() {
    }

    /** {@code LoadItemDto} of CONTRACTS §2 plus {@code streamId}, {@code note} and {@code version}. */
    public record LoadItemDto(
            UUID id,
            UUID semesterId,
            GroupRef group,
            DisciplineRef discipline,
            LessonKind lessonKind,
            BigDecimal hoursPerWeek,
            int plannedHours,
            boolean splitSubgroups,
            List<LoadTeacherDto> teachers,
            RoomType requiredRoomType,
            RoomRef preferredRoom,
            ControlForm controlForm,
            LoadSource source,
            LoadPattern pattern,
            UUID streamId,
            String note,
            long version) {
    }

    public record LoadTeacherDto(int subgroup, TeacherRef teacher) {
    }

    /**
     * @param plannedHours {@code hoursPerWeek × teaching weeks} of the semester when empty
     * @param teachers     subgroup 0 for a whole group; 1..n of the group's subgroups when split
     */
    public record LoadItemRequest(
            @NotNull UUID semesterId,
            @NotNull UUID groupId,
            @NotNull UUID disciplineId,
            LessonKind lessonKind,
            @NotNull @DecimalMin(value = "0.1", message = "Годин на тиждень має бути більше 0")
            @DecimalMax(value = MAX_HOURS_PER_WEEK, message = "Не більше 40 годин на тиждень")
            @Digits(integer = 2, fraction = 1, message = "Години — число з одним знаком після коми, наприклад 1.5")
            BigDecimal hoursPerWeek,
            @Min(0) @Max(MAX_PLANNED_HOURS) Integer plannedHours,
            Boolean splitSubgroups,
            List<@Valid @NotNull LoadTeacherRequest> teachers,
            RoomType requiredRoomType,
            UUID preferredRoomId,
            ControlForm controlForm,
            UUID streamId,
            @Size(max = 1000) String note,
            Long version) {
    }

    public record LoadTeacherRequest(@NotNull @Min(0) @Max(9) Integer subgroup, @NotNull UUID teacherId) {
    }

    /**
     * {@code POST /api/admin/import/load}: items refer to the catalog by natural keys; the upsert
     * key is group × discipline × lesson kind × split.
     */
    public record LoadImportRequest(@NotNull UUID semesterId, @NotNull List<@Valid @NotNull LoadImportItem> items) {
    }

    /** {@code source} says where the numbers come from (curriculum, program, …) and is required. */
    public record LoadImportItem(
            @NotBlank @Size(max = 16) String groupCode,
            @NotBlank @Size(max = 64) String disciplineShortName,
            @NotNull @DecimalMin(value = "0.1", message = "Годин на тиждень має бути більше 0")
            @DecimalMax(value = MAX_HOURS_PER_WEEK, message = "Не більше 40 годин на тиждень")
            @Digits(integer = 2, fraction = 1, message = "Години — число з одним знаком після коми, наприклад 1.5")
            BigDecimal hoursPerWeek,
            @Min(0) @Max(MAX_PLANNED_HOURS) Integer plannedHours,
            LessonKind lessonKind,
            Boolean splitSubgroups,
            List<@Valid @NotNull LoadImportTeacher> teachers,
            ControlForm controlForm,
            RoomType requiredRoomType,
            @Size(max = 50) String preferredRoomName,
            @NotNull LoadSource source,
            @Size(max = 1000) String note) {
    }

    public record LoadImportTeacher(@NotNull @Min(0) @Max(9) Integer subgroup,
                                    @NotBlank @Size(max = 100) String teacherShortName) {
    }

    /** {@code ids}: «4КІ1 | СТП | MIXED | whole» → id. */
    public record LoadImportResult(int created, int updated, int unchanged, Map<String, UUID> ids) {
    }

    /**
     * Result of {@code POST /api/admin/load/derive-from-version/{versionId}}: the derived items of the
     * semester after the run and what happened.
     */
    public record DerivedLoadResult(UUID versionId, UUID semesterId, int created, int updated, int removed,
                                    List<SkippedDerivation> skipped, List<LoadItemDto> items) {
    }

    /** A group × discipline that already has load entered by hand or from a curriculum. */
    public record SkippedDerivation(GroupRef group, DisciplineRef discipline, String reason) {
    }
}
