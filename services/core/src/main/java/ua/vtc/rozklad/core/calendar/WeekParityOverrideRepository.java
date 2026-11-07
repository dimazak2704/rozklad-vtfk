package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeekParityOverrideRepository extends JpaRepository<WeekParityOverride, UUID> {

    List<WeekParityOverride> findAllBySemesterIdOrderByWeekStart(UUID semesterId);

    Optional<WeekParityOverride> findBySemesterIdAndWeekStart(UUID semesterId, LocalDate weekStart);
}
