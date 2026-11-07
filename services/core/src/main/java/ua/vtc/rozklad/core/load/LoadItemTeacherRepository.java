package ua.vtc.rozklad.core.load;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoadItemTeacherRepository extends JpaRepository<LoadItemTeacher, UUID> {

    List<LoadItemTeacher> findAllByTeacherId(UUID teacherId);
}
