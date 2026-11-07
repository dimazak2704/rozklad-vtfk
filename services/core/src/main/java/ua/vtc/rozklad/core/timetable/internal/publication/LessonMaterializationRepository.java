package ua.vtc.rozklad.core.timetable.internal.publication;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk SQL of a publication: thousands of dated lessons are deleted and inserted in batches instead
 * of one entity at a time. Runs in the caller's transaction (same connection as JPA).
 */
@Repository
class LessonMaterializationRepository {

    private static final int BATCH_SIZE = 500;

    private static final String INSERT = """
            INSERT INTO lesson (id, semester_id, version_id, template_lesson_id, date, pair_number, group_id,
                                subgroup_number, discipline_id, teacher_id, room_id, room_exclusive, status, origin,
                                stream_id, lesson_kind, online, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', 'TEMPLATE', ?, ?, ?, ?, ?, 0)
            """;

    private final JdbcTemplate jdbc;

    LessonMaterializationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Deletes TEMPLATE lessons of the semester from {@code from} on that no change created, modified
     * or refers to (DOMAIN.md §6); everything else is kept.
     */
    public int deleteReplaceable(UUID semesterId, LocalDate from) {
        return jdbc.update("""
                DELETE FROM lesson l
                WHERE l.semester_id = ? AND l.date >= ? AND l.origin = 'TEMPLATE' AND l.change_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM change c WHERE c.original_lesson_id = l.id)
                """, semesterId, from);
    }

    /** Lessons of the semester from {@code from} on that survived {@link #deleteReplaceable}. */
    public List<KeptLesson> kept(UUID semesterId, LocalDate from) {
        return jdbc.query("""
                SELECT id, date, pair_number, group_id, subgroup_number, teacher_id, room_id, room_exclusive,
                       stream_id, status
                FROM lesson
                WHERE semester_id = ? AND date >= ?
                ORDER BY date, pair_number
                """, (rs, rowNum) -> new KeptLesson(rs.getObject("id", UUID.class),
                rs.getObject("date", LocalDate.class), rs.getInt("pair_number"), rs.getObject("group_id", UUID.class),
                rs.getInt("subgroup_number"), rs.getObject("teacher_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getBoolean("room_exclusive"),
                rs.getObject("stream_id", UUID.class), "SCHEDULED".equals(rs.getString("status"))), semesterId, from);
    }

    public void insert(List<NewLesson> lessons, Instant now) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.batchUpdate(INSERT, lessons, BATCH_SIZE, (statement, lesson) -> bind(statement, lesson, timestamp));
    }

    private static void bind(PreparedStatement statement, NewLesson lesson, OffsetDateTime timestamp)
            throws SQLException {
        statement.setObject(1, lesson.id());
        statement.setObject(2, lesson.semesterId());
        statement.setObject(3, lesson.versionId());
        statement.setObject(4, lesson.templateLessonId());
        statement.setObject(5, lesson.date());
        statement.setInt(6, lesson.pair());
        statement.setObject(7, lesson.groupId());
        statement.setInt(8, lesson.subgroup());
        statement.setObject(9, lesson.disciplineId());
        setUuid(statement, 10, lesson.teacherId());
        setUuid(statement, 11, lesson.roomId());
        statement.setBoolean(12, lesson.roomExclusive());
        setUuid(statement, 13, lesson.streamId());
        statement.setString(14, lesson.lessonKind().name());
        statement.setBoolean(15, lesson.online());
        statement.setObject(16, timestamp);
        statement.setObject(17, timestamp);
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.OTHER);
        } else {
            statement.setObject(index, value);
        }
    }
}
