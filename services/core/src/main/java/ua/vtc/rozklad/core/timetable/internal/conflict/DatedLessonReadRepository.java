package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * SCHEDULED dated lessons as conflict-engine input, with the room type required by the load item
 * of their template lesson (one query per call). Online lessons are given without their room.
 */
@Repository
class DatedLessonReadRepository {

    private static final String SELECT = """
            SELECT l.id, l.semester_id, l.date, l.pair_number, l.group_id, l.subgroup_number, l.discipline_id,
                   l.teacher_id, l.room_id, l.online, l.stream_id, li.required_room_type
            FROM lesson l
            LEFT JOIN template_lesson t ON t.id = l.template_lesson_id
            LEFT JOIN load_item li ON li.id = t.load_item_id
            WHERE l.status = 'SCHEDULED'
            """;

    private final JdbcClient jdbc;

    DatedLessonReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DatedLesson> scheduledByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " AND l.id IN (:ids)")
                .param("ids", List.copyOf(ids))
                .query(DatedLessonReadRepository::map)
                .list();
    }

    public List<DatedLesson> scheduledOn(Collection<LocalDate> dates) {
        if (dates.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " AND l.date IN (:dates)")
                .param("dates", List.copyOf(dates))
                .query(DatedLessonReadRepository::map)
                .list();
    }

    private static DatedLesson map(ResultSet rs, int rowNum) throws SQLException {
        String requiredType = rs.getString("required_room_type");
        return new DatedLesson(rs.getObject("semester_id", UUID.class), new SlotLesson(
                rs.getObject("id", UUID.class),
                LessonDay.ofDate(rs.getObject("date", LocalDate.class)),
                rs.getInt("pair_number"),
                Parity.BOTH,
                rs.getObject("group_id", UUID.class),
                rs.getInt("subgroup_number"),
                rs.getObject("discipline_id", UUID.class),
                rs.getObject("teacher_id", UUID.class),
                // онлайн-заняття не займає аудиторію (DOMAIN §5): рушій бачить його без аудиторії
                rs.getBoolean("online") ? null : rs.getObject("room_id", UUID.class),
                rs.getObject("stream_id", UUID.class),
                requiredType == null ? null : RoomType.valueOf(requiredType)));
    }

    record DatedLesson(UUID semesterId, SlotLesson lesson) {
    }
}
