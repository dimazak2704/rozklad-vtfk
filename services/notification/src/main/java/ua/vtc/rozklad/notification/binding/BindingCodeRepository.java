package ua.vtc.rozklad.notification.binding;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface BindingCodeRepository extends JpaRepository<BindingCode, UUID> {

    /** Locked, so two chats redeeming the same code at once cannot both succeed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BindingCode c where c.codeHash = :codeHash")
    Optional<BindingCode> findForRedeem(String codeHash);

    boolean existsByCodeHash(String codeHash);

    @Modifying
    @Query("delete from BindingCode c where c.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
