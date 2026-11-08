package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/** Week of a date inside a semester: number, parity (NUMERATOR / DENOMINATOR) and whether it was overridden. */
public record WeekInfo(UUID semesterId, int weekNumber, LocalDate weekStart, Parity parity, boolean overridden) {
}
