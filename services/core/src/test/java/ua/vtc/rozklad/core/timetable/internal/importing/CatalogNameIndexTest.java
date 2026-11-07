package ua.vtc.rozklad.core.timetable.internal.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineAlias;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;
import ua.vtc.rozklad.core.timetable.internal.importing.CatalogNameIndex.Resolution;

/** Name matching of DOMAIN.md §10 with the real typos of the college Excel. */
class CatalogNameIndexTest {

    private final StudentGroup group4ki1 = new StudentGroup("4КІ1", 4, UUID.randomUUID());
    private final StudentGroup group1ki1 = new StudentGroup("1КІ1", 1, UUID.randomUUID());
    private final Teacher garbolinska = new Teacher("Гарболінська", "Гарболінська", "Гарболінська");
    private final Teacher polishchukL = new Teacher("Поліщук", "Поліщук Л.", "Поліщук Л.");
    private final Teacher polishchukM = new Teacher("Поліщук", "Поліщук М.", "Поліщук М.");
    private final Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
    private final Teacher ivanenko = new Teacher("Іваненко", "Іваненко І.І.", "Іваненко");
    private final Teacher ivanenkoTwin = new Teacher("Іваненко", "Іваненко І.О.", "Іваненко");
    private final Discipline ukrMova = new Discipline("Українська мова", "Укр.мова");
    private final Discipline ndeksm = new Discipline(
            "Надійність, діагностика та експлуатація компʼютерних систем і мереж", "НДЕКСМ");
    private final Room gym = new Room("с.з.", RoomType.GYM);
    private final Room room304 = new Room("304", RoomType.CLASSROOM);

    private final CatalogNameIndex index = CatalogNameIndex.build(
            List.of(group4ki1, group1ki1),
            List.of(garbolinska, polishchukL, polishchukM, krentsin, ivanenko, ivanenkoTwin),
            List.of(new TeacherAlias(garbolinska.getId(), "Граболінська", "граболінська"),
                    new TeacherAlias(ivanenkoTwin.getId(), "Іваненко І. І.", "іваненкоіі")),
            List.of(ukrMova, ndeksm),
            List.of(new DisciplineAlias(ndeksm.getId(), "НДКС", "ндкс")),
            List.of(gym, room304));

    @Test
    void groupCodesAreCanonicalized() {
        assertThat(index.group("4КІ1")).isEqualTo(new Resolution.Found(group4ki1.getId()));
        assertThat(index.group("4КИ1")).isEqualTo(new Resolution.Found(group4ki1.getId()));
        assertThat(index.group("4ki1")).isEqualTo(new Resolution.Found(group4ki1.getId()));
        assertThat(index.group("ІКІ1")).isEqualTo(new Resolution.Found(group1ki1.getId()));
        assertThat(index.group("4КІ9")).isInstanceOf(Resolution.Missing.class);
    }

    @Test
    void teachersAreFoundByShortNameAliasAndLookAlikeLetters() {
        assertThat(index.teacher("Граболінська")).isEqualTo(new Resolution.Found(garbolinska.getId()));
        assertThat(index.teacher("Кренцін M. Д.")).isEqualTo(new Resolution.Found(krentsin.getId()));
        assertThat(index.teacher("Поліщук Л.")).isEqualTo(new Resolution.Found(polishchukL.getId()));
        assertThat(index.teacher("Поліщук М.")).isEqualTo(new Resolution.Found(polishchukM.getId()));
        assertThat(index.teacher("Поліщук")).isInstanceOf(Resolution.Missing.class);
    }

    @Test
    void nameSharedByTwoTeachersIsAmbiguousAndNeverGuessed() {
        assertThat(index.teacher("Іваненко І.І.")).isEqualTo(new Resolution.Found(ivanenko.getId()));
        assertThat(index.teacher("Іваненко  І. І.")).isInstanceOf(Resolution.Ambiguous.class);
    }

    @Test
    void disciplinesAreFoundByShortNameAliasOrFullName() {
        assertThat(index.discipline("Укр.мова.")).isEqualTo(new Resolution.Found(ukrMova.getId()));
        assertThat(index.discipline("НДКС")).isEqualTo(new Resolution.Found(ndeksm.getId()));
        assertThat(index.discipline("Українська мова")).isEqualTo(new Resolution.Found(ukrMova.getId()));
        assertThat(index.discipline("Вс.")).isInstanceOf(Resolution.Missing.class);
    }

    @Test
    void roomsIgnoreCaseAndDots() {
        assertThat(index.room("С.З.")).isEqualTo(new Resolution.Found(gym.getId()));
        assertThat(index.room("304")).isEqualTo(new Resolution.Found(room304.getId()));
        assertThat(index.room(" ")).isInstanceOf(Resolution.Missing.class);
    }
}
