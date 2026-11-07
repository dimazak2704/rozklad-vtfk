package ua.vtc.rozklad.core.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StudentGroupRepository extends JpaRepository<StudentGroup, UUID>,
        JpaSpecificationExecutor<StudentGroup> {

    Optional<StudentGroup> findByCode(String code);

    List<StudentGroup> findAllByActiveTrueOrderByCourseAscCodeAsc();
}
