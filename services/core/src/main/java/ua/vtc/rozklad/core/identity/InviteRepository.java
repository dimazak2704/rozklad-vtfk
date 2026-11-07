package ua.vtc.rozklad.core.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByTokenHash(String tokenHash);

    List<Invite> findAllByTeacherIdOrderByCreatedAtDesc(UUID teacherId);

    List<Invite> findAllByOrderByCreatedAtDesc();
}
