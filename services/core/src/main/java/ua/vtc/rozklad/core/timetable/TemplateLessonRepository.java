package ua.vtc.rozklad.core.timetable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TemplateLessonRepository extends JpaRepository<TemplateLesson, UUID> {

    List<TemplateLesson> findAllByVersionId(UUID versionId);

    List<TemplateLesson> findAllByVersionIdIn(Collection<UUID> versionIds);

    List<TemplateLesson> findAllByVersionIdAndGroupId(UUID versionId, UUID groupId);

    List<TemplateLesson> findAllByVersionIdAndTeacherId(UUID versionId, UUID teacherId);

    List<TemplateLesson> findAllByVersionIdAndRoomId(UUID versionId, UUID roomId);

    List<TemplateLesson> findAllByVersionIdAndWeekdayAndPairNumber(UUID versionId, int weekday, int pairNumber);

    long countByVersionId(UUID versionId);

    @Modifying
    @Query("delete from TemplateLesson t where t.versionId = :versionId")
    int deleteAllInVersion(UUID versionId);
}
