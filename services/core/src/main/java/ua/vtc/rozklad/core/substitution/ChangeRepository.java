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

    List<Change> findAllByNewTeacherIdAndStatusIn(UUID newTeacherId, Collection<ChangeStatus> statuses);

    List<Change> findAllByAbsenceId(UUID absenceId);

    List<Change> findAllByOrderId(UUID orderId);

    List<Change> findAllByOriginalLessonIdIn(Collection<UUID> lessonIds);

    /** Pending confirmations whose deadline has passed (reminders, DOMAIN §7). */
    @Query("""
            select c from Change c
            where c.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and c.confirmDeadline < :now
            """)
    List<Change> findOverduePending(Instant now);
}
