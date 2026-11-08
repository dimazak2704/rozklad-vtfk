package ua.vtc.rozklad.core.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BuildingRepository extends JpaRepository<Building, UUID>, JpaSpecificationExecutor<Building> {

    Optional<Building> findByName(String name);

    Optional<Building> findByNameIgnoreCase(String name);
}
