package ua.vtc.rozklad.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubgroupRepository extends JpaRepository<Subgroup, UUID> {

    List<Subgroup> findAllByGroupIdOrderByNumber(UUID groupId);

    List<Subgroup> findAllByGroupIdIn(Collection<UUID> groupIds);
}
