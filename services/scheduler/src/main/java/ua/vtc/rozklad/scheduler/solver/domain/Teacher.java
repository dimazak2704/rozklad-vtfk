package ua.vtc.rozklad.scheduler.solver.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Teacher (problem fact) with UNAVAILABLE (hard) and UNWANTED (soft) slots, an optional daily pair
 * limit and the number of teaching days per kind of week the base timetable gives the teacher (the
 * baseline of «Зайвий робочий день викладача»). Equality is by id.
 */
public final class Teacher {

    /** Pairs a day assumed for the baseline when the snapshot sends no limit. */
    public static final int DEFAULT_PAIRS_PER_DAY = 4;

    private final UUID id;
    private final String name;
    private final Set<Timeslot> unavailable;
    private final Set<Timeslot> unwanted;
    // 0 — без обмеження
    private final int maxPairsPerDay;
    // індекс — тип тижня (LessonUnit.NUMERATOR_WEEK / DENOMINATOR_WEEK); null — базу не задано
    private final int[] baseDays;

    public Teacher(UUID id, String name, Set<Timeslot> unavailable, Set<Timeslot> unwanted) {
        this(id, name, unavailable, unwanted, null, null);
    }

    /**
     * @param maxPairsPerDay daily limit, {@code null} = none
     * @param baseDays teaching days in the numerator and the denominator week the teacher may use
     *                 without a penalty, {@code null} = no baseline (the rule is off for the teacher)
     */
    public Teacher(UUID id, String name, Set<Timeslot> unavailable, Set<Timeslot> unwanted, Integer maxPairsPerDay,
                   int[] baseDays) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = name;
        this.unavailable = Set.copyOf(unavailable);
        this.unwanted = Set.copyOf(unwanted);
        if (maxPairsPerDay != null && maxPairsPerDay < 1) {
            throw new IllegalArgumentException("maxPairsPerDay must be positive: " + maxPairsPerDay);
        }
        this.maxPairsPerDay = maxPairsPerDay == null ? 0 : maxPairsPerDay;
        if (baseDays != null && baseDays.length != LessonUnit.WEEK_TYPES) {
            throw new IllegalArgumentException("baseDays needs one value per kind of week");
        }
        this.baseDays = baseDays == null ? null : baseDays.clone();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Timeslot> getUnavailable() {
        return unavailable;
    }

    public boolean isUnavailableAt(Timeslot timeslot) {
        return unavailable.contains(timeslot);
    }

    public boolean isUnwantedAt(Timeslot timeslot) {
        return unwanted.contains(timeslot);
    }

    /** The daily limit, {@code null} when the teacher has none. */
    public Integer getMaxPairsPerDay() {
        return maxPairsPerDay == 0 ? null : maxPairsPerDay;
    }

    /** Pairs above the daily limit (0 without a limit). */
    public int pairsAboveLimit(int pairs) {
        return maxPairsPerDay == 0 ? 0 : Math.max(0, pairs - maxPairsPerDay);
    }

    /** Baseline teaching days in a kind of week, {@code -1} without a baseline. */
    public int getBaseDays(int weekLane) {
        return baseDays == null ? -1 : baseDays[weekLane];
    }

    /** Teaching days above the baseline in a kind of week (0 without a baseline). */
    public int extraDays(int weekLane, int days) {
        return baseDays == null ? 0 : Math.max(0, days - baseDays[weekLane]);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Teacher teacher && id.equals(teacher.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
