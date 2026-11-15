package ua.vtc.rozklad.core.calendar.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodKind;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/** DTOs of {@code /api/admin/period-kinds} (CONTRACTS §2, period kinds). */
public final class PeriodKindDtos {

    static final String CODE_PATTERN = "^[\\p{L}\\p{N}]{1,10}$";
    static final String CODE_MESSAGE = "Код — від 1 до 10 літер чи цифр, без пробілів.";

    private PeriodKindDtos() {
    }

    /** {@code usage}: number of calendar periods of this kind. */
    public record PeriodKindDto(UUID id, String code, String name, PeriodEffect effect, String color,
                                List<String> aliases, String noteTemplate, boolean builtin, boolean active,
                                int position, long usage, long version) {

        static PeriodKindDto of(PeriodKind kind, long usage) {
            return new PeriodKindDto(kind.getId(), kind.getCode(), kind.getName(), kind.getEffect(), kind.getColor(),
                    kind.getAliases(), kind.getNoteTemplate(), kind.isBuiltin(), kind.isActive(), kind.getPosition(),
                    usage, kind.getVersion());
        }
    }

    /**
     * @param effect   DAY_OFF | SUSPEND | ONLINE | INFO (BLOCK is not accepted yet)
     * @param color    {@code #RRGGBB} or empty (none); stored upper case
     * @param aliases  other spellings of the code («З» for «ЗУ»); empty = none
     * @param active   {@code true} when empty on create, unchanged when empty on update
     * @param position order in lists; the last + 10 when empty on create, unchanged when empty on update
     * @param version  the version the edit is based on; an older one → 409 {@code stale-data}
     */
    public record PeriodKindRequest(
            @NotNull(message = CODE_MESSAGE) @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull PeriodEffect effect,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Колір має вигляд #RRGGBB.") String color,
            @Size(max = 10, message = "Не більше 10 псевдонімів.")
            List<@NotNull(message = CODE_MESSAGE) @Pattern(regexp = CODE_PATTERN, message = CODE_MESSAGE) String> aliases,
            @Size(max = 500) String noteTemplate,
            Boolean active,
            @Min(0) @Max(9999) Integer position,
            Long version) {
    }

    /**
     * {@code PUT …/period-kinds/{id}?dryRun=true}: what the edit would do, nothing saved.
     *
     * @param kind          the kind as it would be saved
     * @param effectChanged the effect differs from the stored one
     * @param periods       number of periods of the kind
     * @param dates         Mon–Fri dates of those periods (sorted)
     * @param groups        groups in their scopes (sorted by code)
     * @param lessons       dated lessons an effect change would add, remove and update (from today on)
     * @param conflicts     lessons of live changes the effect change would clash with (409 on apply)
     * @param blockers      reasons the effect change is refused (409 on apply)
     */
    public record PeriodKindImpactDto(PeriodKindDto kind, boolean effectChanged, int periods, List<LocalDate> dates,
                                      List<GroupRef> groups, LessonCounts lessons, List<ConflictDto> conflicts,
                                      List<String> blockers, boolean canApply) {
    }

    /** Dated lessons a calendar edit adds, removes and updates. */
    public record LessonCounts(int added, int removed, int updated) {

        static final LessonCounts NONE = new LessonCounts(0, 0, 0);
    }
}
