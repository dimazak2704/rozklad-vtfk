package ua.vtc.rozklad.core.accounting;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Plan / fact / forecast of one discipline of a group in a semester, in academic hours
 * (DOMAIN §9). {@code forecastFinishDate} is {@code null} when the plan is never reached.
 */
public record DisciplineProgress(
        UUID groupId,
        UUID disciplineId,
        int plan,
        int fact,
        int remaining,
        int forecast,
        AccountingStatus status,
        LocalDate forecastFinishDate) {

    public boolean isFinished() {
        return status == AccountingStatus.FINISHED;
    }

    /** Hours the scheduled lessons will not cover by the end of the semester; 0 when on track. */
    public int hoursBehind() {
        return Math.max(0, plan - forecast);
    }
}
