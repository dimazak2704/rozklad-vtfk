package ua.vtc.rozklad.core.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DisciplineRepository extends JpaRepository<Discipline, UUID>, JpaSpecificationExecutor<Discipline> {

    Optional<Discipline> findByShortName(String shortName);
}
