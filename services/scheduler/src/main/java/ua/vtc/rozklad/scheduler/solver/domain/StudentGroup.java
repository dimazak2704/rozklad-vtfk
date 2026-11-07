package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Student group (problem fact). Students are split into cohorts: the stored subgroups (1..n) or
 * a single cohort 0 when the group is not split; a whole-group lesson (subgroup 0) belongs to
 * every cohort. {@code size} 0 means unknown. Equality is by id.
 */
public final class StudentGroup {

    public static final int MAX_COHORTS = 8;

    private final UUID id;
    private final String code;
    private final int size;
    private final int shift;
    private final List<Integer> cohorts;
    private final int[] allCohortIndexes;
    private final ShiftWindow window;

    public StudentGroup(UUID id, String code, int size, int shift, List<Integer> subgroups, ShiftWindow window) {
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

    public boolean hasSubgroup(int subgroup) {
        return subgroup == 0 || (isSplit() && cohorts.contains(subgroup));
    }

    public ShiftWindow getWindow() {
        return window;
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
