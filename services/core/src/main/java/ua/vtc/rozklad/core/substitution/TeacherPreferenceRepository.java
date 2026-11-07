package ua.vtc.rozklad.core.substitution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TeacherPreferenceRepository extends JpaRepository<TeacherPreference, UUID> {

    List<TeacherPreference> findAllByTeacherIdAndSemesterId(UUID teacherId, UUID semesterId);

    List<TeacherPreference> findAllBySemesterId(UUID semesterId);

    /** Bulk delete before saving a replacement grid of a teacher. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TeacherPreference p where p.teacherId = :teacherId and p.semesterId = :semesterId")
    int deleteAllOf(UUID teacherId, UUID semesterId);
}
