package ua.vtc.rozklad.notification.delivery;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, UUID> {

    /**
     * Due messages that are the oldest pending one of their chat (strict per-chat order), locked
     * so that concurrent workers skip them.
     */
    @Query(value = """
            SELECT o.* FROM outbound_message o
            WHERE o.status IN ('QUEUED', 'RETRYING')
              AND o.next_attempt_at <= :now
              AND (o.locked_until IS NULL OR o.locked_until <= :now)
              AND NOT EXISTS (SELECT 1 FROM outbound_message p
                              WHERE p.chat_id = o.chat_id
                                AND p.status IN ('QUEUED', 'RETRYING')
                                AND p.queue_seq < o.queue_seq)
            ORDER BY o.queue_seq
            LIMIT :limit
            FOR UPDATE OF o SKIP LOCKED
            """, nativeQuery = true)
    List<OutboundMessage> lockDue(Instant now, int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboundMessage o where o.id = :id")
    Optional<OutboundMessage> lockById(UUID id);

    boolean existsByDedupKey(String dedupKey);

    @Query("""
            select o from OutboundMessage o
            where (:orderId is null or o.orderId = :orderId)
              and (:status is null or o.status = :status)
            order by o.createdAt desc
            """)
    Page<OutboundMessage> search(UUID orderId, OutboundStatus status, Pageable pageable);

    long countByStatusIn(List<OutboundStatus> statuses);

    @Modifying
    @Query("delete from OutboundMessage o where o.status in :statuses and o.createdAt < :before")
    int deleteFinishedBefore(List<OutboundStatus> statuses, Instant before);
}
