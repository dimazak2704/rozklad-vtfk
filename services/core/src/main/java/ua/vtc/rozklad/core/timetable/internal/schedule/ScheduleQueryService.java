package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.DayInfo;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudyPeriod;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;

/**
 * Dated schedules for the public site, teachers and other modules. A constant number of queries
 * per request: the owner, the calendar (semesters, overrides, periods), bell profiles and one query
 * for all lessons of the range; a range with practice adds one lookup for its supervisors and one for its groups.
 */
@Service
@Transactional(readOnly = true)
public class ScheduleQueryService implements ScheduleQueries {

    private final ScheduleReadRepository reads;
    private final ScheduleOwners owners;
    private final CalendarQueries calendarQueries;
    private final CatalogQueries catalog;

    ScheduleQueryService(ScheduleReadRepository reads, ScheduleOwners owners, CalendarQueries calendarQueries,
                         CatalogQueries catalog) {
        this.reads = reads;
        this.owners = owners;
        this.calendarQueries = calendarQueries;
        this.catalog = catalog;
    }

    @Override
    public ScheduleView schedule(ScheduleOwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        requireRange(from, to);
        ScheduleOwners.Resolved owner = owners.resolve(type, ownerId);
        StudyCalendar calendar = calendarQueries.studyCalendar(from, to);
        Map<UUID, BellTimes> bells = calendarQueries.bellTimes(calendar);
        Map<LocalDate, List<LessonRow>> rowsByDate = reads.forOwner(type, ownerId, from, to).stream()
                .collect(Collectors.groupingBy(LessonRow::date));
        Map<LocalDate, List<StudyPeriod>> practices = practicesByDate(type, ownerId, owner, calendar, from, to);
        PracticeRefs refs = practiceRefs(practices.values());
        List<ScheduleView.Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            int weekday = date.getDayOfWeek().getValue();
            if (weekday > 5) {
                continue;
            }
            DayInfo info = calendar.day(date, owner.scope());
            List<LessonView> lessons = LessonViewAssembler.assemble(rowsByDate.getOrDefault(date, List.of()),
                    bellsOf(info, bells), true);
            List<ScheduleView.Practice> dayPractices = practices.getOrDefault(date, List.of()).stream()
                    .map(refs::practice)
                    .toList();
            days.add(new ScheduleView.Day(date, weekday, UkrainianText.weekdayLabel(weekday), info.parity(),
                    info.studyDay(), info.note(), lessons, dayPractices));
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
        StudyCalendar calendar = calendarQueries.studyCalendar(first, last);
        Map<UUID, BellTimes> bells = calendarQueries.bellTimes(calendar);
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

    /** A group sees the practice it is at; a teacher sees the practices they supervise; a room sees none. */
    private static Map<LocalDate, List<StudyPeriod>> practicesByDate(ScheduleOwnerType type, UUID ownerId,
                                                                     ScheduleOwners.Resolved owner,
                                                                     StudyCalendar calendar, LocalDate from,
                                                                     LocalDate to) {
        Map<LocalDate, List<StudyPeriod>> practices = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<StudyPeriod> found = switch (type) {
                case GROUP -> calendar.practices(date, owner.scope());
                case TEACHER -> calendar.supervisedPractices(date, ownerId);
                case ROOM -> List.of();
            };
            if (!found.isEmpty()) {
                practices.put(date, found);
            }
        }
        return practices;
    }

    /** Supervisors and groups of every practice in the range: one batch lookup each, none without practice. */
    private PracticeRefs practiceRefs(Collection<List<StudyPeriod>> practices) {
        Set<UUID> teacherIds = practices.stream().flatMap(List::stream)
                .flatMap(period -> period.supervisorIds().stream())
                .collect(Collectors.toSet());
        Set<UUID> groupIds = practices.stream().flatMap(List::stream)
                .filter(period -> period.scope() == PeriodScope.GROUPS)
                .flatMap(period -> period.groupIds().stream())
                .collect(Collectors.toSet());
        return new PracticeRefs(teacherIds.isEmpty() ? Map.of() : catalog.teacherRefs(teacherIds),
                groupIds.isEmpty() ? Map.of() : catalog.groupRefs(groupIds));
    }

    private static BellTimes bellsOf(DayInfo info, Map<UUID, BellTimes> bells) {
        return info.semesterId() == null ? BellTimes.none() : bells.getOrDefault(info.semesterId(), BellTimes.none());
    }

    private record PracticeRefs(Map<UUID, TeacherRef> teachers, Map<UUID, GroupRef> groups) {

        ScheduleView.Practice practice(StudyPeriod period) {
            List<TeacherRef> supervisors = period.supervisorIds().stream()
                    .map(teachers::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(TeacherRef::shortName, UkrainianOrder.NATURAL))
                    .toList();
            List<GroupRef> periodGroups = period.scope() != PeriodScope.GROUPS ? List.of() : period.groupIds().stream()
                    .map(groups::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(GroupRef::code, UkrainianOrder.NATURAL))
                    .toList();
            List<Integer> courses = period.scope() != PeriodScope.COURSES ? List.of()
                    : period.courses().stream().sorted().toList();
            return new ScheduleView.Practice(period.id(), period.title(), period.startsOn(), period.endsOn(),
                    supervisors, periodGroups, courses);
        }
    }
}
