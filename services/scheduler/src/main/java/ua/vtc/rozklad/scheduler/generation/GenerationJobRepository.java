package ua.vtc.rozklad.scheduler.generation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import ua.vtc.rozklad.contracts.GenerationStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    /** Status changes of one job come from several threads; they are serialized by a row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from GenerationJob j where j.id = :id")
    Optional<GenerationJob> findForUpdate(UUID id);

    /** Best score so far of a running job (no version bump: progress never conflicts with a status change). */
    @Modifying
    @Query("""
            update GenerationJob j set j.bestHardScore = :hardScore, j.bestSoftScore = :softScore,
                   j.elapsedMs = :elapsedMs
            where j.id = :id and j.status = ua.vtc.rozklad.contracts.GenerationStatus.RUNNING
            """)
    int updateProgress(UUID id, long hardScore, long softScore, long elapsedMs);

    List<GenerationJob> findByStatusIn(Collection<GenerationStatus> statuses);

    long countByStatus(GenerationStatus status);

    @Query("""
            select j.id from GenerationJob j
            where j.completion is not null and j.completionSentAt is null and j.finishedAt < :finishedBefore
            order by j.finishedAt
            """)
    List<UUID> findUnsentCompletions(Instant finishedBefore);

    @Modifying
    @Query("delete from GenerationJob j where j.finishedAt < :finishedBefore")
    int deleteFinishedBefore(Instant finishedBefore);
}
