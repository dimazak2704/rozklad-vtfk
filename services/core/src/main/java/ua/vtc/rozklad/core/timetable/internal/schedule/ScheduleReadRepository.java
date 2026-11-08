package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonStatus;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;

/**
 * The hot path of the public schedule: one indexed query returns every lesson of a group, teacher
 * or room in a date range together with its references and the change behind it. The {@code change}
 * table belongs to the substitution module; it is read with SQL because substitution depends on
 * timetable and a Java dependency back would be a module cycle.
 */
@Repository
class ScheduleReadRepository {

    private static final String SELECT = """
            SELECT l.id, l.date, l.pair_number, l.subgroup_number, l.status, l.origin, l.online, l.updated_at,
                   g.id AS group_id, g.code AS group_code, g.course AS group_course,
                   d.id AS discipline_id, d.short_name AS discipline_short_name, d.name AS discipline_name,
                   d.color AS discipline_color,
                   t.id AS teacher_id, t.short_name AS teacher_short_name, t.display_name AS teacher_display_name,
                   r.id AS room_id, r.name AS room_name, r.type AS room_type,
                   c.id AS change_id, c.kind AS change_kind, c.reason_code AS change_reason_code,
                   c.reason_text AS change_reason_text, c.pair_from AS change_pair_from, c.pair_to AS change_pair_to,
                   od.id AS orig_discipline_id, od.short_name AS orig_discipline_short_name,
                   od.name AS orig_discipline_name, od.color AS orig_discipline_color,
                   ot.id AS orig_teacher_id, ot.short_name AS orig_teacher_short_name,
                   ot.display_name AS orig_teacher_display_name,
                   orm.id AS orig_room_id, orm.name AS orig_room_name, orm.type AS orig_room_type,
                   ol.pair_number AS orig_pair
            FROM lesson l
            JOIN student_group g ON g.id = l.group_id
            JOIN discipline d ON d.id = l.discipline_id
            LEFT JOIN teacher t ON t.id = l.teacher_id
            LEFT JOIN room r ON r.id = l.room_id
            LEFT JOIN LATERAL (
                SELECT ch.id, ch.kind, ch.reason_code, ch.reason_text, ch.pair_from, ch.pair_to,
                       ch.original_lesson_id, ch.orig_discipline_id, ch.orig_teacher_id, ch.orig_room_id
                FROM change ch
                WHERE ch.id = l.change_id
                   OR (l.change_id IS NULL AND l.status = 'CANCELLED' AND ch.original_lesson_id = l.id
                       AND ch.status = 'APPLIED')
                ORDER BY (ch.id = l.change_id) DESC NULLS LAST, ch.updated_at DESC
                LIMIT 1
            ) c ON TRUE
            LEFT JOIN discipline od ON od.id = c.orig_discipline_id
            LEFT JOIN teacher ot ON ot.id = c.orig_teacher_id
            LEFT JOIN room orm ON orm.id = c.orig_room_id
            LEFT JOIN lesson ol ON ol.id = c.original_lesson_id
            """;

    private static final String ORDER = " ORDER BY l.date, l.pair_number, g.code, l.subgroup_number, l.id";

    private final JdbcClient jdbc;

    ScheduleReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lessons (scheduled and cancelled) of the owner on {@code [from, to]}. */
    public List<LessonRow> forOwner(ScheduleOwnerType type, UUID ownerId, LocalDate from, LocalDate to) {
        String ownerColumn = switch (type) {
            case GROUP -> "l.group_id";
            case TEACHER -> "l.teacher_id";
            case ROOM -> "l.room_id";
        };
        return jdbc.sql(SELECT + " WHERE " + ownerColumn + " = :ownerId AND l.date BETWEEN :from AND :to" + ORDER)
                .param("ownerId", ownerId)
                .param("from", from)
                .param("to", to)
                .query(ScheduleReadRepository::map)
                .list();
    }

    public List<LessonRow> byIds(Collection<UUID> lessonIds) {
        if (lessonIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " WHERE l.id IN (:ids)" + ORDER)
                .param("ids", List.copyOf(lessonIds))
                .query(ScheduleReadRepository::map)
                .list();
    }

    private static LessonRow map(ResultSet rs, int rowNum) throws SQLException {
        UUID changeId = rs.getObject("change_id", UUID.class);
        LessonRow.Change change = changeId == null ? null : new LessonRow.Change(changeId,
                rs.getString("change_kind"), rs.getString("change_reason_code"), rs.getString("change_reason_text"),
                rs.getInt("change_pair_from"), rs.getInt("change_pair_to"),
                discipline(rs, "orig_discipline_"), teacher(rs, "orig_teacher_"), room(rs, "orig_room_"),
                (Integer) rs.getObject("orig_pair"));
        return new LessonRow(
                rs.getObject("id", UUID.class),
                rs.getObject("date", LocalDate.class),
                rs.getInt("pair_number"),
                rs.getInt("subgroup_number"),
                LessonStatus.valueOf(rs.getString("status")),
                LessonOrigin.valueOf(rs.getString("origin")),
                rs.getBoolean("online"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                new GroupRef(rs.getObject("group_id", UUID.class), rs.getString("group_code"),
                        rs.getInt("group_course")),
                discipline(rs, "discipline_"),
                teacher(rs, "teacher_"),
                room(rs, "room_"),
                change);
    }

    private static DisciplineRef discipline(ResultSet rs, String prefix) throws SQLException {
        UUID id = rs.getObject(prefix + "id", UUID.class);
        return id == null ? null : new DisciplineRef(id, rs.getString(prefix + "short_name"),
                rs.getString(prefix + "name"), rs.getString(prefix + "color"));
    }

    private static TeacherRef teacher(ResultSet rs, String prefix) throws SQLException {
        UUID id = rs.getObject(prefix + "id", UUID.class);
        return id == null ? null : new TeacherRef(id, rs.getString(prefix + "short_name"),
                rs.getString(prefix + "display_name"));
    }

    private static RoomRef room(ResultSet rs, String prefix) throws SQLException {
        UUID id = rs.getObject(prefix + "id", UUID.class);
        return id == null ? null : new RoomRef(id, rs.getString(prefix + "name"),
                RoomType.valueOf(rs.getString(prefix + "type")));
    }
}
