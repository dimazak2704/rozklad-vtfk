package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;
import ua.vtc.rozklad.core.timetable.internal.calendar.BellTimes;
import ua.vtc.rozklad.core.timetable.internal.calendar.BellTimesLoader;
import ua.vtc.rozklad.core.timetable.internal.calendar.DayInfo;
import ua.vtc.rozklad.core.timetable.internal.calendar.StudyCalendar;
import ua.vtc.rozklad.core.timetable.internal.calendar.StudyCalendarLoader;
import ua.vtc.rozklad.core.timetable.internal.support.UkrainianText;

/**
 * Dated schedules for the public site, teachers and other modules. A constant number of queries
 * per request: the owner, the calendar (semesters, overrides, periods), bell profiles and one query
 * for all lessons of the range.
 */
@Service
@Transactional(readOnly = true)
public class ScheduleQueryService implements ScheduleQueries {

    private final ScheduleReadRepository reads;
    private final ScheduleOwners owners;
    private final StudyCalendarLoader calendarLoader;
    private final BellTimesLoader bellTimesLoader;

    ScheduleQueryService(ScheduleReadRepository reads, ScheduleOwners owners, StudyCalendarLoader calendarLoader,
                         BellTimesLoader bellTimesLoader) {
        this.reads = reads;
        this.owners = owners;
        this.calendarLoader = calendarLoader;
        this.bellTimesLoader = bellTimesLoader;
    }

    @Override
    public ScheduleView schedule(ScheduleOwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        ScheduleOwners.Resolved owner = owners.resolve(type, ownerId);
        StudyCalendar calendar = calendarLoader.forRange(from, to);
        Map<UUID, BellTimes> bells = bellTimesLoader.forCalendar(calendar);
        Map<LocalDate, List<LessonRow>> rowsByDate = reads.forOwner(type, ownerId, from, to).stream()
                .collect(Collectors.groupingBy(LessonRow::date));
        List<ScheduleView.Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int weekday = date.getDayOfWeek().getValue();
            if (weekday > 5) {
                continue;
            }
            DayInfo info = calendar.day(date, owner.scope());
            List<LessonView> lessons = LessonViewAssembler.assemble(rowsByDate.getOrDefault(date, List.of()),
                    bellsOf(info, bells), true);
            days.add(new ScheduleView.Day(date, weekday, UkrainianText.weekdayLabel(weekday), info.parity(),
                    info.studyDay(), info.note(), lessons));
        }
        return new ScheduleView(owner.owner(), from, to, days);
    }

    @Override
    public Map<UUID, LessonView> lessonViews(Collection<UUID> lessonIds) {
        List<LessonRow> rows = reads.byIds(lessonIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        LocalDate first = rows.stream().map(LessonRow::date).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate last = rows.stream().map(LessonRow::date).max(Comparator.naturalOrder()).orElseThrow();
        StudyCalendar calendar = calendarLoader.forRange(first, last);
        Map<UUID, BellTimes> bells = bellTimesLoader.forCalendar(calendar);
        Map<UUID, LessonView> views = new LinkedHashMap<>();
        for (LessonRow row : rows) {
            views.put(row.id(), LessonViewAssembler.view(row, bellsOf(calendar.day(row.date(), null), bells)));
        }
        return views;
    }

    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("the range must have from <= to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("the range must not be longer than " + MAX_DAYS + " days");
        }
    }

    private static BellTimes bellsOf(DayInfo info, Map<UUID, BellTimes> bells) {
        return info.semesterId() == null ? BellTimes.none() : bells.getOrDefault(info.semesterId(), BellTimes.none());
    }
}
