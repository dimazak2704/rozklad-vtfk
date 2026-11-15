package ua.vtc.rozklad.core.calendar;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PeriodKindRepository extends JpaRepository<PeriodKind, UUID> {

    List<PeriodKind> findAllByOrderByPositionAscCodeAsc();

    @Query("select coalesce(max(k.position), 0) from PeriodKind k")
    int maxPosition();
}
