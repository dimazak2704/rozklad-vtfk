package ua.vtc.rozklad.core.order;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;

public interface OrderDocumentRepository extends JpaRepository<OrderDocument, UUID>,
        JpaSpecificationExecutor<OrderDocument> {

    @EntityGraph(attributePaths = "items")
    Optional<OrderDocument> findWithItemsById(UUID id);

    Optional<OrderDocument> findBySemesterIdAndDateAndKind(UUID semesterId, LocalDate date, OrderKind kind);

    Optional<OrderDocument> findFirstBySemesterIdAndDateAndKindAndStatus(UUID semesterId, LocalDate date,
                                                                          OrderKind kind, OrderStatus status);

    List<OrderDocument> findAllByDateOrderByCreatedAt(LocalDate date);

    /** Orders of a date in a status with their rows (public changes, dashboard). */
    @EntityGraph(attributePaths = "items")
    List<OrderDocument> findAllByDateAndStatusOrderByCreatedAt(LocalDate date, OrderStatus status);

    /** Semester of an order without loading the entity (taken before the numbering lock). */
    @Query("select o.semesterId from OrderDocument o where o.id = :id")
    Optional<UUID> findSemesterIdById(UUID id);

    /** Number of rows (both sections) per order. */
    @Query("""
            select new ua.vtc.rozklad.core.order.OrderItemCount(i.order.id, count(i))
            from OrderItem i where i.order.id in :orderIds group by i.order.id
            """)
    List<OrderItemCount> countItems(Collection<UUID> orderIds);

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

    /** Serializes draft building and publication of one date (transaction-scoped advisory lock). */
    default void lockDate(UUID semesterId, LocalDate date) {
        acquireAdvisoryLock("order-date:" + semesterId + ":" + date);
    }

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    Integer acquireAdvisoryLock(String key);
}
