package ua.vtc.rozklad.notification.email;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    boolean existsByDedupKey(String dedupKey);

    /** Due letters (their settle delay passed), oldest first, locked so that concurrent workers skip them. */
    @Query(value = """
            SELECT m.* FROM email_message m
            WHERE m.status IN ('QUEUED', 'RETRYING')
              AND m.next_attempt_at <= :now
              AND m.not_before <= :now
              AND (m.locked_until IS NULL OR m.locked_until <= :now)
            ORDER BY m.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailMessage> lockDue(Instant now, int limit);

    /** Earlier letters about one change to one person, locked: a newer letter may replace the unsent ones. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m from EmailMessage m
            where m.correlationId = :correlationId and m.accountId = :accountId and m.kind in :kinds
              and m.status in :statuses
            order by m.createdAt
            """)
    List<EmailMessage> lockEarlier(UUID correlationId, UUID accountId, Collection<EmailKind> kinds,
                                   Collection<EmailMessageStatus> statuses);

    @Query("""
            select m from EmailMessage m
            where (:status is null or m.status = :status)
              and (:kind is null or m.kind = :kind)
            order by m.createdAt desc
            """)
    Page<EmailMessage> search(EmailMessageStatus status, EmailKind kind, Pageable pageable);

    List<EmailMessage> findByNoticeId(UUID noticeId);

    long countByStatusIn(List<EmailMessageStatus> statuses);

    @Modifying
    @Query("delete from EmailMessage m where m.status in :statuses and m.createdAt < :before")
    int deleteFinishedBefore(List<EmailMessageStatus> statuses, Instant before);
}
