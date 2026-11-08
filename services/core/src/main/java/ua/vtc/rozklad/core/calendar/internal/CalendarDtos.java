package ua.vtc.rozklad.core.calendar.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.shared.web.Ref;

/** DTOs of the calendar admin API and its public endpoints. */
public final class CalendarDtos {

    public static final int MAX_PAIR = 8;
    public static final int MAX_WEEKDAY = 5;

    private CalendarDtos() {
    }

    /** {@code teachingWeeks} = {@code weeksCount} or the weeks the dates span. */
    public record SemesterDto(
            UUID id,
            UUID academicYearId,
            String academicYear,
            int number,
            String name,
            LocalDate startsOn,
            LocalDate endsOn,
            Parity firstWeekParity,
            Integer weeksCount,
            int teachingWeeks,
            UUID bellProfileId,
            Ref bellProfile,
            boolean active,
            long version) {
    }

    /**
     * @param academicYear    «2026/2027»; derived from {@code startsOn} when empty
     * @param name            «I семестр 2026/2027» when empty
     * @param firstWeekParity NUMERATOR when empty
     * @param bellProfileId   {@code null} = the default profile from settings
     */
    public record SemesterRequest(
            @Pattern(regexp = "\\d{4}/\\d{4}", message = "Навчальний рік має вигляд 2026/2027") String academicYear,
            @NotNull @Min(1) @Max(2) Integer number,
            @Size(max = 100) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            Parity firstWeekParity,
            @Min(1) @Max(53) Integer weeksCount,
            UUID bellProfileId,
            Long version) {
    }

    /** {@code computedParity} is the alternation rule; {@code parity} is what applies (override wins). */
    public record SemesterWeekDto(LocalDate weekStart, LocalDate weekEnd, int weekNumber, Parity parity,
                                  Parity computedParity, boolean overridden) {
    }

    /** {@code parity = null} removes the override. */
    public record WeekOverrideRequest(Parity parity) {
    }

    /** {@code defaultProfile} = the profile set as {@code activeBellProfileId} in settings. */
    public record BellProfileDto(UUID id, String name, boolean active, boolean defaultProfile,
                                 List<BellSlotDto> slots, long version) {
    }

    public record BellSlotDto(int weekday, int pair, LocalTime startsAt, LocalTime endsAt) {
    }

    public record BellProfileRequest(
            @NotBlank @Size(max = 100) String name,
            Boolean active,
            @NotNull @Size(min = 1, max = 40, message = "Додайте від 1 до 40 пар") List<@Valid @NotNull BellSlotRequest> slots,
            Long version) {
    }

    public record BellSlotRequest(
            @NotNull @Min(1) @Max(MAX_WEEKDAY) Integer weekday,
            @NotNull @Min(1) @Max(MAX_PAIR) Integer pair,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt) {
    }

    public record CalendarPeriodDto(
            UUID id,
            UUID semesterId,
            CalendarPeriodKind kind,
            String title,
            LocalDate startsOn,
            LocalDate endsOn,
            StudyMode studyMode,
            String orderNote,
            PeriodScope scope,
            List<Integer> courses,
            List<UUID> groupIds,
            List<GroupRef> groups,
            long version) {
    }

    /**
     * @param studyMode required for STUDY_MODE, empty for other kinds
     * @param scope     ALL when empty; COURSES needs {@code courses}, GROUPS needs {@code groupIds}
     */
    public record CalendarPeriodRequest(
            @NotNull UUID semesterId,
            @NotNull CalendarPeriodKind kind,
            @NotBlank @Size(max = 300) String title,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            StudyMode studyMode,
            @Size(max = 500) String orderNote,
            PeriodScope scope,
            List<@NotNull @Min(1) @Max(6) Integer> courses,
            List<@NotNull UUID> groupIds,
            Long version) {
    }

    /** Element of the bare JSON array of {@code GET/PUT /api/admin/shift-windows}; validated by the service. */
    public record ShiftWindowDto(Integer shift, Integer weekday, Integer firstPair, Integer lastPair) {
    }

    /** {@code GET /api/public/meta}; week fields are {@code null} when today is outside the semester. */
    public record PublicMetaDto(
            String collegeName,
            SemesterRefDto semester,
            LocalDate today,
            Integer weekNumber,
            Parity parity,
            boolean studyDay,
            PairDto currentPair,
            PairDto nextPair,
            ProfileRefDto bellProfile) {
    }

    public record SemesterRefDto(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {
    }

    /** {@code minutesLeft} exists only for the current pair. */
    public record PairDto(
            int number,
            LocalTime startsAt,
            LocalTime endsAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer minutesLeft) {
    }

    public record ProfileRefDto(UUID id, String name) {
    }

    /**
     * {@code GET /api/public/bells}: slots of the date's weekday from the profile in effect (empty
     * on weekends); {@code profiles} (all active profiles) only when no date was given.
     */
    public record BellsDto(
            ProfileRefDto profile,
            LocalDate date,
            int weekday,
            boolean studyDay,
            List<SlotDto> slots,
            List<BellProfileDto> profiles) {
    }

    public record SlotDto(int pair, LocalTime startsAt, LocalTime endsAt) {
    }
}
