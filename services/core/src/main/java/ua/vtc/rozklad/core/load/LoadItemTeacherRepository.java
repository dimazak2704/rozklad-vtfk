package ua.vtc.rozklad.core.load;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoadItemTeacherRepository extends JpaRepository<LoadItemTeacher, UUID> {

    List<LoadItemTeacher> findAllByTeacherId(UUID teacherId);

    /** Teacher assignments of a subgroup in any semester (a used subgroup cannot be deleted). */
    @Query("""
            select count(t) from LoadItemTeacher t
            where t.loadItem.groupId = :groupId and t.subgroupNumber = :subgroupNumber
            """)
    long countForSubgroup(UUID groupId, int subgroupNumber);
}
