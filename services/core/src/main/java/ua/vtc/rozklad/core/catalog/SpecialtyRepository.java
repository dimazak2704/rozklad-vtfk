package ua.vtc.rozklad.core.catalog;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SpecialtyRepository extends JpaRepository<Specialty, UUID>, JpaSpecificationExecutor<Specialty> {

    Optional<Specialty> findByLetters(String letters);
}
