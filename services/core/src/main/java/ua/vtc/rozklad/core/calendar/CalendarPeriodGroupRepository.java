package ua.vtc.rozklad.core.calendar;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarPeriodGroupRepository extends JpaRepository<CalendarPeriodGroup, UUID> {

    List<CalendarPeriodGroup> findAllByGroupId(UUID groupId);
}
