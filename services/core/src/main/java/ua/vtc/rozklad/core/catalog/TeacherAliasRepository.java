package ua.vtc.rozklad.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherAliasRepository extends JpaRepository<TeacherAlias, UUID> {

    Optional<TeacherAlias> findByNormalized(String normalized);

    List<TeacherAlias> findAllByTeacherId(UUID teacherId);

    List<TeacherAlias> findAllByTeacherIdOrderByAlias(UUID teacherId);

    List<TeacherAlias> findAllByTeacherIdIn(Collection<UUID> teacherIds);
}
