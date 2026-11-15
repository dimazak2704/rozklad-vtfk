package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The backfill of {@code V1_203} on periods that existed before it. The shared test database is migrated empty and
 * gets its periods later (the demo seed {@code V9_001} goes through the compatibility trigger), so this runs the core
 * migrations up to {@code V1_202} on a database of its own, inserts one period of every legacy kind, and migrates the
 * rest.
 */
class PeriodKindsMigrationIT {

    @Test
    void existingPeriodsOfTheFiveLegacyKindsGetTheirBuiltinKinds() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES)) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            migrate(dataSource, "1.202");

            UUID year = UUID.randomUUID();
            UUID semester = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO academic_year (id, name, starts_on, ends_on, created_at, updated_at)
                    VALUES (?, '2026/2027', DATE '2026-09-01', DATE '2027-08-31', now(), now())""", year);
            jdbc.update("""
                    INSERT INTO semester (id, academic_year_id, number, name, starts_on, ends_on, created_at, updated_at)
                    VALUES (?, ?, 1, 'I семестр 2026/2027', DATE '2026-09-01', DATE '2026-12-30', now(), now())""",
                    semester, year);
            Map<UUID, CalendarPeriodKind> legacy = new HashMap<>();
            for (CalendarPeriodKind kind : CalendarPeriodKind.values()) {
                UUID id = UUID.randomUUID();
                legacy.put(id, kind);
                jdbc.update("""
                        INSERT INTO calendar_period (id, semester_id, kind, title, starts_on, ends_on, study_mode,
                                                     created_at, updated_at)
                        VALUES (?, ?, ?, ?, DATE '2026-10-19', DATE '2026-10-30', ?, now(), now())""",
                        id, semester, kind.name(), "Період " + kind.name(),
                        kind == CalendarPeriodKind.STUDY_MODE ? "ONLINE" : null);
            }

            migrate(dataSource, "latest");

            Map<UUID, UUID> kindIds = new HashMap<>();
            jdbc.queryForList("SELECT id, kind_id FROM calendar_period")
                    .forEach(row -> kindIds.put((UUID) row.get("id"), (UUID) row.get("kind_id")));
            assertThat(kindIds).hasSize(CalendarPeriodKind.values().length);
            legacy.forEach((period, kind) -> assertThat(kindIds.get(period)).as(kind.name())
                    .isEqualTo(kind.builtinKindId()));
            assertThat(jdbc.queryForList("""
                    SELECT p.kind || '→' || k.code FROM calendar_period p JOIN period_kind k ON k.id = p.kind_id
                    ORDER BY k.position""", String.class))
                    .containsExactly("HOLIDAY→К", "NO_CLASSES→БЗ", "PRACTICE→НП", "EXAM_SESSION→І", "STUDY_MODE→ФН");
            assertThat(jdbc.queryForList("SELECT DISTINCT source FROM calendar_period", String.class))
                    .containsExactly("MANUAL");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint
                    WHERE conname IN ('ck_calendar_period_kind', 'ck_calendar_period_study_mode_required')""",
                    Integer.class)).as("checks keyed on the legacy column are gone").isZero();
        }
    }

    private static void migrate(DriverManagerDataSource dataSource, String target) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).target(target)
                .load().migrate();
    }
}
