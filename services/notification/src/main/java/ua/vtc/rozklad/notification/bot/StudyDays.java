package ua.vtc.rozklad.notification.bot;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Monday–Friday stepping (holidays are core's business: such a day just shows no lessons). */
final class StudyDays {

    private StudyDays() {
    }

    static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static LocalDate onOrAfter(LocalDate date) {
        LocalDate day = date;
        while (!isWeekday(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    static LocalDate next(LocalDate date) {
        return onOrAfter(date.plusDays(1));
    }

    static LocalDate previous(LocalDate date) {
        LocalDate day = date.minusDays(1);
        while (!isWeekday(day)) {
            day = day.minusDays(1);
        }
        return day;
    }
}
