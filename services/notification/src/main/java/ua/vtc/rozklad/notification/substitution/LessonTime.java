package ua.vtc.rozklad.notification.substitution;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;

/** When a substituted lesson is over and no longer worth a message. */
final class LessonTime {

    private LessonTime() {
    }

    /** The lesson ended: an earlier day, or today after its end (when the end time is known). */
    static boolean isOver(Clock clock, LocalDate date, LocalTime endsAt) {
        LocalDate today = LocalDate.now(clock);
        return date.isBefore(today)
                || date.equals(today) && endsAt != null && !endsAt.isAfter(LocalTime.now(clock));
    }
}
