package ua.vtc.rozklad.core.load.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.load.internal.LoadDerivation.TemplateLessonRow;

/**
 * Reads the lessons of a timetable version straight from {@code timetable_version} and
 * {@code template_lesson}. Deliberately SQL instead of the timetable module's repositories: the
 * timetable module depends on load (generation units), so a Java dependency back would be a module
 * cycle. Both tables belong to the same core database and their columns are part of DOMAIN §3.
 */
@Component
class TemplateLessonReader {

    private final JdbcClient jdbc;

    TemplateLessonReader(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
    }

    Optional<VersionLessons> read(UUID versionId) {
        Optional<UUID> semesterId = jdbc.sql("select semester_id from timetable_version where id = ?")
                .param(versionId)
                .query(UUID.class)
                .optional();
        if (semesterId.isEmpty()) {
            return Optional.empty();
        }
        List<TemplateLessonRow> lessons = jdbc.sql("""
                        select group_id, subgroup_number, discipline_id, teacher_id, parity, lesson_kind
                        from template_lesson where version_id = ?
                        """)
                .param(versionId)
                .query((rs, rowNum) -> new TemplateLessonRow(
                        rs.getObject("group_id", UUID.class),
                        rs.getInt("subgroup_number"),
                        rs.getObject("discipline_id", UUID.class),
                        rs.getObject("teacher_id", UUID.class),
                        Parity.valueOf(rs.getString("parity")),
                        LessonKind.valueOf(rs.getString("lesson_kind"))))
                .list();
        return Optional.of(new VersionLessons(versionId, semesterId.get(), lessons));
    }

    record VersionLessons(UUID versionId, UUID semesterId, List<TemplateLessonRow> lessons) {
    }
}
