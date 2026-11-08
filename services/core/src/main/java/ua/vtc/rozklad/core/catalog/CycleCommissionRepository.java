package ua.vtc.rozklad.core.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CycleCommissionRepository extends JpaRepository<CycleCommission, UUID>,
        JpaSpecificationExecutor<CycleCommission> {

    Optional<CycleCommission> findByName(String name);

    Optional<CycleCommission> findByNameIgnoreCase(String name);
}
