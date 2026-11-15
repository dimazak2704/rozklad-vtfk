package ua.vtc.rozklad.core.load.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /**
     * Weekly slots of the semester's PUBLISHED version (at most one), of {@code groupIds} only when given: what the
     * plan of an item is counted from. One query; empty when nothing is published.
     */
    List<PlanSlotRow> publishedSlots(UUID semesterId, Collection<UUID> groupIds) {
        if (groupIds == null) {
            return slots(PUBLISHED_SLOTS, Map.of("semester", semesterId), null);
        }
        return slots(PUBLISHED_GROUP_SLOTS, Map.of("semester", semesterId), groupIds);
    }

    /** Weekly slots of one version (the one just published, whose status may not be flushed yet). */
    List<PlanSlotRow> versionSlots(UUID versionId) {
        return slots(VERSION_SLOTS, Map.of("version", versionId), null);
    }

    // постійні тексти запитів: значення йдуть лише параметрами
    private static final String SLOTS = """
            select t.group_id, t.discipline_id, t.subgroup_number, t.weekday, t.parity, t.lesson_kind, t.load_item_id
            from template_lesson t join timetable_version v on v.id = t.version_id
            """;
    private static final String PUBLISHED_SLOTS = SLOTS + "where v.semester_id = :semester and v.status = 'PUBLISHED'";
    private static final String PUBLISHED_GROUP_SLOTS = PUBLISHED_SLOTS + " and t.group_id in (:groups)";
    private static final String VERSION_SLOTS = SLOTS + "where v.id = :version";

    private List<PlanSlotRow> slots(String sql, Map<String, Object> params, Collection<UUID> groupIds) {
        if (groupIds != null && groupIds.isEmpty()) {
            return List.of();
        }
        var statement = jdbc.sql(sql);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        if (groupIds != null) {
            statement = statement.param("groups", List.copyOf(groupIds));
        }
        return statement.query((rs, rowNum) -> new PlanSlotRow(
                        rs.getObject("group_id", UUID.class),
                        rs.getObject("discipline_id", UUID.class),
                        rs.getInt("subgroup_number"),
                        rs.getInt("weekday"),
                        Parity.valueOf(rs.getString("parity")),
                        LessonKind.valueOf(rs.getString("lesson_kind")),
                        rs.getObject("load_item_id", UUID.class)))
                .list();
    }

    record VersionLessons(UUID versionId, UUID semesterId, List<TemplateLessonRow> lessons) {
    }

    /** One weekly lesson as the plan sees it; {@code loadItemId} is {@code null} for imported lessons. */
    record PlanSlotRow(UUID groupId, UUID disciplineId, int subgroup, int weekday, Parity parity, LessonKind lessonKind,
                       UUID loadItemId) {
    }
}
