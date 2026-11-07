package ua.vtc.rozklad.core.timetable.internal.support;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * Read model of the load module ({@code load_item}, {@code load_item_teacher}) for generation,
 * conflict checks and validation. SQL instead of the load repositories: the load module derives
 * items from timetable versions (it depends on timetable), so a Java dependency back would be a
 * module cycle.
 */
@Repository
public class LoadReadRepository {

    private final JdbcClient jdbc;

    public LoadReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Required room type of each load item that has one. */
    public Map<UUID, RoomType> requiredRoomTypes(Collection<UUID> loadItemIds) {
        if (loadItemIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, RoomType> types = new HashMap<>();
        jdbc.sql("""
                        SELECT id, required_room_type FROM load_item
                        WHERE id IN (:ids) AND required_room_type IS NOT NULL
                        """)
                .param("ids", List.copyOf(loadItemIds))
                .query(rs -> {
                    types.put(rs.getObject("id", UUID.class), RoomType.valueOf(rs.getString("required_room_type")));
                });
        return types;
    }

    public Optional<LoadItemKey> findKey(UUID loadItemId) {
        return jdbc.sql("SELECT id, semester_id, group_id, discipline_id FROM load_item WHERE id = :id")
                .param("id", loadItemId)
                .query((rs, rowNum) -> new LoadItemKey(rs.getObject("id", UUID.class),
                        rs.getObject("semester_id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getObject("discipline_id", UUID.class)))
                .optional();
    }

    /** Load items of the semester for the groups, each with its teachers per subgroup. */
    public List<LoadRow> forGroups(UUID semesterId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, LoadRow.Builder> rows = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT li.id, li.group_id, li.discipline_id, li.lesson_kind, li.hours_per_week,
                               li.split_subgroups, li.required_room_type, li.preferred_room_id, li.stream_id,
                               lit.subgroup_number, lit.teacher_id
                        FROM load_item li
                        LEFT JOIN load_item_teacher lit ON lit.load_item_id = li.id
                        WHERE li.semester_id = :semesterId AND li.group_id IN (:groupIds)
                        ORDER BY li.group_id, li.discipline_id, li.id, lit.subgroup_number
                        """)
                .param("semesterId", semesterId)
                .param("groupIds", List.copyOf(groupIds))
                .query(rs -> {
                    LoadRow.Builder row = rows.computeIfAbsent(rs.getObject("id", UUID.class),
                            id -> builder(id, rs));
                    UUID teacherId = rs.getObject("teacher_id", UUID.class);
                    if (teacherId != null) {
                        row.teachers.put(rs.getInt("subgroup_number"), teacherId);
                    }
                });
        return rows.values().stream().map(LoadRow.Builder::build).toList();
    }

    private static LoadRow.Builder builder(UUID id, ResultSet rs) {
        try {
            String requiredType = rs.getString("required_room_type");
            return new LoadRow.Builder(id, rs.getObject("group_id", UUID.class),
                    rs.getObject("discipline_id", UUID.class), LessonKind.valueOf(rs.getString("lesson_kind")),
                    rs.getBigDecimal("hours_per_week"), rs.getBoolean("split_subgroups"),
                    requiredType == null ? null : RoomType.valueOf(requiredType),
                    rs.getObject("preferred_room_id", UUID.class), rs.getObject("stream_id", UUID.class));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read load_item row " + id, e);
        }
    }

    public record LoadItemKey(UUID id, UUID semesterId, UUID groupId, UUID disciplineId) {
    }

    /**
     * A load item with its teachers: {@code teachersBySubgroup} maps subgroup number (0 = whole
     * group) to the teacher.
     */
    public record LoadRow(UUID id, UUID groupId, UUID disciplineId, LessonKind lessonKind, BigDecimal hoursPerWeek,
                          boolean splitSubgroups, RoomType requiredRoomType, UUID preferredRoomId, UUID streamId,
                          Map<Integer, UUID> teachersBySubgroup) {

        public LoadRow {
            teachersBySubgroup = Map.copyOf(teachersBySubgroup);
        }

        static final class Builder {

            private final UUID id;
            private final UUID groupId;
            private final UUID disciplineId;
            private final LessonKind lessonKind;
            private final BigDecimal hoursPerWeek;
            private final boolean splitSubgroups;
            private final RoomType requiredRoomType;
            private final UUID preferredRoomId;
            private final UUID streamId;
            private final Map<Integer, UUID> teachers = new TreeMap<>();

            Builder(UUID id, UUID groupId, UUID disciplineId, LessonKind lessonKind, BigDecimal hoursPerWeek,
                    boolean splitSubgroups, RoomType requiredRoomType, UUID preferredRoomId, UUID streamId) {
                this.id = id;
                this.groupId = groupId;
                this.disciplineId = disciplineId;
                this.lessonKind = lessonKind;
                this.hoursPerWeek = hoursPerWeek;
                this.splitSubgroups = splitSubgroups;
                this.requiredRoomType = requiredRoomType;
                this.preferredRoomId = preferredRoomId;
                this.streamId = streamId;
            }

            LoadRow build() {
                return new LoadRow(id, groupId, disciplineId, lessonKind, hoursPerWeek, splitSubgroups,
                        requiredRoomType, preferredRoomId, streamId, teachers);
            }
        }
    }
}
