package ua.vtc.rozklad.core.timetable.internal.support;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotPreference;
import ua.vtc.rozklad.core.timetable.internal.conflict.TeacherSlotPreferences;

/**
 * Teacher preferences of a semester read straight from {@code teacher_preference}. The table
 * belongs to the substitution module, which itself depends on timetable (lessons); a Java
 * dependency back would be a module cycle that Spring Modulith rejects, so this read model uses SQL.
 */
@Repository
public class PreferenceReadRepository {

    private final JdbcClient jdbc;

    public PreferenceReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public TeacherSlotPreferences forSemester(UUID semesterId) {
        if (semesterId == null) {
            return TeacherSlotPreferences.none();
        }
        List<TeacherSlotPreferences.Entry> entries = jdbc.sql("""
                        SELECT teacher_id, weekday, pair_number, kind
                        FROM teacher_preference
                        WHERE semester_id = :semesterId
                        """)
                .param("semesterId", semesterId)
                .query((rs, rowNum) -> new TeacherSlotPreferences.Entry(rs.getObject("teacher_id", UUID.class),
                        rs.getInt("weekday"), rs.getInt("pair_number"), SlotPreference.valueOf(rs.getString("kind"))))
                .list();
        return new TeacherSlotPreferences(entries);
    }
}
