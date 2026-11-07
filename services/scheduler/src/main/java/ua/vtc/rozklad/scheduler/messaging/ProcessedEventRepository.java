package ua.vtc.rozklad.scheduler.messaging;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** @return 1 when the id was stored now, 0 when it had been processed before */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, processed_at) VALUES (:eventId, :processedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID eventId, Instant processedAt);

    @Modifying
    @Query("delete from ProcessedEvent p where p.processedAt < :before")
    int deleteProcessedBefore(Instant before);
}
