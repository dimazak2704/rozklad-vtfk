package ua.vtc.rozklad.core.accounting.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/** Semester resolution and "has this pair already ended" for the accounting module, via {@link CalendarQueries}. */
@Component
class AccountingCalendar {

    private final CalendarQueries calendar;
    private final Clock clock;

    AccountingCalendar(CalendarQueries calendar, Clock clock) {
        this.calendar = calendar;
        this.clock = clock;
    }

    /** The requested semester, or the active one, or the one containing today. */
    SemesterInfo semester(UUID semesterId) {
        if (semesterId != null) {
            return calendar.semester(semesterId).orElseThrow(() -> NotFoundException.of("Семестр", semesterId));
        }
        LocalDate today = LocalDate.now(clock);
        return calendar.activeSemester()
                .or(() -> calendar.semesterOn(today))
                .orElseThrow(() -> new BusinessRuleException("NO_ACTIVE_SEMESTER",
                        "Не знайдено активного семестру. Вкажіть семестр явно."));
    }

    /**
     * {@code (date, pair) -> ended}: past dates are over; today's pairs are over after their bell.
     * Without a bell profile today's pairs count as not ended yet.
     */
    BiPredicate<LocalDate, Integer> endedLessons() {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);
        Map<Integer, LocalTime> todayEnds = calendar.bellSlots(today).stream()
                .collect(Collectors.toMap(PairTime::pair, PairTime::endsAt));
        return (date, pair) -> date.isBefore(today)
                || (date.isEqual(today) && todayEnds.containsKey(pair) && !todayEnds.get(pair).isAfter(now));
    }
}
