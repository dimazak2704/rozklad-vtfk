package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog facts of a group for conflict checks and metrics.
 *
 * @param size           number of students, {@code null} when unknown (capacity check skipped)
 * @param subgroups      stored subgroup numbers (1..n); the whole group (0) is implicit
 * @param subgroupSizes  known sizes of subgroups by number
 */
public record GroupFact(UUID id, String code, int course, Integer size, int shift, Set<Integer> subgroups,
                        Map<Integer, Integer> subgroupSizes) {

    public GroupFact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        subgroups = subgroups == null ? Set.of() : Set.copyOf(subgroups);
        subgroupSizes = subgroupSizes == null ? Map.of() : Map.copyOf(subgroupSizes);
    }

    /** Students of a cohort: the group for 0, a subgroup otherwise; {@code null} when unknown. */
    public Integer cohortSize(int subgroup) {
        return subgroup == 0 ? size : subgroupSizes.get(subgroup);
    }
}
