package ua.vtc.rozklad.core.accounting.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.BellSlot;
import ua.vtc.rozklad.core.calendar.BellSlotRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.time.StudyWeeks;

/** Semester resolution and "has this pair already ended" for the accounting module. */
@Component
class AccountingCalendar {

    private final SemesterRepository semesters;
    private final BellProfileRepository bellProfiles;
    private final BellSlotRepository bellSlots;
    private final Clock clock;

    AccountingCalendar(SemesterRepository semesters, BellProfileRepository bellProfiles, BellSlotRepository bellSlots,
                       Clock clock) {
        this.semesters = semesters;
        this.bellProfiles = bellProfiles;
        this.bellSlots = bellSlots;
        this.clock = clock;
    }

    /** The requested semester, or the active one, or the one containing today. */
    Semester semester(UUID semesterId) {
        if (semesterId != null) {
            return semesters.findById(semesterId).orElseThrow(() -> NotFoundException.of("Семестр", semesterId));
        }
        LocalDate today = LocalDate.now(clock);
        return semesters.findByActiveTrue()
                .or(() -> semesters.findContaining(today))
                .orElseThrow(() -> new BusinessRuleException("NO_ACTIVE_SEMESTER",
                        "Не знайдено активного семестру. Вкажіть семестр явно."));
    }

    /**
     * {@code (date, pair) -> ended}: past dates are over; today's pairs are over after their bell.
     * Without a bell profile today's pairs count as not ended yet.
     */
    BiPredicate<LocalDate, Integer> endedLessons(Semester semester) {
        LocalDate today = LocalDate.now(clock);
        LocalTime now = LocalTime.now(clock);
        Map<Integer, LocalTime> todayEnds = StudyWeeks.isWeekday(today)
                ? pairEnds(semester, today.getDayOfWeek().getValue())
                : Map.of();
        return (date, pair) -> date.isBefore(today)
                || (date.isEqual(today) && todayEnds.containsKey(pair) && !todayEnds.get(pair).isAfter(now));
    }

    private Map<Integer, LocalTime> pairEnds(Semester semester, int weekday) {
        return bellProfileId(semester)
                .map(profileId -> bellSlots.findDaySlots(profileId, weekday).stream()
                        .collect(Collectors.toMap(BellSlot::getPairNumber, BellSlot::getEndsAt)))
                .orElse(Map.of());
    }

    /** The semester's bell profile, otherwise the only active profile. */
    private Optional<UUID> bellProfileId(Semester semester) {
        if (semester.getBellProfileId() != null) {
            return Optional.of(semester.getBellProfileId());
        }
        List<BellProfile> active = bellProfiles.findAllByOrderByName().stream().filter(BellProfile::isActive).toList();
        return active.size() == 1 ? Optional.of(active.getFirst().getId()) : Optional.empty();
    }
}
