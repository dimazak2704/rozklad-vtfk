package ua.vtc.rozklad.core.timetable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ua.vtc.rozklad.contracts.GenerationStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    List<GenerationJob> findAllBySemesterIdOrderByCreatedAtDesc(UUID semesterId);

    boolean existsBySemesterIdAndStatusIn(UUID semesterId, Collection<GenerationStatus> statuses);
}
