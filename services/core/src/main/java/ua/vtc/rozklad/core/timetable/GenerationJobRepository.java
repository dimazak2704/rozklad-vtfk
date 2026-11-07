package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ua.vtc.rozklad.contracts.GenerationStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    List<GenerationJob> findAllBySemesterIdOrderByCreatedAtDesc(UUID semesterId);

    List<GenerationJob> findTop50ByOrderByCreatedAtDesc();

    boolean existsBySemesterIdAndStatusIn(UUID semesterId, Collection<GenerationStatus> statuses);

    List<GenerationJob> findAllByStatusIn(Collection<GenerationStatus> statuses);

    /** Serializes result handling, cancel and the watchdog for one job ({@code SELECT … FOR UPDATE}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from GenerationJob j where j.id = :id")
    Optional<GenerationJob> findByIdForUpdate(UUID id);
}
