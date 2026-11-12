package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestClock;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionService;

/**
 * A republication effective today, made after the first pair began (Monday, 09:00 Kyiv): the pair
 * that began keeps its lesson of the old version, and a later refresh of the same group-day (a
 * cancelled change, a calendar edit) keeps it too (docs/modules/timetable.md).
 */
@CoreIntegrationTest
class RepublicationTodayIT {

    @Autowired
    private TestClock clock;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TimetableImportService imports;

    @Autowired
    private PublicationService publication;

    @Autowired
    private TimetableVersionService versions;

    @Autowired
    private TemplateLessonService templateLessons;

    @Autowired
    private ChangeCommands changeCommands;

    @Autowired
    private DatedLessonRefresh refresh;

    private TimetableItData data;
    private LocalDate today;

    @BeforeEach
    void mondayMorning() {
        today = LocalDate.now(TimeConfig.KYIV).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        at(LocalTime.of(7, 0));
        data = new TimetableItData(today).create(transaction, em);
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        TimetableItData.cleanUp(jdbc);
    }

    @Test
    void refreshAfterARepublicationEffectiveTodayKeepsThePairThatBegan() {
        UUID group = data.group4ki1.getId();
        UUID first = publishFirstVersion();
        Map<String, Object> morning = lessonAt(group, today, 1);
        UUID second = versionWithKmInLab6(first);
        publication.publish(second, today, "admin");
        assertThat(lessonAt(group, today, 1)).as("the publication keeps pair 1, which began at 08:30")
                .isEqualTo(morning);
        assertThat(lessonAt(group, today, 2).get("version_id")).isEqualTo(second);

        // скасована зміна оновлює весь день групи
        ChangeDto change = changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, today, 2, null,
                group, null, (UUID) lessonAt(group, today, 2).get("id"), null, data.nepyivoda.getId(), null, null,
                ReasonCode.ABSENT, null, null), "admin");
        at(LocalTime.of(9, 10));
        changeCommands.cancel(change.id(), "admin");

        assertThat(lessonAt(group, today, 1)).as("pair 1 stays from the old version after the refresh")
                .isEqualTo(morning);
        assertThat(lessonAt(group, today, 2).get("version_id")).isEqualTo(second);
    }

    @Test
    void refreshOfTodayKeepsThePairThatBeganAndFollowsTheNewVersionLater() {
        UUID group = data.group4ki1.getId();
        UUID first = publishFirstVersion();
        UUID second = versionWithKmInLab6(first);
        publication.publish(second, today, "admin");
        Map<String, Object> morning = lessonAt(group, today, 1);
        LocalDate nextMonday = today.plusWeeks(1);

        at(LocalTime.of(9, 10));
        transaction.executeWithoutResult(status -> refresh.refresh(List.of(today, nextMonday), List.of(group)));

        assertThat(lessonAt(group, today, 1)).isEqualTo(morning);
        assertThat(morning.get("version_id")).isEqualTo(first);
        assertThat(lessonAt(group, nextMonday, 1)).satisfies(lesson -> {
            assertThat(lesson.get("version_id")).isEqualTo(second);
            assertThat(lesson.get("room_id")).isEqualTo(data.lab6.getId());
        });
    }

    private UUID publishFirstVersion() {
        UUID first = imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "Розклад", List.of(
                new ImportTimetableCommand.Lesson(1, 1, Parity.BOTH, "4КІ1", 0, "КМ", null, "304", false),
                new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4КІ1", 0, "СТП", "Кренцін М.Д.", "6",
                        false)))).versionId();
        publication.publish(first, data.semester.getStartsOn(), "admin");
        // друга публікація о 09:00: 1-ша пара (08:30) уже почалася, 2-га (09:40) ще ні
        at(LocalTime.of(9, 0));
        return first;
    }

    /** A copy of the version where КМ (pair 1) moves from room 304 to room 6. */
    private UUID versionWithKmInLab6(UUID first) {
        UUID second = versions.create(data.semester.getId(), "КМ у лабораторії", first).id();
        TemplateLesson km = transaction.execute(status -> em.createQuery("""
                        select t from TemplateLesson t where t.versionId = :v and t.pairNumber = 1
                        """, TemplateLesson.class).setParameter("v", second).getSingleResult());
        templateLessons.update(second, km.getId(), new TemplateLessonCommand(1, 1, Parity.BOTH,
                data.group4ki1.getId(), 0, data.km.getId(), null, data.lab6.getId(), km.getLessonKind(), null,
                false, false, null), null);
        return second;
    }

    private void at(LocalTime time) {
        clock.stopAt(today.atTime(time).atZone(TimeConfig.KYIV).toInstant());
    }

    private Map<String, Object> lessonAt(UUID group, LocalDate date, int pair) {
        return jdbc.queryForMap("""
                SELECT id, version_id, template_lesson_id, room_id FROM lesson
                WHERE group_id = ? AND date = ? AND pair_number = ? AND status = 'SCHEDULED'
                """, group, date, pair);
    }
}
