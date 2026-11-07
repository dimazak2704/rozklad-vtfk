package ua.vtc.rozklad.core.substitution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Substitution state of a date.
 *
 * @param absences         active absences covering the date
 * @param uncoveredLessons SCHEDULED lessons of absent teachers without a decision (or only a declined one)
 * @param declined         declined changes that nobody has replaced with a new decision yet
 * @param overduePending   pending confirmations past their deadline
 * @param declinedChanges  the unresolved declined changes, for alerts
 */
public record SubstitutionDaySummary(
        LocalDate date,
        int absences,
        int uncoveredLessons,
        int pending,
        int confirmed,
        int declined,
        int overduePending,
        List<DeclinedChange> declinedChanges) {

    public SubstitutionDaySummary {
        declinedChanges = List.copyOf(declinedChanges);
    }

    /** {@code teacherName} is the teacher who declined (short name), may be {@code null}. */
    public record DeclinedChange(UUID changeId, String groupCode, int pairFrom, int pairTo, String teacherName) {
    }
}
