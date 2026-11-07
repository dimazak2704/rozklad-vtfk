package ua.vtc.rozklad.core.substitution.internal.preference;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ua.vtc.rozklad.core.substitution.PreferenceKind;

/** One slot of the teacher's preference grid: weekday 1..5 × pair 1..8, UNWANTED or UNAVAILABLE. */
public record PreferenceDto(
        @NotNull @Min(1) @Max(5) Integer weekday,
        @NotNull @Min(1) @Max(8) Integer pair,
        @NotNull PreferenceKind kind,
        @Size(max = 500) String note) {
}
