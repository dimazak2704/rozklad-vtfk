package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Calendar facts of one date for a group (or for the whole college).
 *
 * @param parity     NUMERATOR / DENOMINATOR, {@code null} outside every semester
 * @param studyDay   Monday–Friday inside a semester and not closed by a DAY_OFF or SUSPEND period
 * @param online     an ONLINE period (online / mixed) applies, independently of SUSPEND
 * @param note       titles of the periods that apply, joined with «; », or {@code null}
 * @param semesterId semester containing the date, {@code null} outside every semester
 */
public record DayInfo(LocalDate date, int weekday, Parity parity, int weekNumber, boolean studyDay, boolean online,
                      String note, UUID semesterId) {
}
