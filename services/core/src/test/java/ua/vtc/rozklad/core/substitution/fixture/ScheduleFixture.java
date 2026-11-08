package ua.vtc.rozklad.core.substitution.fixture;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.catalog.CycleCommission;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherDiscipline;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.support.ReferenceData;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.VersionSource;

/**
 * Test data of the semester of order №35 (DOMAIN §12): «I семестр 2026/2027», 01.09–30.12.2026,
 * first week NUMERATOR, bells «2 зміни, 60 хв». Every method commits its own transaction; call
 * {@link #cleanUp()} after each test (integration tests share one database).
 */
public final class ScheduleFixture {

    public static final LocalDate SEMESTER_START = LocalDate.of(2026, 9, 1);
    public static final LocalDate SEMESTER_END = LocalDate.of(2026, 12, 30);

    private static final LocalTime[][] MON_THU = {
            {LocalTime.of(8, 30), LocalTime.of(9, 30)}, {LocalTime.of(9, 40), LocalTime.of(10, 40)},
            {LocalTime.of(11, 0), LocalTime.of(12, 0)}, {LocalTime.of(12, 10), LocalTime.of(13, 10)},
            {LocalTime.of(13, 30), LocalTime.of(14, 30)}, {LocalTime.of(14, 40), LocalTime.of(15, 40)},
            {LocalTime.of(16, 0), LocalTime.of(17, 0)}, {LocalTime.of(17, 10), LocalTime.of(18, 10)}};
    private static final LocalTime[][] FRIDAY = {
            {LocalTime.of(8, 30), LocalTime.of(9, 30)}, {LocalTime.of(9, 40), LocalTime.of(10, 40)},
            {LocalTime.of(11, 0), LocalTime.of(12, 0)}, {LocalTime.of(12, 20), LocalTime.of(13, 20)},
            {LocalTime.of(13, 30), LocalTime.of(14, 30)}, {LocalTime.of(14, 50), LocalTime.of(15, 50)}};

    private final TransactionTemplate transaction;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public ScheduleFixture(TransactionTemplate transaction, EntityManager entityManager, JdbcTemplate jdbc) {
        this.transaction = transaction;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    public Semester semester() {
        return transaction.execute(status -> {
            BellProfile bells = new BellProfile("Тест: 2 зміни, 60 хв");
            for (int weekday = 1; weekday <= 5; weekday++) {
                LocalTime[][] slots = weekday == 5 ? FRIDAY : MON_THU;
                for (int pair = 1; pair <= slots.length; pair++) {
                    bells.addSlot(weekday, pair, slots[pair - 1][0], slots[pair - 1][1]);
                }
            }
            entityManager.persist(bells);
            AcademicYear year = new AcademicYear("2026/2027", SEMESTER_START, LocalDate.of(2027, 6, 30));
            entityManager.persist(year);
            Semester semester = new Semester(year.getId(), 1, "I семестр 2026/2027", SEMESTER_START, SEMESTER_END);
            semester.setBellProfileId(bells.getId());
            semester.setActive(true);
            entityManager.persist(semester);
            return semester;
        });
    }

    public StudentGroup group(String code, int course) {
        return transaction.execute(status -> {
            String letters = code.replaceAll("\\d", "");
            Specialty specialty = entityManager.createQuery("select s from Specialty s where s.letters = :letters",
                    Specialty.class).setParameter("letters", letters).getResultStream().findFirst().orElseGet(() -> {
                        Specialty created = new Specialty(letters, "Спеціальність " + letters);
                        entityManager.persist(created);
                        return created;
                    });
            StudentGroup group = new StudentGroup(code, course, specialty.getId());
            group.setShift(course == 2 ? 2 : 1);
            entityManager.persist(group);
            return group;
        });
    }

    public Teacher teacher(String shortName, String displayName) {
        return teacher(shortName, displayName, null, null);
    }

    public Teacher teacher(String shortName, String displayName, UUID account, UUID cycleCommissionId) {
        return persist(new Teacher(displayName.replaceAll("\\s.*", ""), shortName, displayName), teacher -> {
            teacher.setKeycloakUserId(account);
            teacher.setCycleCommissionId(cycleCommissionId);
        });
    }

    public UUID cycleCommission(String name) {
        return persist(new CycleCommission(name), commission -> {
        }).getId();
    }

    public Discipline discipline(String shortName, String name) {
        return persist(new Discipline(name, shortName), discipline -> {
        });
    }

    public Room room(String name) {
        return persist(new Room(name, RoomType.CLASSROOM), room -> {
        });
    }

    public Lesson lesson(Semester semester, LocalDate date, int pair, StudentGroup group, int subgroup,
                         Discipline discipline, Teacher teacher, Room room) {
        return persist(new Lesson(semester.getId(), date, pair, group.getId(), subgroup, discipline.getId(),
                LessonOrigin.TEMPLATE), lesson -> {
            lesson.setTeacherId(teacher == null ? null : teacher.getId());
            lesson.setRoom(room == null ? null : room.getId(), true);
        });
    }

    /** A lesson of a stream: one teacher and room for several groups in the slot (DOMAIN §1). */
    public Lesson streamLesson(Semester semester, LocalDate date, int pair, StudentGroup group, Discipline discipline,
                               Teacher teacher, Room room, UUID streamId) {
        return persist(new Lesson(semester.getId(), date, pair, group.getId(), 0, discipline.getId(),
                LessonOrigin.TEMPLATE), lesson -> {
            lesson.setTeacherId(teacher.getId());
            lesson.setRoom(room.getId(), true);
            lesson.setStreamId(streamId);
        });
    }

    /** A published weekly timetable of the semester (no dated lessons are derived from it). */
    public TimetableVersion publishedVersion(Semester semester) {
        return persist(new TimetableVersion(semester.getId(), "Опублікований розклад", VersionSource.MANUAL),
                version -> version.markPublished(SEMESTER_START, Instant.parse("2026-08-31T12:00:00Z"), "admin"));
    }

    /** A weekly lesson of both weeks in the version. */
    public TemplateLesson templateLesson(TimetableVersion version, int weekday, int pair, StudentGroup group,
                                         Discipline discipline, Teacher teacher, Room room) {
        return persist(new TemplateLesson(version.getId(), weekday, pair, Parity.BOTH,
                group.getId(), 0, discipline.getId()), lesson -> {
            lesson.setTeacherId(teacher.getId());
            lesson.setRoomId(room.getId());
        });
    }

    public void competence(Teacher teacher, Discipline discipline) {
        persist(new TeacherDiscipline(teacher.getId(), discipline.getId()), link -> {
        });
    }

    public LoadItem load(Semester semester, StudentGroup group, Discipline discipline, int plannedHours,
                         Teacher teacher) {
        return persist(new LoadItem(semester.getId(), group.getId(), discipline.getId(), BigDecimal.valueOf(2),
                plannedHours), item -> item.assignTeacher(0, teacher.getId()));
    }

    public void setting(String key, String value) {
        transaction.executeWithoutResult(status ->
                entityManager.merge(new Setting(key, JsonNodeFactory.instance.stringNode(value), "test")));
    }

    public void period(Semester semester, CalendarPeriodKind kind, String title, LocalDate from, LocalDate to,
                       String orderNote, Collection<UUID> groupIds) {
        persist(new CalendarPeriod(semester.getId(), kind, title, from, to), period -> {
            period.setOrderNote(orderNote);
            if (kind == CalendarPeriodKind.STUDY_MODE) {
                period.setStudyMode(StudyMode.ONLINE);
            }
            if (groupIds != null) {
                period.applyToGroups(groupIds);
            }
        });
    }

    /** Published main orders №1..№N for the study days from the start of the semester. */
    public void publishedOrders(Semester semester, List<LocalDate> dates) {
        transaction.executeWithoutResult(status -> {
            int number = 1;
            for (LocalDate date : dates) {
                OrderDocument order = OrderDocument.mainOrder(semester.getId(), date, "РОЗПОРЯДЖЕННЯ №" + number);
                order.markPublished(number++, Instant.parse("2026-08-31T12:00:00Z"), "admin");
                entityManager.persist(order);
            }
        });
    }

    public void cleanUp() {
        jdbc.execute("""
                TRUNCATE order_item, order_document, change_history, change, absence, teacher_preference, lesson,
                         load_item_teacher, load_item, template_lesson, timetable_version, generation_job,
                         calendar_period_group, calendar_period, week_parity_override, semester, academic_year,
                         teacher_discipline, teacher_alias, teacher,
                         discipline_alias, discipline, subgroup, student_group, specialty, department, room, building,
                         cycle_commission CASCADE
                """);
        ReferenceData.restore(jdbc);
    }

    private <T> T persist(T entity, Consumer<T> setUp) {
        return transaction.execute(status -> {
            setUp.accept(entity);
            entityManager.persist(entity);
            return entity;
        });
    }
}
