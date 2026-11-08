package ua.vtc.rozklad.notification.substitution;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SubstitutionRequestRepository extends JpaRepository<SubstitutionRequest, UUID> {

    /**
     * The request with its row locked until the transaction ends. Every writer (the teacher's button,
     * core's events, the scheduled jobs) reads through a locking query, so two of them never update
     * the same version: the second one waits for the first one's commit and then sees its result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SubstitutionRequest r where r.changeId = :changeId")
    Optional<SubstitutionRequest> findForUpdate(UUID changeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from SubstitutionRequest r
            where r.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and r.remindedAt is null and r.deadline is not null and r.deadline < :now
            order by r.deadline
            """)
    List<SubstitutionRequest> findOverdue(Instant now);

    /** Pending requests of teachers without Telegram, older than {@code noticedBefore}, not reported to admins yet. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from SubstitutionRequest r
            where r.status = ua.vtc.rozklad.contracts.ChangeStatus.PENDING_CONFIRMATION
              and r.noTelegramAt is not null and r.noTelegramAt <= :noticedBefore and r.adminWarnedAt is null
            order by r.teacherId, r.lessonDate, r.pair
            """)
    List<SubstitutionRequest> findUnreportedWithoutTelegram(Instant noticedBefore);

    @Modifying
    @Query("delete from SubstitutionRequest r where r.lessonDate < :before")
    int deleteLessonsBefore(LocalDate before);
}
