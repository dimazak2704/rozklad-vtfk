package ua.vtc.rozklad.core.timetable.internal.publication;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    /**
     * A change that still keeps its lesson: anything but an abandoned decision. A declined change
     * waits for a new decision and an applied one keeps its cancelled original as history (a block
     * keeps every lesson it cancelled, {@code change_replaced_lesson}).
     */
    private static final String LIVE_DECISION = """
            SELECT 1 FROM change c
            WHERE (c.original_lesson_id = l.id OR c.swap_lesson_id = l.id
                   OR c.id IN (SELECT rl.change_id FROM change_replaced_lesson rl WHERE rl.lesson_id = l.id))
              AND c.status NOT IN ('CANCELLED', 'DECLINED')""";

    /**
     * Lessons matching a condition on {@code l} with what the planner needs to know about them,
     * including the template lesson each stands for ({@link KeptLesson.Root}): a lesson created by a
     * change follows the change's original back to a template lesson; an extra lesson or a block
     * stands for none.
     */
    private static final String KEPT = """
            WITH RECURSIVE base AS (
                SELECT l.id, l.date, l.pair_number, l.group_id, l.subgroup_number, l.teacher_id, l.room_id,
                       l.room_exclusive, l.stream_id, l.status, l.online, l.template_lesson_id, l.change_id,
                       (l.change_id IS NOT NULL OR l.origin <> 'TEMPLATE') AS from_order
                FROM lesson l
                WHERE %s
            ),
            origin(lesson_id, cur_id, depth) AS (
                SELECT b.id, c.original_lesson_id, 1
                FROM base b JOIN change c ON c.id = b.change_id
                WHERE b.template_lesson_id IS NULL AND c.kind NOT IN ('ADDITIONAL', 'BLOCK')
                  AND c.original_lesson_id IS NOT NULL
                UNION ALL
                SELECT o.lesson_id, c.original_lesson_id, o.depth + 1
                FROM origin o JOIN lesson cur ON cur.id = o.cur_id JOIN change c ON c.id = cur.change_id
                WHERE cur.template_lesson_id IS NULL AND c.kind NOT IN ('ADDITIONAL', 'BLOCK')
                  AND c.original_lesson_id IS NOT NULL AND o.depth < 16
            ),
            root AS (
                SELECT o.lesson_id, cur.template_lesson_id
                FROM origin o JOIN lesson cur ON cur.id = o.cur_id
                WHERE cur.template_lesson_id IS NOT NULL
            )
            SELECT b.id, b.date, b.pair_number, b.group_id, b.subgroup_number, b.teacher_id, b.room_id,
                   b.room_exclusive, b.stream_id, b.status, b.online, b.from_order, g.course,
                   COALESCE(r.max_parallel, 1) > 1 AS shared_room,
                   t.id AS root_template_id, t.version_id AS root_version_id, t.online AS root_online
            FROM base b
            JOIN student_group g ON g.id = b.group_id
            LEFT JOIN room r ON r.id = b.room_id
            LEFT JOIN root ON root.lesson_id = b.id
            LEFT JOIN template_lesson t ON t.id = COALESCE(b.template_lesson_id, root.template_lesson_id)
            ORDER BY b.date, b.pair_number
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    LessonMaterializationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * Deletes TEMPLATE lessons of the semester from {@code from} on that no change created, modified
     * or refers to (DOMAIN.md §6); everything else is kept. A change refers to a lesson as its
     * original or as the lesson a SWAP moves. A cancelled or declined change is not a live decision
     * and pins nothing: its link to a deleted lesson is cleared by the foreign key (V1_502).
     */
    public int deleteReplaceable(UUID semesterId, LocalDate from) {
        return jdbc.update("""
                DELETE FROM lesson l
                WHERE l.semester_id = ? AND l.date >= ? AND l.origin = 'TEMPLATE' AND l.change_id IS NULL
                  AND NOT EXISTS (%s)
                """.formatted(LIVE_DECISION), semesterId, from);
    }

    /**
     * Template lessons from {@code from} on that survive only because an open (not yet applied)
     * decision refers to them, with the version they come from.
     */
    public List<PinnedLesson> pinnedByOpenDecisions(UUID semesterId, LocalDate from) {
        return jdbc.query("""
                SELECT l.id, l.date, l.pair_number, l.subgroup_number, l.version_id, g.code AS group_code,
                       d.short_name AS discipline_name, t.short_name AS teacher_name
                FROM lesson l
                JOIN student_group g ON g.id = l.group_id
                JOIN discipline d ON d.id = l.discipline_id
                LEFT JOIN teacher t ON t.id = l.teacher_id
                WHERE l.semester_id = ? AND l.date >= ? AND l.origin = 'TEMPLATE' AND l.change_id IS NULL
                  AND l.status = 'SCHEDULED'
                  AND EXISTS (SELECT 1 FROM change c
                              WHERE (c.original_lesson_id = l.id OR c.swap_lesson_id = l.id)
                                AND c.status IN ('DRAFT', 'PENDING_CONFIRMATION', 'CONFIRMED'))
                ORDER BY l.date, l.pair_number
                """, (rs, rowNum) -> new PinnedLesson(rs.getObject("id", UUID.class),
                rs.getObject("date", LocalDate.class), rs.getInt("pair_number"), rs.getInt("subgroup_number"),
                rs.getObject("version_id", UUID.class), rs.getString("group_code"), rs.getString("discipline_name"),
                rs.getString("teacher_name")), semesterId, from);
    }

    /**
     * Locks the lessons of the group-days in the order change creation locks one group-day (group,
     * date, pair), so a decision cannot pick a lesson that a refresh is about to delete.
     */
    public void lockGroupDays(Collection<LocalDate> dates, Collection<UUID> groupIds) {
        named.query("""
                SELECT id FROM lesson WHERE date IN (:dates) AND group_id IN (:groups)
                ORDER BY group_id, date, pair_number, id
                FOR UPDATE
                """, Map.of("dates", dates, "groups", groupIds), (rs, rowNum) -> rs.getObject(1, UUID.class));
    }

    /** Template lessons of the group-days that no change created or keeps (see {@link #deleteReplaceable}). */
    public List<ReplaceableLesson> replaceable(Collection<LocalDate> dates, Collection<UUID> groupIds) {
        return named.query("""
                SELECT l.id, l.date, l.template_lesson_id, l.online, l.room_exclusive, l.status
                FROM lesson l
                WHERE l.date IN (:dates) AND l.group_id IN (:groups) AND l.origin = 'TEMPLATE'
                  AND l.change_id IS NULL AND NOT EXISTS (%s)
                """.formatted(LIVE_DECISION), Map.of("dates", dates, "groups", groupIds),
                (rs, rowNum) -> new ReplaceableLesson(rs.getObject("id", UUID.class),
                        rs.getObject("date", LocalDate.class), rs.getObject("template_lesson_id", UUID.class),
                        rs.getBoolean("online"), rs.getBoolean("room_exclusive"),
                        "SCHEDULED".equals(rs.getString("status"))));
    }

    /**
     * Scheduled lessons of the group-days that a refresh never touches: created by a change
     * (origin other than TEMPLATE, or {@code change_id} set) or kept for a live decision. A calendar
     * edit must not leave them on a day the group has no classes.
     */
    public List<StrandedLesson> keptScheduled(Collection<LocalDate> dates, Collection<UUID> groupIds) {
        return named.query("""
                SELECT l.id, l.date, l.pair_number, l.subgroup_number, l.group_id, g.code AS group_code,
                       g.course, d.short_name AS discipline_name, t.short_name AS teacher_name,
                       (l.change_id IS NOT NULL OR l.origin <> 'TEMPLATE') AS from_order
                FROM lesson l
                JOIN student_group g ON g.id = l.group_id
                JOIN discipline d ON d.id = l.discipline_id
                LEFT JOIN teacher t ON t.id = l.teacher_id
                WHERE l.date IN (:dates) AND l.group_id IN (:groups) AND l.status = 'SCHEDULED'
                  AND (l.change_id IS NOT NULL OR l.origin <> 'TEMPLATE' OR EXISTS (%s))
                ORDER BY l.date, g.code, l.pair_number
                """.formatted(LIVE_DECISION), Map.of("dates", dates, "groups", groupIds),
                (rs, rowNum) -> new StrandedLesson(rs.getObject("id", UUID.class),
                        rs.getObject("date", LocalDate.class), rs.getInt("pair_number"), rs.getInt("subgroup_number"),
                        rs.getObject("group_id", UUID.class), rs.getString("group_code"), rs.getInt("course"),
                        rs.getString("discipline_name"), rs.getString("teacher_name"), rs.getBoolean("from_order")));
    }

    /** Every lesson of the dates (any group, any status), as the planner sees lessons that stay. */
    public List<KeptLesson> lessonsOn(Collection<LocalDate> dates) {
        return named.query(KEPT.formatted("l.date IN (:dates)"), Map.of("dates", dates),
                (rs, rowNum) -> keptLesson(rs));
    }

    /** Names of one lesson for a conflict message. */
    public LessonLabel label(UUID lessonId) {
        return jdbc.queryForObject("""
                SELECT g.code, d.short_name AS discipline_name, t.short_name AS teacher_name, r.name AS room_name
                FROM lesson l
                JOIN student_group g ON g.id = l.group_id
                JOIN discipline d ON d.id = l.discipline_id
                LEFT JOIN teacher t ON t.id = l.teacher_id
                LEFT JOIN room r ON r.id = l.room_id
                WHERE l.id = ?
                """, (rs, rowNum) -> new LessonLabel(rs.getString("code"), rs.getString("discipline_name"),
                rs.getString("teacher_name"), rs.getString("room_name")), lessonId);
    }

    record LessonLabel(String groupCode, String disciplineName, String teacherName, String roomName) {
    }

    public void delete(Collection<UUID> ids) {
        jdbc.batchUpdate("DELETE FROM lesson WHERE id = ?", List.copyOf(ids), BATCH_SIZE,
                (statement, id) -> statement.setObject(1, id));
    }

    /** A study-mode edit: the lesson is (no longer) online and so does (not) occupy its room. */
    public void updateMode(Collection<ModeUpdate> lessons, Instant now) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.batchUpdate("UPDATE lesson SET online = ?, room_exclusive = ?, updated_at = ?, version = version + 1 "
                + "WHERE id = ?", List.copyOf(lessons), BATCH_SIZE, (statement, lesson) -> {
                    statement.setBoolean(1, lesson.online());
                    statement.setBoolean(2, lesson.roomExclusive());
                    statement.setObject(3, timestamp);
                    statement.setObject(4, lesson.id());
                });
    }

    /** New study mode of one dated lesson. */
    record ModeUpdate(UUID id, boolean online, boolean roomExclusive) {
    }

    /** Lessons of the semester from {@code from} on that survived {@link #deleteReplaceable}. */
    public List<KeptLesson> kept(UUID semesterId, LocalDate from) {
        return jdbc.query(KEPT.formatted("l.semester_id = ? AND l.date >= ?"), (rs, rowNum) -> keptLesson(rs),
                semesterId, from);
    }

    private static KeptLesson keptLesson(ResultSet rs) throws SQLException {
        UUID rootId = rs.getObject("root_template_id", UUID.class);
        KeptLesson.Root root = rootId == null ? null
                : new KeptLesson.Root(rootId, rs.getObject("root_version_id", UUID.class), rs.getBoolean("root_online"));
        return new KeptLesson(rs.getObject("id", UUID.class), rs.getObject("date", LocalDate.class),
                rs.getInt("pair_number"), rs.getObject("group_id", UUID.class), rs.getInt("subgroup_number"),
                rs.getObject("teacher_id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getBoolean("room_exclusive"), rs.getObject("stream_id", UUID.class),
                "SCHEDULED".equals(rs.getString("status")), rs.getBoolean("online"), rs.getInt("course"),
                rs.getBoolean("shared_room"), root, rs.getBoolean("from_order"));
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
