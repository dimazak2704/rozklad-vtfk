package ua.vtc.rozklad.core.settings;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;

/**
 * How far back anonymous visitors see substitutions and orders (owner decision 14.11.2026): from
 * {@link #from()} = today − {@code public.changesHistoryDays} on, every future date included. Signed-in
 * staff (ADMIN, TEACHER) see the whole archive, and so does the Telegram bot (SERVICE_NOTIFICATION), which
 * applies the window to its other chats itself. "Today" comes from core's {@code Clock} (stand time follows it).
 *
 * <p>Schedules overlay substitutions too, so they are limited by whole weeks: an anonymous range may start no
 * earlier than {@link #weekFrom()}, the Monday of the week of {@link #from()}.
 */
public interface PublicHistory {

    /** First date anonymous visitors may open; the same for every caller ({@code publicHistoryFrom} of the meta). */
    LocalDate from();

    /** Monday of the week of {@link #from()}: the earliest start of an anonymous schedule range. */
    default LocalDate weekFrom() {
        return weekStart(from());
    }

    /** Whether the current request sees the whole archive: a signed-in ADMIN or TEACHER, or the bot. */
    boolean fullArchive();

    /** Earliest date of a schedule the current caller may get: empty for the full archive, else {@link #weekFrom()}. */
    default Optional<LocalDate> scheduleLimit() {
        return fullArchive() ? Optional.empty() : Optional.of(weekFrom());
    }

    /**
     * Substitutions of {@code date} for the current caller.
     *
     * @throws ua.vtc.rozklad.core.shared.error.HistoryNotPublicException an anonymous caller and a date before {@link #from()}
     */
    void requireDate(LocalDate date);

    /**
     * A schedule range that starts on {@code rangeFrom} for the current caller.
     *
     * @throws ua.vtc.rozklad.core.shared.error.HistoryNotPublicException an anonymous caller and a start before {@link #weekFrom()}
     */
    void requireScheduleFrom(LocalDate rangeFrom);

    static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
