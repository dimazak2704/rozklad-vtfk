package ua.vtc.rozklad.core.timetable.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellTimes;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonStatus;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;

/** LessonView kinds of CONTRACTS.md §1 with the original taken from the change row (order №35 examples). */
class LessonViewAssemblerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final GroupRef GROUP = new GroupRef(UUID.randomUUID(), "4КІ1", 4);
    private static final DisciplineRef STP = new DisciplineRef(UUID.randomUUID(), "СТП",
            "Сучасні технології проєктування", null);
    private static final DisciplineRef NDKS = new DisciplineRef(UUID.randomUUID(), "НДЕКСМ",
            "Надійність, діагностика та експлуатація компʼютерних систем і мереж", "#3366AA");
    private static final TeacherRef KRENTSIN = new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін");
    private static final TeacherRef NEPYIVODA = new TeacherRef(UUID.randomUUID(), "Непийвода М.В.", "Непийвода");
    private static final RoomRef ROOM_6 = new RoomRef(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB);
    private static final RoomRef ROOM_301 = new RoomRef(UUID.randomUUID(), "301", RoomType.COMPUTER_LAB);

    private final BellTimes bells = bells();

    @Test
    void lessonOfTheBaseTimetableIsRegularWithBellTimes() {
        LessonView view = LessonViewAssembler.view(row(2, LessonStatus.SCHEDULED, LessonOrigin.TEMPLATE, STP,
                KRENTSIN, ROOM_301, null), bells);

        assertThat(view.kind()).isEqualTo(LessonViewKind.REGULAR);
        assertThat(view.startsAt()).isEqualTo(LocalTime.of(9, 40));
        assertThat(view.endsAt()).isEqualTo(LocalTime.of(10, 40));
        assertThat(view.original()).isNull();
        assertThat(view.reasonText()).isNull();
        assertThat(view.changeId()).isNull();
    }

    @Test
    void mutualSubstitutionShowsTheOriginalFromTheChange() {
        // №35: 4КІ1 | 2 | СТП Кренцін | Зв.зам. | НДКС Непийвода | 6
        LessonRow.Change change = change("REPLACE_DISCIPLINE", "MUTUAL", null, 2, 2, STP, KRENTSIN, ROOM_301, 2);
        LessonView view = LessonViewAssembler.view(row(2, LessonStatus.SCHEDULED, LessonOrigin.CHANGE, NDKS,
                NEPYIVODA, ROOM_6, change), bells);

        assertThat(view.kind()).isEqualTo(LessonViewKind.SUBSTITUTED);
        assertThat(view.reasonText()).isEqualTo("Зв.зам.");
        assertThat(view.original()).isEqualTo(new LessonView.Original(STP, KRENTSIN, ROOM_301, null));
        assertThat(view.changeId()).isEqualTo(change.id());
    }

    @Test
    void replacedOriginalIsHiddenWhenItsReplacementIsInTheSameView() {
        LessonRow.Change change = change("REPLACE_TEACHER", "ABSENT", null, 2, 2, STP, KRENTSIN, ROOM_6, 2);
        LessonRow cancelled = row(2, LessonStatus.CANCELLED, LessonOrigin.TEMPLATE, STP, KRENTSIN, ROOM_6, change);
        LessonRow replacement = row(2, LessonStatus.SCHEDULED, LessonOrigin.CHANGE, STP, NEPYIVODA, ROOM_6, change);

        List<LessonView> group = LessonViewAssembler.assemble(List.of(cancelled, replacement), bells, true);
        List<LessonView> absentTeacher = LessonViewAssembler.assemble(List.of(cancelled), bells, true);

        assertThat(group).singleElement().satisfies(view -> {
            assertThat(view.kind()).isEqualTo(LessonViewKind.SUBSTITUTED);
            assertThat(view.reasonText()).isEqualTo("Відп.");
            assertThat(view.original().teacher()).isEqualTo(KRENTSIN);
        });
        assertThat(absentTeacher).singleElement().satisfies(view -> {
            assertThat(view.kind()).isEqualTo(LessonViewKind.CANCELLED);
            assertThat(view.original()).isNull();
        });
    }

    @Test
    void cancelledPairKeepsItsReason() {
        LessonRow.Change change = change("CANCEL", "OTHER", "Хвороба групи", 1, 1, STP, KRENTSIN, null, 1);

        LessonView view = LessonViewAssembler.view(row(1, LessonStatus.CANCELLED, LessonOrigin.TEMPLATE, STP,
                KRENTSIN, null, change), bells);

        assertThat(view.kind()).isEqualTo(LessonViewKind.CANCELLED);
        assertThat(view.reasonText()).isEqualTo("Хвороба групи");
    }

    @Test
    void swapIsShownAsMovedFromTheOriginalPair() {
        LessonRow.Change change = change("SWAP", "ABSENT", null, 2, 4, null, null, null, 4);

        LessonView view = LessonViewAssembler.view(row(2, LessonStatus.SCHEDULED, LessonOrigin.CHANGE, NDKS,
                NEPYIVODA, ROOM_6, change), bells);

        assertThat(view.kind()).isEqualTo(LessonViewKind.MOVED);
        assertThat(view.original()).isEqualTo(new LessonView.Original(null, null, null, 4));
    }

    @Test
    void roomChangeAdditionalPairAndBlock() {
        LessonView roomChanged = LessonViewAssembler.view(row(4, LessonStatus.SCHEDULED, LessonOrigin.TEMPLATE, STP,
                KRENTSIN, ROOM_6, change("ROOM_CHANGE", null, null, 4, 4, null, null, ROOM_301, null)), bells);
        LessonView additional = LessonViewAssembler.view(row(3, LessonStatus.SCHEDULED, LessonOrigin.ADDITIONAL, STP,
                KRENTSIN, ROOM_6, change("ADDITIONAL", "OTHER", "Відпрацювання", 3, 3, null, null, null, null)),
                bells);
        LessonView block = LessonViewAssembler.view(row(1, LessonStatus.SCHEDULED, LessonOrigin.BLOCK, STP,
                KRENTSIN, ROOM_6, change("BLOCK", null, null, 1, 4, null, null, null, null)), bells);

        assertThat(roomChanged.kind()).isEqualTo(LessonViewKind.ROOM_CHANGED);
        assertThat(roomChanged.original()).isEqualTo(new LessonView.Original(null, null, ROOM_301, null));
        assertThat(additional.kind()).isEqualTo(LessonViewKind.ADDITIONAL);
        // як у розпорядженні: додаткова пара друкується без причини
        assertThat(additional.reasonText()).isNull();
        assertThat(additional.original()).isNull();
        assertThat(block.kind()).isEqualTo(LessonViewKind.BLOCK);
        assertThat(block.startsAt()).isEqualTo(LocalTime.of(8, 30));
        assertThat(block.endsAt()).isEqualTo(LocalTime.of(13, 10));
    }

    @Test
    void withoutBellProfileTheLessonHasNoTimes() {
        LessonView view = LessonViewAssembler.view(row(1, LessonStatus.SCHEDULED, LessonOrigin.TEMPLATE, STP, null,
                null, null), BellTimes.none());

        assertThat(view.startsAt()).isNull();
        assertThat(view.endsAt()).isNull();
        assertThat(view.teacher()).isNull();
    }

    private static LessonRow row(int pair, LessonStatus status, LessonOrigin origin, DisciplineRef discipline,
                                 TeacherRef teacher, RoomRef room, LessonRow.Change change) {
        return new LessonRow(UUID.randomUUID(), MONDAY, pair, 0, status, origin, false,
                Instant.parse("2026-10-16T12:00:00Z"), GROUP, discipline, teacher, room, change);
    }

    private static LessonRow.Change change(String kind, String reasonCode, String reasonText, int pairFrom,
                                           int pairTo, DisciplineRef discipline, TeacherRef teacher, RoomRef room,
                                           Integer originalPair) {
        return new LessonRow.Change(UUID.randomUUID(), kind, reasonCode, reasonText, pairFrom, pairTo, discipline,
                teacher, room, originalPair);
    }

    private static BellTimes bells() {
        BellProfile profile = new BellProfile("2 зміни, 60 хв");
        profile.addSlot(1, 1, LocalTime.of(8, 30), LocalTime.of(9, 30));
        profile.addSlot(1, 2, LocalTime.of(9, 40), LocalTime.of(10, 40));
        profile.addSlot(1, 3, LocalTime.of(11, 0), LocalTime.of(12, 0));
        profile.addSlot(1, 4, LocalTime.of(12, 10), LocalTime.of(13, 10));
        return BellTimes.of(profile);
    }
}
