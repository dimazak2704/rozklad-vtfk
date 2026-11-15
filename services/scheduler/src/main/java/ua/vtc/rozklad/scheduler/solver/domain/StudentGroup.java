package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Student group (problem fact). Students are split into cohorts: the stored subgroups (1..n) or
 * a single cohort 0 when the group is not split; a whole-group lesson (subgroup 0) belongs to
 * every cohort. {@code size} 0 means unknown. The group may have a limit of pairs on Friday (owner's rule of
 * 15.11.2026, Friday is an unloading day). Equality is by id.
 */
public final class StudentGroup {

    public static final int MAX_COHORTS = 8;
    /** The weekday of the Friday limit. */
    public static final int FRIDAY = 5;

    private final UUID id;
    private final String code;
    private final int size;
    private final int shift;
    private final List<Integer> cohorts;
    private final int[] allCohortIndexes;
    private final ShiftWindow window;
    // 0 — без обмеження
    private final int fridayMaxPairs;

    public StudentGroup(UUID id, String code, int size, int shift, List<Integer> subgroups, ShiftWindow window) {
        this(id, code, size, shift, subgroups, window, null);
    }

    /** @param fridayMaxPairs most pairs on Friday, {@code null} = no limit */
    public StudentGroup(UUID id, String code, int size, int shift, List<Integer> subgroups, ShiftWindow window,
                        Integer fridayMaxPairs) {
        this.id = Objects.requireNonNull(id, "id");
        this.code = code;
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        this.size = size;
        this.shift = shift;
        this.cohorts = subgroups.isEmpty() ? List.of(0) : List.copyOf(subgroups);
        if (cohorts.size() > MAX_COHORTS) {
            throw new IllegalArgumentException("at most " + MAX_COHORTS + " subgroups are supported");
        }
        this.allCohortIndexes = IntStream.range(0, cohorts.size()).toArray();
        this.window = Objects.requireNonNull(window, "window");
        if (fridayMaxPairs != null && fridayMaxPairs < 1) {
            throw new IllegalArgumentException("fridayMaxPairs must be positive: " + fridayMaxPairs);
        }
        this.fridayMaxPairs = fridayMaxPairs == null ? 0 : fridayMaxPairs;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public int getSize() {
        return size;
    }

    public int getShift() {
        return shift;
    }

    public boolean hasKnownSize() {
        return size > 0;
    }

    public boolean isSplit() {
        return !cohorts.equals(List.of(0));
    }

    /** Number of student cohorts: the stored subgroups, or 1 when the group is not split. */
    public int cohortCount() {
        return cohorts.size();
    }

    public boolean hasSubgroup(int subgroup) {
        return subgroup == 0 || (isSplit() && cohorts.contains(subgroup));
    }

    public ShiftWindow getWindow() {
        return window;
    }

    /** The Friday limit, {@code null} when the group has none. */
    public Integer getFridayMaxPairs() {
        return fridayMaxPairs == 0 ? null : fridayMaxPairs;
    }

    /** Pairs above the group's limit for that weekday (only Friday has one; 0 without a limit). */
    public int pairsAboveDayLimit(int weekday, int pairs) {
        return weekday != FRIDAY || fridayMaxPairs == 0 ? 0 : Math.max(0, pairs - fridayMaxPairs);
    }

    public boolean allows(Timeslot timeslot) {
        return window.contains(timeslot);
    }

    /** Indexes of the cohorts that attend a lesson of {@code subgroup} (0 = the whole group). */
    public int[] cohortIndexesOf(int subgroup) {
        if (subgroup == 0) {
            return allCohortIndexes.clone();
        }
        int index = isSplit() ? cohorts.indexOf(subgroup) : -1;
        if (index < 0) {
            throw new IllegalArgumentException("group " + code + " has no subgroup " + subgroup);
        }
        return new int[] {index};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StudentGroup group && id.equals(group.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return code;
    }
}
