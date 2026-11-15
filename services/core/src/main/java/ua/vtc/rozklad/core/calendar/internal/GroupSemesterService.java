package ua.vtc.rozklad.core.calendar.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;

/**
 * Theory weeks and exams of a group in a semester from the college's academic calendar (table
 * {@code group_semester}, written by the import, read by the admin API). Step 1 only stores them; load and
 * generation use them in step 2. Plain JDBC: the table has a natural key and no behaviour.
 */
@Service
@Transactional(readOnly = true)
public class GroupSemesterService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogQueries catalog;
    private final Clock clock;

    GroupSemesterService(NamedParameterJdbcTemplate jdbc, CatalogQueries catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Rows of the semester sorted by group code. */
    public List<GroupSemesterDto> list(UUID semesterId) {
        List<Row> rows = rows(List.of(semesterId));
        Map<UUID, GroupRef> groups = catalog.groupRefs(rows.stream().map(Row::groupId).toList());
        return rows.stream()
                .filter(row -> groups.get(row.groupId()) != null)
                .map(row -> new GroupSemesterDto(row.semesterId(), groups.get(row.groupId()), row.theoryWeeks(),
                        row.exams(), row.source(), row.updatedAt()))
                .sorted(Comparator.comparing(dto -> dto.group().code(), UkrainianOrder.NATURAL))
                .toList();
    }

    List<Row> rows(Collection<UUID> semesterIds) {
        if (semesterIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT semester_id, group_id, theory_weeks, exams, source, updated_at
                        FROM group_semester WHERE semester_id IN (:ids)
                        """, Map.of("ids", semesterIds),
                (rs, i) -> new Row(rs.getObject("semester_id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getObject("theory_weeks", Integer.class), rs.getObject("exams", Integer.class),
                        rs.getString("source"),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    /** Inserts or overwrites the rows as IMPORT (one batch). */
    @Transactional
    void upsertImported(Collection<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        SqlParameterSource[] batch = rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("semesterId", row.semesterId())
                        .addValue("groupId", row.groupId())
                        .addValue("theoryWeeks", row.theoryWeeks())
                        .addValue("exams", row.exams())
                        .addValue("updatedAt", now))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO group_semester (semester_id, group_id, theory_weeks, exams, source, updated_at)
                VALUES (:semesterId, :groupId, CAST(:theoryWeeks AS smallint), CAST(:exams AS smallint), 'IMPORT',
                        :updatedAt)
                ON CONFLICT (semester_id, group_id) DO UPDATE
                SET theory_weeks = excluded.theory_weeks, exams = excluded.exams, source = 'IMPORT',
                    updated_at = excluded.updated_at
                """, batch);
    }

    /** A stored row; {@code source} MANUAL | IMPORT. */
    record Row(UUID semesterId, UUID groupId, Integer theoryWeeks, Integer exams, String source, Instant updatedAt) {

        boolean sameNumbers(Integer otherWeeks, Integer otherExams) {
            return Objects.equals(theoryWeeks, otherWeeks) && Objects.equals(exams, otherExams);
        }
    }

    /** {@code GET /api/admin/group-semesters}: {@code theoryWeeks}/{@code exams} are {@code null} when the calendar leaves them empty. */
    public record GroupSemesterDto(UUID semesterId, GroupRef group, Integer theoryWeeks, Integer exams, String source,
                                   Instant updatedAt) {
    }
}
