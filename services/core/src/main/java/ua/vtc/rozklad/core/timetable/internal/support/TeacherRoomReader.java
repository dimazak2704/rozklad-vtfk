package ua.vtc.rozklad.core.timetable.internal.support;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.timetable.TeacherRoomQueries;
import ua.vtc.rozklad.core.timetable.TeacherRooms;

/**
 * {@link TeacherRoomQueries}: room uses of template lessons, one aggregate statement per call. A
 * lesson of both weeks counts 2 occurrences, a numerator or denominator lesson 1; online lessons
 * and lessons without a teacher or a room are skipped.
 */
@Service
@Transactional(readOnly = true)
public class TeacherRoomReader implements TeacherRoomQueries {

    private static final String USES = """
            SELECT t.teacher_id, t.discipline_id, t.group_id, t.room_id, r.name AS room_name,
                   SUM(CASE WHEN t.parity = 'BOTH' THEN 2 ELSE 1 END) AS occurrences
            FROM template_lesson t
            JOIN room r ON r.id = t.room_id
            WHERE %s AND t.teacher_id IS NOT NULL AND NOT t.online
            GROUP BY t.teacher_id, t.discipline_id, t.group_id, t.room_id, r.name
            """;

    private final JdbcClient jdbc;

    public TeacherRoomReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TeacherRooms published(UUID semesterId, Collection<UUID> teacherIds) {
        List<UUID> ids = teacherIds.stream().filter(Objects::nonNull).distinct().toList();
        if (semesterId == null || ids.isEmpty()) {
            return TeacherRooms.NONE;
        }
        return TeacherRooms.of(jdbc.sql(USES.formatted("""
                        t.version_id = (SELECT v.id FROM timetable_version v
                                        WHERE v.semester_id = :semesterId AND v.status = 'PUBLISHED')
                        AND t.teacher_id IN (:teacherIds)"""))
                .param("semesterId", semesterId)
                .param("teacherIds", ids)
                .query(TeacherRoomReader::use)
                .list());
    }

    /** Usual rooms of every teacher of the version (the generator's base when nothing is published). */
    public TeacherRooms ofVersion(UUID versionId) {
        if (versionId == null) {
            return TeacherRooms.NONE;
        }
        return TeacherRooms.of(jdbc.sql(USES.formatted("t.version_id = :versionId"))
                .param("versionId", versionId)
                .query(TeacherRoomReader::use)
                .list());
    }

    private static TeacherRooms.RoomUse use(ResultSet rs, int rowNum) throws SQLException {
        return new TeacherRooms.RoomUse(rs.getObject("teacher_id", UUID.class),
                rs.getObject("discipline_id", UUID.class), rs.getObject("group_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("room_name"), rs.getInt("occurrences"));
    }
}
