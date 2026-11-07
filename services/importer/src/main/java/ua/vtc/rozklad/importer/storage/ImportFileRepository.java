package ua.vtc.rozklad.importer.storage;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportFileRepository extends JpaRepository<ImportFile, UUID> {
}
