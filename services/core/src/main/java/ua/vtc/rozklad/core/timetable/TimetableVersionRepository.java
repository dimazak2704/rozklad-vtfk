package ua.vtc.rozklad.core.timetable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimetableVersionRepository extends JpaRepository<TimetableVersion, UUID> {

    List<TimetableVersion> findAllBySemesterIdOrderByCreatedAtDesc(UUID semesterId);

    /** The published version of a semester (at most one, guarded by a partial unique index). */
    default Optional<TimetableVersion> findPublished(UUID semesterId) {
        return findFirstBySemesterIdAndStatus(semesterId, VersionStatus.PUBLISHED);
    }

    Optional<TimetableVersion> findFirstBySemesterIdAndStatus(UUID semesterId, VersionStatus status);
}
