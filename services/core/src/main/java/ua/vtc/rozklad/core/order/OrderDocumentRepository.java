package ua.vtc.rozklad.core.order;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import ua.vtc.rozklad.contracts.OrderKind;

public interface OrderDocumentRepository extends JpaRepository<OrderDocument, UUID>,
        JpaSpecificationExecutor<OrderDocument> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderDocument> findWithItemsById(UUID id);

    Optional<OrderDocument> findBySemesterIdAndDateAndKind(UUID semesterId, LocalDate date, OrderKind kind);

    List<OrderDocument> findAllByDateOrderByCreatedAt(LocalDate date);

    /**
     * Highest number used in the semester. Call after {@link #lockNumbering} in the same transaction
     * so that two publications cannot take the same number (the unique index is the last guard).
     */
    @Query("select coalesce(max(o.number), 0) from OrderDocument o where o.semesterId = :semesterId")
    int findMaxNumber(UUID semesterId);

    /** Serializes numbering of one semester with a transaction-scoped PostgreSQL advisory lock. */
    default void lockNumbering(UUID semesterId) {
        acquireAdvisoryLock("order-number:" + semesterId);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);
}
