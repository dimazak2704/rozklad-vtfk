package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherDisciplineRepository extends JpaRepository<TeacherDiscipline, UUID> {

    List<TeacherDiscipline> findAllByTeacherId(UUID teacherId);

    List<TeacherDiscipline> findAllByDisciplineId(UUID disciplineId);

    boolean existsByTeacherIdAndDisciplineId(UUID teacherId, UUID disciplineId);

    Optional<TeacherDiscipline> findByTeacherIdAndDisciplineId(UUID teacherId, UUID disciplineId);
}
