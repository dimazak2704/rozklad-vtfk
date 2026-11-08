package ua.vtc.rozklad.core.calendar.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupInfo;

/**
 * Publishes {@code schedule.changed} (reason CALENDAR_CHANGED) when a calendar edit changes which
 * lessons happen or how they look: parity overrides and calendar periods. The event goes through
 * the outbox, so it must be called inside the transaction of the edit. Consumers (timetable
 * materialization, notification caches) get the Mon–Fri dates and the groups affected.
 */
@Component
class CalendarChangePublisher {

    private final ApplicationEventPublisher events;
    private final CatalogQueries catalog;
    private final Clock clock;

    CalendarChangePublisher(ApplicationEventPublisher events, CatalogQueries catalog, Clock clock) {
        this.events = events;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Dates of every period (before and after an edit) and the union of their groups. */
    void periodsChanged(Collection<PeriodSnapshot> periods, LocalDate semesterStart, LocalDate semesterEnd) {
        SortedSet<LocalDate> dates = new TreeSet<>();
        Set<UUID> groupIds = new java.util.LinkedHashSet<>();
        List<GroupInfo> activeGroups = null;
        for (PeriodSnapshot period : periods) {
            dates.addAll(studyWeekdays(period.startsOn(), period.endsOn(), semesterStart, semesterEnd));
            switch (period.scope()) {
                case GROUPS -> groupIds.addAll(period.groupIds());
                case COURSES -> {
                    activeGroups = activeGroups != null ? activeGroups : catalog.activeGroups();
                    activeGroups.stream().filter(group -> period.courses().contains(group.course()))
                            .forEach(group -> groupIds.add(group.id()));
                }
                case ALL -> {
                    activeGroups = activeGroups != null ? activeGroups : catalog.activeGroups();
                    activeGroups.forEach(group -> groupIds.add(group.id()));
                }
            }
        }
        publish(dates, groupIds);
    }

    /** A parity override changes the lessons of every group in that week. */
    void weekChanged(LocalDate weekStart, LocalDate semesterStart, LocalDate semesterEnd) {
        publish(new TreeSet<>(studyWeekdays(weekStart, weekStart.plusDays(4), semesterStart, semesterEnd)),
                allActiveGroups());
    }

    /**
     * New dates or first-week parity of a semester change the parity of its weeks and which days
     * have lessons: every weekday of the old and the new range, every group.
     */
    void semesterChanged(LocalDate oldStart, LocalDate oldEnd, LocalDate newStart, LocalDate newEnd) {
        LocalDate from = oldStart.isBefore(newStart) ? oldStart : newStart;
        LocalDate to = oldEnd.isAfter(newEnd) ? oldEnd : newEnd;
        publish(new TreeSet<>(studyWeekdays(from, to, from, to)), allActiveGroups());
    }

    private Set<UUID> allActiveGroups() {
        return catalog.activeGroups().stream().map(GroupInfo::id)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private void publish(SortedSet<LocalDate> dates, Set<UUID> groupIds) {
        if (dates.isEmpty()) {
            return;
        }
        events.publishEvent(new ScheduleChanged(EventEnvelope.create(clock), List.copyOf(dates),
                List.copyOf(groupIds), List.of(), ScheduleChangeReason.CALENDAR_CHANGED));
    }

    private static List<LocalDate> studyWeekdays(LocalDate from, LocalDate to, LocalDate semesterStart,
                                                 LocalDate semesterEnd) {
        LocalDate start = from.isBefore(semesterStart) ? semesterStart : from;
        LocalDate end = to.isAfter(semesterEnd) ? semesterEnd : to;
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (PeriodRules.isWeekday(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** Values of a period at one moment (an edit publishes both the old and the new state). */
    record PeriodSnapshot(LocalDate startsOn, LocalDate endsOn, ua.vtc.rozklad.core.calendar.PeriodScope scope,
                          List<Integer> courses, Set<UUID> groupIds) {

        static PeriodSnapshot of(CalendarPeriod period) {
            return new PeriodSnapshot(period.getStartsOn(), period.getEndsOn(), period.getScope(),
                    List.copyOf(period.getCourses()), Set.copyOf(period.getGroupIds()));
        }
    }
}
