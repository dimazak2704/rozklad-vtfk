package ua.vtc.rozklad.core.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CycleCommissionRepository extends JpaRepository<CycleCommission, UUID> {

    Optional<CycleCommission> findByName(String name);
}
