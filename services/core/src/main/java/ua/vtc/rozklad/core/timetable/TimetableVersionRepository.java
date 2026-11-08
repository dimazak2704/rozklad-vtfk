package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface TimetableVersionRepository extends JpaRepository<TimetableVersion, UUID> {

    List<TimetableVersion> findAllBySemesterIdOrderByCreatedAtDesc(UUID semesterId);

    /** The published version of a semester (at most one, guarded by a partial unique index). */
    default Optional<TimetableVersion> findPublished(UUID semesterId) {
        return findFirstBySemesterIdAndStatus(semesterId, VersionStatus.PUBLISHED);
    }

    Optional<TimetableVersion> findFirstBySemesterIdAndStatus(UUID semesterId, VersionStatus status);

    /**
     * Locks the version row ({@code SELECT … FOR UPDATE}): edits of a draft and its publication are
     * serialized, so a lesson cannot be added while the draft is being published.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from TimetableVersion v where v.id = :id")
    Optional<TimetableVersion> findByIdForUpdate(UUID id);

    Optional<TimetableVersion> findByImportKey(String importKey);

    /** Serializes import calls carrying the same {@code Idempotency-Key}. */
    default void lockImportKey(String key) {
        acquireAdvisoryLock("timetable-import:" + key);
    }

    /** Serializes publications of one semester with a transaction-scoped PostgreSQL advisory lock. */
    default void lockPublication(UUID semesterId) {
        acquireAdvisoryLock("timetable-publication:" + semesterId);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);
}
