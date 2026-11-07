package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TeacherRepository extends JpaRepository<Teacher, UUID>, JpaSpecificationExecutor<Teacher> {

    Optional<Teacher> findByShortName(String shortName);

    Optional<Teacher> findByKeycloakUserId(UUID keycloakUserId);

    List<Teacher> findAllByActiveTrueOrderByShortName();
}
