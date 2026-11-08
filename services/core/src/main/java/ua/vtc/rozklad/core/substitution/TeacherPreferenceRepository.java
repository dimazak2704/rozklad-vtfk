package ua.vtc.rozklad.core.substitution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TeacherPreferenceRepository extends JpaRepository<TeacherPreference, UUID> {

    List<TeacherPreference> findAllByTeacherIdAndSemesterId(UUID teacherId, UUID semesterId);

    List<TeacherPreference> findAllBySemesterId(UUID semesterId);

    /** Preferences of all teachers for one weekday × pair (candidate ranking). */
    List<TeacherPreference> findAllBySemesterIdAndWeekdayAndPairNumber(UUID semesterId, int weekday, int pairNumber);

    /**
     * Serializes replacements of one teacher's grid (transaction-scoped advisory lock): a double
     * click sends two PUTs whose delete-then-insert would otherwise deadlock on the unique slots.
     */
    default void lockGridOf(UUID teacherId, UUID semesterId) {
        acquireAdvisoryLock("preference-grid:" + teacherId + ":" + semesterId);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);

    /** Bulk delete before saving a replacement grid of a teacher. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TeacherPreference p where p.teacherId = :teacherId and p.semesterId = :semesterId")
    int deleteAllOf(UUID teacherId, UUID semesterId);
}
