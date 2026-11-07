package ua.vtc.rozklad.core.timetable;

import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherAlias;

/**
 * Catalog and calendar for timetable integration tests: 4КІ1/4КІ2, Кренцін/Непийвода, СТП/КМ/НДЕКСМ,
 * rooms 6 and 304, bells «2 зміни, 60 хв» and a 10-week active semester that starts on the Monday
 * two weeks before today (week 1 numerator), so «today» is always inside it.
 */
final class TimetableItData {

    static final String CLEAN_UP = """
            TRUNCATE lesson, change, template_lesson, generation_job, timetable_version, load_item_teacher, load_item,
                     teacher_preference, calendar_period_group, calendar_period, week_parity_override, semester,
                     academic_year, bell_slot, bell_profile, subgroup, student_group, specialty, teacher_alias,
                     teacher, discipline_alias, discipline, room, processed_event CASCADE
            """;

    final LocalDate weekOneMonday;
    Semester semester;
    StudentGroup group4ki1;
    StudentGroup group4ki2;
    Teacher krentsin;
    Teacher nepyivoda;
    Discipline stp;
    Discipline km;
    Discipline ndeksm;
    Room lab6;
    Room room304;

    TimetableItData(LocalDate today) {
        this.weekOneMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(2);
    }

    /** Monday of week {@code n} (1-based) of the semester. */
    LocalDate monday(int week) {
        return weekOneMonday.plusWeeks(week - 1L);
    }

    TimetableItData create(TransactionTemplate transaction, EntityManager em) {
        transaction.executeWithoutResult(status -> {
            Specialty specialty = persist(em, new Specialty("КІ", "Комп'ютерна інженерія"));
            group4ki1 = group(em, "4КІ1", specialty);
            group4ki2 = group(em, "4КІ2", specialty);
            krentsin = persist(em, new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін"));
            nepyivoda = persist(em, new Teacher("Непийвода", "Непийвода М.В.", "Непийвода"));
            persist(em, new TeacherAlias(nepyivoda.getId(), "Непийвода М. В.", "непийводамв"));
            stp = persist(em, new Discipline("Сучасні технології проєктування", "СТП"));
            km = persist(em, new Discipline("Комп'ютерні мережі", "КМ"));
            ndeksm = persist(em, new Discipline(
                    "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "НДЕКСМ"));
            lab6 = room(em, "6", RoomType.COMPUTER_LAB, 30);
            room304 = room(em, "304", RoomType.CLASSROOM, 32);
            BellProfile bells = new BellProfile("2 зміни, 60 хв");
            for (int weekday = 1; weekday <= 5; weekday++) {
                bells.addSlot(weekday, 1, LocalTime.of(8, 30), LocalTime.of(9, 30));
                bells.addSlot(weekday, 2, LocalTime.of(9, 40), LocalTime.of(10, 40));
                bells.addSlot(weekday, 3, LocalTime.of(11, 0), LocalTime.of(12, 0));
                bells.addSlot(weekday, 4, weekday == 5 ? LocalTime.of(12, 20) : LocalTime.of(12, 10),
                        weekday == 5 ? LocalTime.of(13, 20) : LocalTime.of(13, 10));
            }
            persist(em, bells);
            AcademicYear year = persist(em, new AcademicYear("2026/2027", weekOneMonday.minusWeeks(1),
                    weekOneMonday.plusYears(1)));
            semester = new Semester(year.getId(), 1, "I семестр 2026/2027", weekOneMonday.plusDays(1),
                    monday(10).plusDays(6));
            semester.setBellProfileId(bells.getId());
            semester.setActive(true);
            persist(em, semester);
        });
        return this;
    }

    private static StudentGroup group(EntityManager em, String code, Specialty specialty) {
        StudentGroup group = new StudentGroup(code, 4, specialty.getId());
        group.setSize(25);
        group.setShift(1);
        return persist(em, group);
    }

    private static Room room(EntityManager em, String name, RoomType type, int capacity) {
        Room room = new Room(name, type);
        room.setCapacity(capacity);
        return persist(em, room);
    }

    static <T> T persist(EntityManager em, T entity) {
        em.persist(entity);
        return entity;
    }

    static void cleanUp(JdbcTemplate jdbc) {
        jdbc.execute(CLEAN_UP);
    }
}
