package ua.vtc.rozklad.core.calendar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.PeriodKindRepository;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.SpecialtyRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;

/**
 * The demo seed (profile {@code demo}) against the real schema: the SQL of {@code db/demo} and the
 * group-scoped periods created once the import brings the groups. Runs in one transaction that is
 * rolled back, so the shared test database stays clean.
 */
@CoreIntegrationTest
class DemoSeedIT {

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SemesterRepository semesters;

    @Autowired
    private CalendarPeriodRepository periods;

    @Autowired
    private SpecialtyRepository specialties;

    @Autowired
    private StudentGroupRepository groups;

    @Autowired
    private CatalogQueries catalog;

    @Autowired
    private PeriodKindRepository kinds;

    @Test
    void demoSqlSeedsCalendarAndStructureAndGroupPeriodsFollowTheImport() {
        transaction.executeWithoutResult(status -> {
            jdbc.execute(demoSql());

            List<Map<String, Object>> seeded = jdbc.queryForList(
                    "SELECT name, active, first_week_parity FROM semester ORDER BY starts_on");
            assertThat(seeded).extracting(row -> row.get("name"), row -> row.get("active"))
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("I семестр 2026/2027", true),
                            org.assertj.core.groups.Tuple.tuple("II семестр 2026/2027", false));
            assertThat(count("department")).isEqualTo(5);
            assertThat(count("specialty")).isEqualTo(11);
            assertThat(count("cycle_commission")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT code FROM specialty WHERE letters = 'КІ'", String.class))
                    .isEqualTo("123");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_period WHERE scope = 'COURSES'", Integer.class))
                    .isEqualTo(1);
            // старий SQL пише лише kind: тригер сумісності V1_203 дає йому вбудований вид
            assertThat(jdbc.queryForObject("""
                    SELECT k.code FROM calendar_period p JOIN period_kind k ON k.id = p.kind_id
                    WHERE p.scope = 'COURSES'""", String.class)).isEqualTo("ФН");

            DemoGroupPeriods demo = new DemoGroupPeriods(semesters, periods, catalog, kinds);
            demo.ensurePeriods();
            assertThat(count("calendar_period")).as("груп ще немає").isEqualTo(1);

            Specialty ki = specialties.findByLetters("КІ").orElseThrow();
            UUID group2ki3 = groups.save(new StudentGroup("2КІ3", 2, ki.getId())).getId();
            UUID group4ki1 = groups.save(new StudentGroup("4КІ1", 4, ki.getId())).getId();
            demo.ensurePeriods();
            demo.ensurePeriods();
            UUID group4ki2 = groups.save(new StudentGroup("4КІ2", 4, ki.getId())).getId();
            demo.ensurePeriods();
            periods.flush();

            List<CalendarPeriod> byGroups = periods.findAll().stream()
                    .filter(period -> !period.getGroupIds().isEmpty()).toList();
            assertThat(byGroups).extracting(CalendarPeriod::getTitle, CalendarPeriod::getGroupIds)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("Електрорадіомонтажна практика 2КІ3", Set.of(group2ki3)),
                            org.assertj.core.groups.Tuple.tuple("4КІ навчаються дистанційно", Set.of(group4ki1, group4ki2)));
            assertThat(byGroups).extracting(period -> period.getKind().getCode())
                    .containsExactlyInAnyOrder("НП", "ФН");
            status.setRollbackOnly();
        });
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String demoSql() {
        try {
            return new ClassPathResource("db/demo/V9_001__demo_calendar_and_structure.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
