package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Every read loads the period's kind in the same query: rules need its effect (no extra statements). */
public interface CalendarPeriodRepository extends JpaRepository<CalendarPeriod, UUID> {

    @EntityGraph(attributePaths = "kind")
    List<CalendarPeriod> findAllBySemesterIdOrderByStartsOn(UUID semesterId);

    @EntityGraph(attributePaths = "kind")
    List<CalendarPeriod> findAllByOrderByStartsOn();

    @EntityGraph(attributePaths = "kind")
    Optional<CalendarPeriod> findWithKindById(UUID id);

    /** Periods of the semester that overlap {@code [from, to]}. */
    @Query("""
            select p from CalendarPeriod p join fetch p.kind
            where p.semesterId = :semesterId and p.startsOn <= :to and p.endsOn >= :from
            order by p.startsOn
            """)
    List<CalendarPeriod> findOverlapping(UUID semesterId, LocalDate from, LocalDate to);

    /** Periods of a kind with their groups and supervisors (an effect change re-checks all of them). */
    @Query("""
            select distinct p from CalendarPeriod p join fetch p.kind left join fetch p.groups
            left join fetch p.supervisorIds
            where p.kind.id = :kindId
            """)
    List<CalendarPeriod> findAllByKind(UUID kindId);

    /** Periods of the semesters with their kinds, groups and supervisors (the academic calendar import). */
    @Query("""
            select distinct p from CalendarPeriod p join fetch p.kind left join fetch p.groups
            left join fetch p.supervisorIds
            where p.semesterId in :semesterIds
            """)
    List<CalendarPeriod> findAllInSemesters(Collection<UUID> semesterIds);

    long countByKindId(UUID kindId);

    /** Kind id → number of periods of that kind. */
    @Query("select p.kind.id, count(p) from CalendarPeriod p group by p.kind.id")
    List<Object[]> countByKind();
}
