package ua.vtc.rozklad.core.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BellProfileRepository extends JpaRepository<BellProfile, UUID> {

    Optional<BellProfile> findByName(String name);

    /** Profile with slots in one query. */
    @EntityGraph(attributePaths = "slots")
    Optional<BellProfile> findWithSlotsById(UUID id);

    List<BellProfile> findAllByOrderByName();
}
