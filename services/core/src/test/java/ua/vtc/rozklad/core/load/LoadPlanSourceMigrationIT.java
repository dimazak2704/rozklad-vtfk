package ua.vtc.rozklad.core.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ua.vtc.rozklad.core.support.TestcontainersConfiguration;

/**
 * The data rule of {@code V1_302} on items that existed before it: a plan other than the old default (hours per week ×
 * the semester's weeks) was set explicitly and becomes FIXED, except a curriculum item's, which is the curriculum's
 * own total (its weeks × hours, 22 weeks for 4КІ) and by the owner's rule of 15.11.2026 does not fix the plan. The
 * shared test database is migrated empty, so this runs the core migrations up to {@code V1_301} on a database of its
 * own, inserts the items and migrates the rest.
 */
class LoadPlanSourceMigrationIT {

    @Test
    void anExplicitPlanBecomesFixedButACurriculumTotalStaysAuto() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES)) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            migrate(dataSource, "1.301");

            UUID year = UUID.randomUUID();
            UUID semester = UUID.randomUUID();
            UUID specialty = UUID.randomUUID();
            UUID group = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO academic_year (id, name, starts_on, ends_on, created_at, updated_at)
                    VALUES (?, '2026/2027', DATE '2026-09-01', DATE '2027-08-31', now(), now())""", year);
            // 01.09 (вівторок) – 30.12 без weeks_count: 18 тижнів від понеділка 31.08 до понеділка 28.12
            jdbc.update("""
                    INSERT INTO semester (id, academic_year_id, number, name, starts_on, ends_on, created_at, updated_at)
                    VALUES (?, ?, 1, 'I семестр 2026/2027', DATE '2026-09-01', DATE '2026-12-30', now(), now())""",
                    semester, year);
            jdbc.update("""
                    INSERT INTO specialty (id, letters, name, created_at, updated_at)
                    VALUES (?, 'КІ', 'Комп’ютерна інженерія', now(), now())""", specialty);
            jdbc.update("""
                    INSERT INTO student_group (id, code, course, specialty_id, created_at, updated_at)
                    VALUES (?, '4КІ2', 4, ?, now(), now())""", group, specialty);

            Map<String, UUID> items = new HashMap<>();
            // старий план за замовчуванням: 3 × 18
            items.put("derived default", item(jdbc, semester, group, "ДК", "3", 54, "TIMETABLE_DERIVED"));
            items.put("manual set", item(jdbc, semester, group, "СТП", "3", 50, "MANUAL"));
            // підсумок навчального плану 4КІ: 22 тижні × 3 год
            items.put("curriculum total", item(jdbc, semester, group, "КМ", "3", 66, "CURRICULUM"));
            items.put("curriculum default", item(jdbc, semester, group, "Соц.", "2", 36, "CURRICULUM"));
            items.put("program set", item(jdbc, semester, group, "НДЕКСМ", "2", 40, "PROGRAM"));

            migrate(dataSource, "latest");

            Map<UUID, String> sources = new HashMap<>();
            jdbc.queryForList("SELECT id, plan_source FROM load_item")
                    .forEach(row -> sources.put((UUID) row.get("id"), (String) row.get("plan_source")));
            assertThat(sources.get(items.get("derived default"))).isEqualTo("AUTO");
            assertThat(sources.get(items.get("manual set"))).isEqualTo("FIXED");
            assertThat(sources.get(items.get("curriculum total"))).as("a curriculum total does not fix the plan")
                    .isEqualTo("AUTO");
            assertThat(sources.get(items.get("curriculum default"))).isEqualTo("AUTO");
            assertThat(sources.get(items.get("program set"))).isEqualTo("FIXED");
            assertThat(jdbc.queryForList("SELECT planned_hours FROM load_item WHERE id = ?", Integer.class,
                    items.get("curriculum total"))).as("the migration only marks, core recounts at start-up")
                    .containsExactly(66);
        }
    }

    private static UUID item(JdbcTemplate jdbc, UUID semester, UUID group, String discipline, String hoursPerWeek,
                             int plannedHours, String source) {
        UUID disciplineId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO discipline (id, name, short_name, created_at, updated_at)
                VALUES (?, ?, ?, now(), now())""", disciplineId, discipline, discipline);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO load_item (id, semester_id, group_id, discipline_id, hours_per_week, planned_hours, source,
                                       created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""",
                id, semester, group, disciplineId, new BigDecimal(hoursPerWeek), plannedHours, source);
        return id;
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).target(target)
                .load().migrate();
    }
}
