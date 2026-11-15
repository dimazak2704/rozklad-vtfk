package ua.vtc.rozklad.core.calendar;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * The days a group is on theory in one semester of the system (owner's rule of 15.11.2026): Monday–Friday dates of
 * the semester that no DAY_OFF or SUSPEND period of the group closes (holidays, practice, НС, session, training
 * camp…). Those weeks are not counted and not caught up later, so the plan of a discipline follows these days. Get it
 * from {@link StudyCalendar#theory}.
 *
 * <p>Days are kept per weekday and per week parity (overrides applied), so a pair of the timetable can be counted
 * exactly: a numerator-only pair misses only the numerator weeks the group spends off theory.
 *
 * @param semesterWeeks    Monday-based weeks of the semester
 * @param theoryWeeks      weeks with at least one study day of the group
 * @param numeratorWeeks   theory weeks with parity NUMERATOR
 * @param denominatorWeeks theory weeks with parity DENOMINATOR
 * @param studyDays        study days of the group in the semester
 * @param offPeriods       DAY_OFF and SUSPEND periods of the group that close at least one Monday–Friday date of the
 *                         semester, by start
 */
public record GroupTheory(
        UUID semesterId,
        UUID groupId,
        int semesterWeeks,
        int theoryWeeks,
        int numeratorWeeks,
        int denominatorWeeks,
        int studyDays,
        List<StudyPeriod> offPeriods,
        StudyDays days) {

    public GroupTheory {
        Objects.requireNonNull(semesterId, "semesterId");
        Objects.requireNonNull(groupId, "groupId");
        offPeriods = List.copyOf(offPeriods);
        Objects.requireNonNull(days, "days");
    }

    /** Study days of the group on {@code weekday} (1..5) in weeks of {@code parity}; BOTH = every week. */
    public int days(int weekday, Parity parity) {
        return days.count(weekday, parity);
    }

    /** Study days per weekday (1..5) and week parity (NUMERATOR, DENOMINATOR). Immutable. */
    public static final class StudyDays {

        private final int[] counts;

        private StudyDays(int[] counts) {
            this.counts = counts;
        }

        public static Builder builder() {
            return new Builder();
        }

        public int count(int weekday, Parity parity) {
            if (weekday < 1 || weekday > 5) {
                return 0;
            }
            return switch (Objects.requireNonNull(parity, "parity")) {
                case NUMERATOR -> counts[index(weekday, Parity.NUMERATOR)];
                case DENOMINATOR -> counts[index(weekday, Parity.DENOMINATOR)];
                case BOTH -> counts[index(weekday, Parity.NUMERATOR)] + counts[index(weekday, Parity.DENOMINATOR)];
            };
        }

        private static int index(int weekday, Parity parity) {
            return (weekday - 1) * 2 + (parity == Parity.NUMERATOR ? 0 : 1);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StudyDays days && Arrays.equals(counts, days.counts);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(counts);
        }

        @Override
        public String toString() {
            return Arrays.toString(counts);
        }

        /** Collects the days one by one. */
        public static final class Builder {

            private final int[] counts = new int[10];

            /** One study day; {@code parity} is NUMERATOR or DENOMINATOR. */
            public Builder add(int weekday, Parity parity) {
                if (weekday < 1 || weekday > 5 || parity == null || parity == Parity.BOTH) {
                    throw new IllegalArgumentException("a study day is Monday–Friday of a numerator or denominator week");
                }
                counts[index(weekday, parity)]++;
                return this;
            }

            public StudyDays build() {
                return new StudyDays(counts.clone());
            }
        }
    }
}
