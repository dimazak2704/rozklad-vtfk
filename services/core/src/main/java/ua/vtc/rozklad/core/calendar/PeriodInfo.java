package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A calendar period that affects a date (holiday, practice, study mode, …). {@code orderNote} is
 * the sentence copied into orders («Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.»).
 */
public record PeriodInfo(
        UUID id,
        CalendarPeriodKind kind,
        String title,
        LocalDate startsOn,
        LocalDate endsOn,
        StudyMode studyMode,
        String orderNote,
        PeriodScope scope) {

    public static PeriodInfo of(CalendarPeriod period) {
        return new PeriodInfo(period.getId(), period.getKind(), period.getTitle(), period.getStartsOn(),
                period.getEndsOn(), period.getStudyMode(), period.getOrderNote(), period.getScope());
    }
}
