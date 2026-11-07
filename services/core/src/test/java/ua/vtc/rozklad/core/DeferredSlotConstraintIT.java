package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.support.CoreJpaTest;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * Slot constraints of {@code lesson} are deferred: a transaction may pass through intermediate
 * states (swap two pairs), but a clash still fails the commit with a data integrity error.
 */
@CoreJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeferredSlotConstraintIT {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID semesterId;
    private UUID groupId;
    private UUID teacherId;
    private UUID disciplineId;
    private Room room;

    @BeforeEach
    void catalog() {
        transaction.executeWithoutResult(status -> {
            Specialty specialty = persist(new Specialty("КІ", "Комп'ютерна інженерія"));
            groupId = persist(new StudentGroup("4КІ1", 4, specialty.getId())).getId();
            teacherId = persist(new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін")).getId();
            disciplineId = persist(new Discipline("Сучасні технології проєктування", "СТП")).getId();
            room = persist(new Room("6", RoomType.COMPUTER_LAB));
            AcademicYear year = persist(new AcademicYear("2026/2027", LocalDate.of(2026, 9, 1),
                    LocalDate.of(2027, 6, 30)));
            semesterId = persist(new Semester(year.getId(), 1, "I семестр", LocalDate.of(2026, 9, 1),
                    LocalDate.of(2026, 12, 30))).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("TRUNCATE lesson, semester, academic_year, room, discipline, teacher, student_group, specialty "
                + "CASCADE");
    }

    @Test
    void swappingTwoPairsInOneTransactionCommits() {
        UUID[] ids = new UUID[2];
        transaction.executeWithoutResult(status -> {
            ids[0] = lessons.save(lesson(2)).getId();
            ids[1] = lessons.save(lesson(4)).getId();
        });

        transaction.executeWithoutResult(status -> {
            lessons.findById(ids[0]).orElseThrow().moveTo(DAY, 4);
            lessons.findById(ids[1]).orElseThrow().moveTo(DAY, 2);
        });

        assertThat(lessons.findById(ids[0]).orElseThrow().getPairNumber()).isEqualTo(4);
    }

    @Test
    void clashFailsTheCommitAsDataIntegrityViolation() {
        transaction.executeWithoutResult(status -> lessons.save(lesson(3)));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> lessons.save(lesson(3))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(lessons.count()).isEqualTo(1);
    }

    private Lesson lesson(int pair) {
        Lesson lesson = new Lesson(semesterId, DAY, pair, groupId, 0, disciplineId, LessonOrigin.TEMPLATE);
        lesson.setTeacherId(teacherId);
        lesson.setRoom(room.getId(), room.isExclusive());
        return lesson;
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }
}
