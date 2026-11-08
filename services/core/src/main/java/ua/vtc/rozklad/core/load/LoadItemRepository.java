package ua.vtc.rozklad.core.load;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoadItemRepository extends JpaRepository<LoadItem, UUID> {

    @EntityGraph(attributePaths = "teachers")
    List<LoadItem> findAllBySemesterId(UUID semesterId);

    @EntityGraph(attributePaths = "teachers")
    List<LoadItem> findAllBySemesterIdAndGroupId(UUID semesterId, UUID groupId);

    List<LoadItem> findAllBySemesterIdAndGroupIdAndDisciplineId(UUID semesterId, UUID groupId, UUID disciplineId);

    @EntityGraph(attributePaths = "teachers")
    Optional<LoadItem> findWithTeachersById(UUID id);

    @Query("""
            select count(i) > 0 from LoadItem i join i.teachers t
            where i.semesterId = :semesterId and i.disciplineId = :disciplineId and t.teacherId = :teacherId
            """)
    boolean teacherHasDiscipline(UUID teacherId, UUID disciplineId, UUID semesterId);
}
