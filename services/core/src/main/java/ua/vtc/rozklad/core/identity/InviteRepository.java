package ua.vtc.rozklad.core.identity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByTokenHash(String tokenHash);

    /** The invite locked for the rest of the transaction ({@code SELECT … FOR UPDATE}): one accept at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invite i where i.tokenHash = :tokenHash")
    Optional<Invite> lockByTokenHash(String tokenHash);

    List<Invite> findAllByTeacherIdOrderByCreatedAtDesc(UUID teacherId);

    List<Invite> findAllByOrderByCreatedAtDesc();

    List<Invite> findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(UUID teacherId);
}
