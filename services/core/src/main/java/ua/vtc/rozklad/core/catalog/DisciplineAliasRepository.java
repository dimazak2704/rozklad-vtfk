package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisciplineAliasRepository extends JpaRepository<DisciplineAlias, UUID> {

    Optional<DisciplineAlias> findByNormalized(String normalized);

    List<DisciplineAlias> findAllByDisciplineId(UUID disciplineId);
}
