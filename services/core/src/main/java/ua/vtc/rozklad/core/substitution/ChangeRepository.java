package ua.vtc.rozklad.core.substitution;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ua.vtc.rozklad.contracts.ChangeStatus;

public interface ChangeRepository extends JpaRepository<Change, UUID> {

    List<Change> findAllByDateOrderByCreatedAt(LocalDate date);

    List<Change> findAllByDateAndStatusIn(LocalDate date, Collection<ChangeStatus> statuses);

    List<Change> findAllByDateBetweenAndStatusIn(LocalDate from, LocalDate to, Collection<ChangeStatus> statuses);

    List<Change> findAllByNewTeacherIdAndStatusIn(UUID newTeacherId, Collection<ChangeStatus> statuses);

    List<Change> findAllByNewTeacherIdOrderByDateDescPairFromAsc(UUID newTeacherId);

    List<Change> findAllByAbsenceId(UUID absenceId);

    List<Change> findAllByOrderId(UUID orderId);

    List<Change> findAllByOriginalLessonIdIn(Collection<UUID> lessonIds);

    List<Change> findAllBySwapLessonIdIn(Collection<UUID> lessonIds);

    /**
     * Serializes decisions about one date (transaction-scoped advisory lock): a teacher or a room is
     * shared by every group of the date, so the group-day lock alone lets two parallel requests
     * promise the same teacher to two groups. Take it before {@code LessonRepository.lockGroupDay}.
     */
    default void lockDecisionsOf(LocalDate date) {
        acquireAdvisoryLock("change-date:" + date);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);

    /** Pending confirmations whose deadline has passed (reminders, DOMAIN §7). */
    @Query("""
            select c from Change c
            where c.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and c.confirmDeadline < :now
            """)
    List<Change> findOverduePending(Instant now);

    /** Overdue pending confirmations of the current round that have not been reminded yet. */
    @Query("""
            select c.id from Change c
            where c.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and c.confirmDeadline < :now
              and c.remindedAt is null
            order by c.confirmDeadline
            """)
    List<UUID> findIdsToRemind(Instant now);
}
