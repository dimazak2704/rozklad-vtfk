package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SemesterRepository extends JpaRepository<Semester, UUID> {

    Optional<Semester> findByActiveTrue();

    List<Semester> findAllByOrderByStartsOnDesc();

    @Query("select s from Semester s where s.startsOn <= :date and s.endsOn >= :date")
    Optional<Semester> findContaining(LocalDate date);
}
