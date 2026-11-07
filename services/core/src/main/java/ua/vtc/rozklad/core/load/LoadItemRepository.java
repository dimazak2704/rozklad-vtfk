package ua.vtc.rozklad.core.load;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoadItemRepository extends JpaRepository<LoadItem, UUID> {

    @EntityGraph(attributePaths = "teachers")
    List<LoadItem> findAllBySemesterId(UUID semesterId);

    @EntityGraph(attributePaths = "teachers")
    List<LoadItem> findAllBySemesterIdAndGroupId(UUID semesterId, UUID groupId);

    List<LoadItem> findAllBySemesterIdAndGroupIdAndDisciplineId(UUID semesterId, UUID groupId, UUID disciplineId);
}
