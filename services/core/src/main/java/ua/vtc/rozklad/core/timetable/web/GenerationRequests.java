package ua.vtc.rozklad.core.timetable.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationRequestCommand;

/** Request bodies of the generation API (CONTRACTS.md §2) with server-side validation. */
final class GenerationRequests {

    private GenerationRequests() {
    }

    /** {@code POST /api/admin/generation/jobs}. */
    record StartGeneration(
            @NotNull UUID semesterId,
            @Valid Scope scope,
            UUID baseVersionId,
            @NotNull @Min(10) @Max(600) Integer timeLimitSeconds,
            @Valid Weights weights) {

        GenerationRequestCommand toCommand() {
            return new GenerationRequestCommand(semesterId, scope == null ? null : scope.groupIds(),
                    scope == null ? null : scope.courses(), baseVersionId, timeLimitSeconds,
                    weights == null ? null : weights.toWeights());
        }
    }

    record Scope(List<@NotNull UUID> groupIds, List<@NotNull @Min(1) @Max(6) Integer> courses) {
    }

    /** Soft constraint weights; 0 switches a constraint off. */
    record Weights(@NotNull @Min(0) @Max(1000) Integer windows, @NotNull @Min(0) @Max(1000) Integer brokenDays,
                   @NotNull @Min(0) @Max(1000) Integer latePairs, @NotNull @Min(0) @Max(1000) Integer preferences,
                   @NotNull @Min(0) @Max(1000) Integer homeRoom) {

        GenerationWeights toWeights() {
            return new GenerationWeights(windows, brokenDays, latePairs, preferences, homeRoom);
        }
    }
}
