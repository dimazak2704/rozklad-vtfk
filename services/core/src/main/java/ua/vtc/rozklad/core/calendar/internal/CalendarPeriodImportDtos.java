package ua.vtc.rozklad.core.calendar.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.calendar.internal.PeriodKindDtos.LessonCounts;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/** {@code POST /api/admin/import/calendar-periods}: the college's academic calendar (графік освітнього процесу). */
public final class CalendarPeriodImportDtos {

    private CalendarPeriodImportDtos() {
    }

    /**
     * @param academicYear   «2026/2027»: periods are split over the semesters of this year
     * @param periods        every period of the year the file has (rows of earlier imports it lacks are deleted)
     * @param groupSemesters theory weeks and exams per group and semester number
     */
    public record CalendarPeriodImportRequest(
            @NotNull @Pattern(regexp = "\\d{4}/\\d{4}", message = "Навчальний рік має вигляд 2026/2027")
            String academicYear,
            @NotNull @Size(max = 3000, message = "Не більше 3000 періодів.")
            List<@Valid @NotNull ImportPeriod> periods,
            @Size(max = 1000, message = "Не більше 1000 рядків тижнів теорії.")
            List<@Valid @NotNull ImportGroupSemester> groupSemesters) {
    }

    /**
     * A period by natural keys: the kind by code or alias, groups by code, the room by name.
     *
     * @param sourceKey stable key of the item in the file, unique in the request
     * @param scope     ALL when empty; COURSES needs {@code courses}, GROUPS needs {@code groupCodes}
     */
    public record ImportPeriod(
            @NotBlank @Size(max = 200) String sourceKey,
            @NotBlank @Size(max = 10) String kindCode,
            @NotBlank @Size(max = 300) String title,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            PeriodScope scope,
            List<@NotBlank @Size(max = 16) String> groupCodes,
            List<@NotNull @Min(1) @Max(6) Integer> courses,
            @Size(max = 50) String roomName,
            StudyMode studyMode,
            @Size(max = 500) String orderNote) {
    }

    /** {@code theoryWeeks}/{@code exams} empty when the calendar leaves the cell empty. */
    public record ImportGroupSemester(
            @NotBlank @Size(max = 16) String groupCode,
            @NotNull @Min(1) @Max(2) Integer semesterNumber,
            @Min(1) @Max(53) Integer theoryWeeks,
            @Min(0) @Max(20) Integer exams) {
    }

    /** What happened to one stored part of an item (or to a stored row the request no longer has). */
    public enum ImportAction {
        CREATE,
        UPDATE,
        UNCHANGED,
        DELETE,
        SKIP_MANUAL
    }

    /**
     * Both modes. {@code canApply} is {@code false} when the real run would be refused (conflicts with live
     * changes); {@code lessons} counts dated lessons from today on.
     */
    public record CalendarPeriodImportResult(
            boolean dryRun,
            String academicYear,
            boolean canApply,
            int created,
            int updated,
            int unchanged,
            int deleted,
            int skippedManual,
            List<PeriodRow> periods,
            List<ManualRow> manual,
            List<ManualOverlap> manualOverlaps,
            List<OutOfSemester> outOfSemester,
            GroupSemesterCounts groupSemesters,
            List<ConflictDto> conflicts,
            LessonCounts lessons) {
    }

    /**
     * @param sourceKey the item's key ({@code storedKey} for a DELETE row)
     * @param storedKey {@code sourceKey}, or {@code sourceKey|S<n>} when the item spans several semesters
     * @param periodId  {@code null} only for CREATE in a dry run
     */
    public record PeriodRow(String sourceKey, String storedKey, ImportAction action, UUID periodId, UUID semesterId,
                            String kindCode, LocalDate startsOn, LocalDate endsOn, List<String> groupCodes) {
    }

    /** A row the dispatcher edited by hand: the import leaves it; {@code differs} names what the file has otherwise. */
    public record ManualRow(String storedKey, UUID periodId, String title, List<String> differs) {
    }

    /**
     * A manual period (no key, or a key the request no longer produces) with the same effect, a shared group and
     * overlapping dates (information only); {@code sourceKey} is the overlapping item of the request.
     */
    public record ManualOverlap(UUID periodId, String kindCode, String title, LocalDate startsOn, LocalDate endsOn,
                                List<String> groupCodes, String sourceKey) {
    }

    /** An item (its own dates) with Mon–Fri days outside every semester of the year; {@code kept} = stored parts. */
    public record OutOfSemester(String sourceKey, String kindCode, LocalDate startsOn, LocalDate endsOn,
                                List<String> groupCodes, List<KeptPart> kept) {
    }

    public record KeptPart(String storedKey, UUID semesterId, LocalDate startsOn, LocalDate endsOn) {
    }

    public record GroupSemesterCounts(int created, int updated, int unchanged, List<SkippedGroupSemester> skipped) {
    }

    public record SkippedGroupSemester(String groupCode, int semesterNumber, String reason) {
    }
}
