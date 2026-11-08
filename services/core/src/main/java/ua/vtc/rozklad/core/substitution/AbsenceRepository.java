package ua.vtc.rozklad.core.substitution;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AbsenceRepository extends JpaRepository<Absence, UUID> {

    /** Active absences that overlap {@code [from, to]} (optionally of one teacher). */
    @Query("""
            select a from Absence a
            where a.status = ua.vtc.rozklad.core.substitution.AbsenceStatus.ACTIVE
              and a.dateFrom <= :to and a.dateTo >= :from
              and (:teacherId is null or a.teacherId = :teacherId)
            order by a.dateFrom, a.createdAt
            """)
    List<Absence> findActiveOverlapping(LocalDate from, LocalDate to, UUID teacherId);

    List<Absence> findAllByTeacherIdOrderByDateFromDesc(UUID teacherId);

    /**
     * Serializes absence writes of one teacher (transaction-scoped advisory lock), so the overlap
     * check and the insert cannot interleave with a parallel request for the same teacher.
     */
    default void lockTeacher(UUID teacherId) {
        acquireAdvisoryLock("absence-teacher:" + teacherId);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);
}
