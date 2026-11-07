package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.events.GenerationWeights;

/**
 * A request to generate a timetable (CONTRACTS.md §2). Empty {@code groupIds} and {@code courses}
 * mean every active group; when both are given their union is generated.
 *
 * @param baseVersionId version whose lessons stay for groups outside the scope (default: the published one)
 * @param weights       soft constraint weights, {@code null} = defaults
 */
public record GenerationRequestCommand(UUID semesterId, List<UUID> groupIds, List<Integer> courses,
                                       UUID baseVersionId, int timeLimitSeconds, GenerationWeights weights) {

    public GenerationRequestCommand {
        Objects.requireNonNull(semesterId, "semesterId");
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        courses = courses == null ? List.of() : List.copyOf(courses);
    }
}
