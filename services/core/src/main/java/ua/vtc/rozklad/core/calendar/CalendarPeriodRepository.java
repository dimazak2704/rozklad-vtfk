package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CalendarPeriodRepository extends JpaRepository<CalendarPeriod, UUID> {

    List<CalendarPeriod> findAllBySemesterIdOrderByStartsOn(UUID semesterId);

    /** Periods of the semester that overlap {@code [from, to]}. */
    @Query("""
            select p from CalendarPeriod p
            where p.semesterId = :semesterId and p.startsOn <= :to and p.endsOn >= :from
            order by p.startsOn
            """)
    List<CalendarPeriod> findOverlapping(UUID semesterId, LocalDate from, LocalDate to);
}
