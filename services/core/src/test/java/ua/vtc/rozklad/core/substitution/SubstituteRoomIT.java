package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.substitution.internal.options.LessonOptions;
import ua.vtc.rozklad.core.substitution.internal.options.OptionDto;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.TimetableVersion;

/**
 * Round 7: the room belongs to the teacher. A cover of another discipline (REPLACE_DISCIPLINE)
 * without a room goes to the substitute's usual room for that discipline in the group (the
 * published weekly timetable) when it is free; otherwise the lesson keeps its room. The options
 * suggest the same room ({@code suggestedRoom}), so what the dispatcher sees is what the change gets.
 */
@CoreIntegrationTest
class SubstituteRoomIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private ChangeCommands commands;

    @Autowired
    private LessonOptions lessonOptions;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup ki41;
    private StudentGroup ki42;
    private Teacher nepyivoda;
    private Teacher hanza;
    private Discipline km;
    private Discipline opcz;
    private Room room6;
    private Room room304;
    private Lesson stp;
    private LoadItem kmLoad;

    @BeforeEach
    void schedule() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        ki41 = fixture.group("4КІ1", 4);
        ki42 = fixture.group("4КІ2", 4);
        Teacher krentsin = fixture.teacher("Кренцін М.Д.", "Кренцін");
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода");
        hanza = fixture.teacher("Ганза І.П.", "Ганза");
        Discipline stpDiscipline = fixture.discipline("СТП", "Сучасні технології проєктування");
        km = fixture.discipline("КМ", "Комп'ютерні мережі");
        opcz = fixture.discipline("ОПЦЗ", "Охорона праці та цивільний захист");
        room6 = fixture.room("6");
        room304 = fixture.room("304");
        Room room12 = fixture.room("12");
        // тижневий розклад: Непийвода веде КМ у 4КІ1 в 304, а свою КМ у 4КІ2 — в 12
        TimetableVersion published = fixture.publishedVersion(semester);
        fixture.templateLesson(published, 2, 1, ki41, km, nepyivoda, room304);
        fixture.templateLesson(published, 3, 1, ki41, km, nepyivoda, room304);
        fixture.templateLesson(published, 2, 2, ki42, km, nepyivoda, room12);
        stp = fixture.lesson(semester, MONDAY, 2, ki41, 0, stpDiscipline, krentsin, room6);
        // пул групи 4КІ1: інші її дисципліни зі своїми викладачами
        fixture.load(semester, ki41, stpDiscipline, 40, krentsin);
        kmLoad = fixture.load(semester, ki41, km, 40, nepyivoda);
        fixture.load(semester, ki41, opcz, 40, hanza);
        fixture.setting("substitution.approvalMode", "AUTO");
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void coverGoesToTheSubstitutesRoomWhenItIsFree() {
        ChangeDto change = cover(km, nepyivoda, null);

        assertThat(change.replacement().room().id()).isEqualTo(room304.getId());
        assertThat(change.original().room().id()).isEqualTo(room6.getId());
    }

    @Test
    void busyRoomOfTheSubstituteKeepsTheLessonsRoom() {
        fixture.lesson(semester, MONDAY, 2, ki42, 0, opcz, hanza, room304);

        ChangeDto change = cover(km, nepyivoda, null);

        assertThat(change.replacement().room()).as("no new room: the lesson keeps 6").isNull();
    }

    @Test
    void roomGivenByTheDispatcherWins() {
        ChangeDto change = cover(km, nepyivoda, room6.getId());

        assertThat(change.replacement().room().id()).isEqualTo(room6.getId());
    }

    @Test
    void substituteWithoutLessonsInThePublishedTimetableKeepsTheLessonsRoom() {
        ChangeDto change = cover(opcz, hanza, null);

        assertThat(change.replacement().room()).isNull();
    }

    @Test
    void optionsSuggestTheRoomTheCoverGets() {
        assertThat(suggestedRoom(nepyivoda)).isEqualTo("304");
        assertThat(cover(km, nepyivoda, null).replacement().room().name()).isEqualTo("304");
    }

    @Test
    void optionsSuggestTheLessonsRoomWhenTheSubstitutesRoomIsBusy() {
        fixture.lesson(semester, MONDAY, 2, ki42, 0, opcz, hanza, room304);

        assertThat(suggestedRoom(nepyivoda)).isEqualTo("6");
        assertThat(cover(km, nepyivoda, null).replacement().room()).isNull();
    }

    @Test
    void roomThatDoesNotSuitTheLessonIsNotSuggested() {
        // аудиторія 304 замала для групи — create її не візьме, тож і варіант її не пропонує
        jdbc.update("UPDATE room SET capacity = 10 WHERE id = ?", room304.getId());
        jdbc.update("UPDATE student_group SET size = 25 WHERE id = ?", ki41.getId());
        assertThat(suggestedRoom(nepyivoda)).isEqualTo("6");
        assertThat(cover(km, nepyivoda, null).replacement().room()).isNull();
    }

    @Test
    void roomOfAnotherTypeThanTheDisciplineNeedsIsNotSuggested() {
        jdbc.update("UPDATE load_item SET required_room_type = ? WHERE id = ?", RoomType.LAB.name(), kmLoad.getId());

        assertThat(suggestedRoom(nepyivoda)).isEqualTo("6");
    }

    @Test
    void roomTakenOutOfUseIsNotSuggested() {
        jdbc.update("UPDATE room SET active = false WHERE id = ?", room304.getId());

        assertThat(suggestedRoom(nepyivoda)).isEqualTo("6");
        assertThat(cover(km, nepyivoda, null).replacement().room()).isNull();
    }

    private String suggestedRoom(Teacher teacher) {
        OptionDto option = lessonOptions.of(stp.getId()).options().stream()
                .filter(candidate -> candidate.kind() == ChangeKind.REPLACE_DISCIPLINE
                        && candidate.teacher().id().equals(teacher.getId()))
                .findFirst()
                .orElseThrow();
        return option.suggestedRoom().name();
    }

    private ChangeDto cover(Discipline discipline, Teacher teacher, UUID roomId) {
        return commands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, MONDAY, 2, null, ki41.getId(),
                null, stp.getId(), discipline.getId(), teacher.getId(), roomId, null, ReasonCode.MUTUAL,
                null, null), "admin");
    }
}
