package ua.vtc.rozklad.core.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftWindowRepository extends JpaRepository<ShiftWindow, UUID> {

    Optional<ShiftWindow> findByShiftAndWeekday(int shift, int weekday);

    List<ShiftWindow> findAllByOrderByShiftAscWeekdayAsc();
}
