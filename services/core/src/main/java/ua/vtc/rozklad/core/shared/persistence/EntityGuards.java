package ua.vtc.rozklad.core.shared.persistence;

import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ua.vtc.rozklad.core.shared.domain.BaseEntity;
import ua.vtc.rozklad.core.shared.error.ConflictException;

/** Small guards every CRUD service needs: optimistic version check and "delete or 409". */
public final class EntityGuards {

    private EntityGuards() {
    }

    /**
     * Compares the version the client edited with the stored one ({@code PUT} bodies carry the
     * optional {@code version} they read). A mismatch is the same 409 «Дані змінилися» as a lost
     * optimistic lock; {@code null} skips the check.
     */
    public static void checkVersion(BaseEntity entity, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != entity.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(entity.getClass(), entity.getId());
        }
    }

    /**
     * Deletes and flushes at once, so a foreign key violation (the record is referenced by
     * lessons, load, orders, …) becomes a 409 with a message about this record instead of a
     * generic data integrity error at commit. The database knows every reference, including
     * those of other modules, so no module has to list them.
     */
    public static <T> void deleteOrConflict(JpaRepository<T, ?> repository, T entity, Supplier<String> message) {
        repository.delete(entity);
        try {
            repository.flush();
        } catch (DataIntegrityViolationException referenced) {
            throw new ConflictException(message.get());
        }
    }
}
