package ua.vtc.rozklad.notification.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmailNoticeRepository extends JpaRepository<EmailNotice, UUID> {

    boolean existsByDedupKey(String dedupKey);

    /** Due intents, oldest first, locked so that concurrent workers skip them. */
    @Query(value = """
            SELECT n.* FROM email_notice n
            WHERE n.status IN ('PENDING', 'RETRYING')
              AND n.next_attempt_at <= :now
              AND (n.locked_until IS NULL OR n.locked_until <= :now)
            ORDER BY n.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<EmailNotice> lockDue(Instant now, int limit);

    @Modifying
    @Query("delete from EmailNotice n where n.status in :statuses and n.createdAt < :before")
    int deleteFinishedBefore(List<EmailNoticeStatus> statuses, Instant before);
}
