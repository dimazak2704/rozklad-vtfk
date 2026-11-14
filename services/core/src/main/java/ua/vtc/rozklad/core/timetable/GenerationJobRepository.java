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

    /**
     * Status of the semester's newest job, without loading jobs: their plan and analysis JSON is
     * hundreds of kilobytes each, and the dashboard needs only this one value.
     */
    @Query("select j.status from GenerationJob j where j.semesterId = :semesterId order by j.createdAt desc limit 1")
    Optional<GenerationStatus> findLatestStatus(UUID semesterId);

    boolean existsBySemesterIdAndStatusIn(UUID semesterId, Collection<GenerationStatus> statuses);

    List<GenerationJob> findAllByStatusIn(Collection<GenerationStatus> statuses);

    /** Serializes result handling, cancel and the watchdog for one job ({@code SELECT … FOR UPDATE}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from GenerationJob j where j.id = :id")
    Optional<GenerationJob> findByIdForUpdate(UUID id);
}
