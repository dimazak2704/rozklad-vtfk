package ua.vtc.rozklad.core.calendar.internal;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudyPeriod;
import ua.vtc.rozklad.core.calendar.StudySemester;
import ua.vtc.rozklad.core.calendar.WeekParityOverride;

/**
 * Builds a {@link StudyCalendar} from the calendar module's data with a constant number of
 * queries (semesters, parity overrides, periods with their groups), whatever the date range.
 * Call inside a transaction.
 */
@Component
class StudyCalendarLoader {

    private final EntityManager entityManager;

    StudyCalendarLoader(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    StudyCalendar forSemester(Semester semester) {
        return build(List.of(semester));
    }

    /** Calendar of every semester that overlaps {@code [from, to]}. */
    StudyCalendar forRange(LocalDate from, LocalDate to) {
        List<Semester> semesters = entityManager.createQuery("""
                        select s from Semester s
                        where s.startsOn <= :to and s.endsOn >= :from
                        order by s.startsOn
                        """, Semester.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return build(semesters);
    }

    private StudyCalendar build(List<Semester> semesters) {
        if (semesters.isEmpty()) {
            return new StudyCalendar(List.of());
        }
        List<UUID> ids = semesters.stream().map(Semester::getId).toList();
        Map<UUID, List<StudyPeriod>> periods = periodsBySemester(ids);
        Map<UUID, Map<LocalDate, Parity>> overrides = overridesBySemester(ids);
        return new StudyCalendar(semesters.stream()
                .map(semester -> new StudySemester(semester.getId(), semester.getStartsOn(), semester.getEndsOn(),
                        new SemesterCalendar(semester.getStartsOn(), semester.getEndsOn(), semester.getFirstWeekParity(),
                                overrides.getOrDefault(semester.getId(), Map.of())),
                        periods.getOrDefault(semester.getId(), List.of()), semester.getBellProfileId()))
                .toList());
    }

    private Map<UUID, List<StudyPeriod>> periodsBySemester(Collection<UUID> semesterIds) {
        // fetch join: групи періодів приходять тим самим запитом, без N+1
        List<CalendarPeriod> periods = entityManager.createQuery("""
                        select distinct p from CalendarPeriod p left join fetch p.groups
                        where p.semesterId in :ids
                        """, CalendarPeriod.class)
                .setParameter("ids", semesterIds)
                .getResultList();
        return periods.stream().collect(Collectors.groupingBy(CalendarPeriod::getSemesterId,
                Collectors.mapping(StudyPeriod::of, Collectors.toList())));
    }

    private Map<UUID, Map<LocalDate, Parity>> overridesBySemester(Collection<UUID> semesterIds) {
        List<WeekParityOverride> overrides = entityManager.createQuery("""
                        select o from WeekParityOverride o where o.semesterId in :ids
                        """, WeekParityOverride.class)
                .setParameter("ids", semesterIds)
                .getResultList();
        return overrides.stream().collect(Collectors.groupingBy(WeekParityOverride::getSemesterId,
                Collectors.toMap(WeekParityOverride::getWeekStart, WeekParityOverride::getParity)));
    }
}
