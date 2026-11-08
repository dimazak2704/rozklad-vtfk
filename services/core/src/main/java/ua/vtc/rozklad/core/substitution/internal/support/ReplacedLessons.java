package ua.vtc.rozklad.core.substitution.internal.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Lessons a block of pairs (BLOCK) cancelled when its order was published. Other kinds name the
 * lesson they replace in {@code original_lesson_id} / {@code swap_lesson_id}; a block replaces every
 * lesson of its cohort in its pairs, so the links are kept here (table {@code change_replaced_lesson}).
 * Views show the block as the reason of those cancelled lessons, refreshes keep them and teacher
 * statistics count them as missed pairs.
 */
@Repository
public class ReplacedLessons {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    ReplacedLessons(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void record(UUID changeId, Collection<UUID> lessonIds) {
        if (lessonIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO change_replaced_lesson (change_id, lesson_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                List.copyOf(lessonIds), lessonIds.size(), (statement, lessonId) -> {
                    statement.setObject(1, changeId);
                    statement.setObject(2, lessonId);
                });
    }

    /** Lesson ids per change (changes without links are absent). */
    public Map<UUID, List<UUID>> of(Collection<UUID> changeIds) {
        Map<UUID, List<UUID>> result = new HashMap<>();
        if (changeIds.isEmpty()) {
            return result;
        }
        named.query("SELECT change_id, lesson_id FROM change_replaced_lesson WHERE change_id IN (:ids)",
                Map.of("ids", changeIds), rs -> {
                    result.computeIfAbsent(rs.getObject("change_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getObject("lesson_id", UUID.class));
                });
        return result;
    }
}
