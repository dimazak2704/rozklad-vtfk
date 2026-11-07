package ua.vtc.rozklad.core.calendar;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BellSlotRepository extends JpaRepository<BellSlot, UUID> {

    @Query("select s from BellSlot s where s.profile.id = :profileId and s.weekday = :weekday order by s.pairNumber")
    List<BellSlot> findDaySlots(UUID profileId, int weekday);
}
