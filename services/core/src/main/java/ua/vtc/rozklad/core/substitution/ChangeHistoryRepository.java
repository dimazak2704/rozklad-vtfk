package ua.vtc.rozklad.core.substitution;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangeHistoryRepository extends JpaRepository<ChangeHistory, UUID> {

    List<ChangeHistory> findAllByChangeIdOrderByAt(UUID changeId);

    List<ChangeHistory> findAllByChangeIdInOrderByAt(Collection<UUID> changeIds);
}
