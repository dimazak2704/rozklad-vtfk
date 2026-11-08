package ua.vtc.rozklad.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.catalog.Department;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.Specialty;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItemContent;
import ua.vtc.rozklad.core.order.OrderSection;
import ua.vtc.rozklad.core.settings.Setting;
import ua.vtc.rozklad.core.settings.SettingRepository;
import ua.vtc.rozklad.core.shared.messaging.ProcessedEventRepository;
import ua.vtc.rozklad.core.shared.messaging.ProcessedEvents;
import ua.vtc.rozklad.core.substitution.Absence;
import ua.vtc.rozklad.core.substitution.AbsenceRepository;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.ReasonCode;
import ua.vtc.rozklad.core.support.CoreJpaTest;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/** Entity mappings against the Flyway schema: JSON, arrays, audit, cascades and DB constraints. */
@CoreJpaTest
class PersistenceMappingIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private EntityManager em;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private StudentGroupRepository groups;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private SemesterRepository semesters;

    @Autowired
    private BellProfileRepository bellProfiles;

    @Autowired
    private CalendarPeriodRepository periods;

    @Autowired
    private LoadItemRepository loadItems;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private GenerationJobRepository generationJobs;

    @Autowired
    private AbsenceRepository absences;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private OrderDocumentRepository orders;

    @Autowired
    private SettingRepository settings;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private Semester semester;
    private StudentGroup group;
    private Teacher teacher;
    private Discipline discipline;
    private Room room;

    @BeforeEach
    void catalog() {
        Department department = persist(new Department("Комп'ютерне відділення", "Комп'ютерне"));
        Specialty specialty = new Specialty("КІ", "Комп'ютерна інженерія");
        specialty.setDepartmentId(department.getId());
        specialty.setCode("123");
        persist(specialty);
        group = new StudentGroup("4КІ1", 4, specialty.getId());
        group.setNumber(1);
        group.setSize(25);
        persist(group);
        teacher = persist(new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін"));
        discipline = persist(new Discipline("Сучасні технології проєктування", "СТП"));
        room = persist(new Room("6", RoomType.COMPUTER_LAB));
        AcademicYear year = persist(new AcademicYear("2026/2027", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 6, 30)));
        semester = persist(new Semester(year.getId(), 1, "I семестр 2026/2027", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 30)));
        em.flush();
    }

    @Test
    void auditColumnsAndVersionAreMaintained() {
        em.clear();
        StudentGroup loaded = groups.findByCode("4КІ1").orElseThrow();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(loaded.getVersion()).isZero();
        assertThat(loaded.isNew()).isFalse();

        loaded.setShift(2);
        em.flush();

        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
    }

    @Test
    void newEntitiesArePersistedNotMerged() {
        Teacher created = new Teacher("Непийвода", "Непийвода М.В.", "Непийвода");

        Teacher saved = teachers.save(created);
        em.flush();

        assertThat(saved).isSameAs(created);
        assertThat(created.isNew()).isFalse();
    }

    @Test
    void jsonColumnsRoundTripThroughJackson3() {
        JsonNode params = jsonMapper.readTree("{\"timeLimitSeconds\":120,\"scope\":{\"courses\":[4]}}");
        GenerationJob job = new GenerationJob(semester.getId(), params, "admin");
        job.setProgress(jsonMapper.readTree("[{\"t\":2000,\"hard\":-3,\"soft\":-120}]"));
        generationJobs.save(job);
        settings.save(new Setting("substitution.approvalMode", jsonMapper.readTree("\"CONFIRM\""), "admin"));
        OrderDocument order = OrderDocument.mainOrder(semester.getId(), MONDAY,
                "По коледжу про деякі зміни в розкладі занять");
        order.setNotes(List.of("Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."));
        orders.save(order);
        em.flush();
        em.clear();

        GenerationJob loadedJob = generationJobs.findById(job.getId()).orElseThrow();
        assertThat(loadedJob.getParams().get("scope").get("courses").get(0).asInt()).isEqualTo(4);
        assertThat(loadedJob.getProgress().get(0).get("soft").asInt()).isEqualTo(-120);
        assertThat(settings.findById("substitution.approvalMode").orElseThrow().getValue().asString())
                .isEqualTo("CONFIRM");
        assertThat(orders.findById(order.getId()).orElseThrow().getNotes())
                .containsExactly("Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.");
    }

    @Test
    void replacingJsonValueIsDetectedByDirtyChecking() {
        GenerationJob job = generationJobs.save(new GenerationJob(semester.getId(), jsonMapper.readTree("{}"), null));
        em.flush();

        job.setProgress(jsonMapper.readTree("[{\"t\":4000,\"hard\":0,\"soft\":-40}]"));
        em.flush();
        em.clear();

        assertThat(generationJobs.findById(job.getId()).orElseThrow().getProgress().get(0).get("t").asInt())
                .isEqualTo(4000);
    }

    @Test
    void smallintArraysAreMapped() {
        Absence absence = new Absence(teacher.getId(), MONDAY, MONDAY, AbsenceReasonCategory.SICK_LEAVE,
                AbsenceSource.BOT, "krentsin");
        absence.setPairs(List.of(3, 1, 3));
        absences.save(absence);
        CalendarPeriod online = new CalendarPeriod(semester.getId(), CalendarPeriodKind.STUDY_MODE,
                "4 курс дистанційно", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31));
        online.setStudyMode(StudyMode.ONLINE);
        online.applyToCourses(List.of(4, 3));
        periods.save(online);
        em.flush();
        em.clear();

        Absence loaded = absences.findById(absence.getId()).orElseThrow();
        assertThat(loaded.getPairs()).containsExactly(1, 3);
        assertThat(loaded.covers(MONDAY, 3)).isTrue();
        assertThat(loaded.covers(MONDAY, 2)).isFalse();
        assertThat(periods.findById(online.getId()).orElseThrow().getCourses()).containsExactly(3, 4);
        assertThat(absences.findActiveOverlapping(MONDAY, MONDAY, teacher.getId())).hasSize(1);
    }

    @Test
    void childCollectionsCascadeAndOrphansAreRemoved() {
        // «2 зміни, 60 хв» уже є в довідкових даних (V1_201)
        BellProfile profile = new BellProfile("Тест: мапінг дзвінків");
        profile.addSlot(1, 1, LocalTime.of(8, 30), LocalTime.of(9, 30));
        profile.addSlot(1, 2, LocalTime.of(9, 40), LocalTime.of(10, 40));
        bellProfiles.save(profile);
        CalendarPeriod practice = new CalendarPeriod(semester.getId(), CalendarPeriodKind.PRACTICE,
                "Електрорадіомонтажна практика", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30));
        practice.applyToGroups(Set.of(group.getId()));
        periods.save(practice);
        LoadItem item = new LoadItem(semester.getId(), group.getId(), discipline.getId(), new BigDecimal("3.0"), 66);
        item.setSplitSubgroups(true);
        item.assignTeacher(1, teacher.getId());
        item.assignTeacher(2, teacher.getId());
        loadItems.save(item);
        em.flush();
        em.clear();

        BellProfile loadedProfile = bellProfiles.findWithSlotsById(profile.getId()).orElseThrow();
        assertThat(loadedProfile.getSlots()).extracting(slot -> slot.getStartsAt().toString())
                .containsExactly("08:30", "09:40");
        loadedProfile.clearSlots();
        loadedProfile.addSlot(5, 1, LocalTime.of(8, 30), LocalTime.of(9, 30));
        em.flush();
        em.clear();

        assertThat(bellProfiles.findWithSlotsById(profile.getId()).orElseThrow().getSlots()).hasSize(1);
        assertThat(periods.findById(practice.getId()).orElseThrow().getGroupIds()).containsExactly(group.getId());
        LoadItem loadedItem = loadItems.findAllBySemesterIdAndGroupId(semester.getId(), group.getId()).getFirst();
        assertThat(loadedItem.getHoursPerWeek()).isEqualByComparingTo("3.0");
        assertThat(loadedItem.teacherOf(2)).contains(teacher.getId());
    }

    @Test
    void teacherCannotHaveTwoScheduledLessonsInOneSlot() {
        lessons.save(lesson(group.getId(), 0));
        lessons.save(lesson(createGroup("4КІ2").getId(), 0));

        assertThatThrownBy(lessons::checkSlotConstraintsNow)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_lesson_teacher_slot");
    }

    @Test
    void cancelledLessonsDoNotBlockTheSlot() {
        Lesson original = lessons.save(lesson(group.getId(), 0));
        em.flush();

        // застосування заміни в одній транзакції: оригінал скасовано, нове заняття в тому ж слоті
        original.cancel();
        Lesson replacement = lesson(group.getId(), 0);
        replacement.setTeacherId(createTeacher("Непийвода М.В.").getId());
        lessons.save(replacement);
        lessons.checkSlotConstraintsNow();

        assertThat(lessons.lockGroupDay(group.getId(), MONDAY)).hasSize(2);
    }

    @Test
    void lessonsAreStudyDaysOnly() {
        Lesson saturday = new Lesson(semester.getId(), LocalDate.of(2026, 10, 24), 1, group.getId(), 0,
                discipline.getId(), LessonOrigin.TEMPLATE);

        assertThatThrownBy(() -> {
            lessons.save(saturday);
            em.flush();
        }).isInstanceOfSatisfying(ConstraintViolationException.class,
                violation -> assertThat(violation.getConstraintName()).isEqualTo("ck_lesson_study_day"));
    }

    @Test
    void changesAndOrdersLinkTogether() {
        Lesson lesson = lessons.save(lesson(group.getId(), 0));
        Change change = new Change(ChangeKind.REPLACE_TEACHER, MONDAY, 2, 2, group.getId(), 0, "admin");
        change.setOriginal(lesson.getId(), discipline.getId(), teacher.getId(), room.getId());
        change.setReason(ReasonCode.MUTUAL, ReasonCode.MUTUAL.orderText());
        change.setStatus(ChangeStatus.CONFIRMED);
        changes.save(change);
        OrderDocument order = OrderDocument.mainOrder(semester.getId(), MONDAY,
                "По коледжу про деякі зміни в розкладі занять");
        order.setDayLabels("ПОНЕДІЛОК", "ЗНАМЕННИК");
        order.addItem(OrderSection.CHANGES, new OrderItemContent("4КІ1", "2", OrderRowType.REPLACEMENT, "СТП",
                "Кренцін", "Зв.зам.", "НДКС", "Непийвода", "6", change.getId()));
        order.addItem(OrderSection.ROOM_CHANGES, new OrderItemContent("4КІ1", "3", OrderRowType.ROOM_CHANGE, null,
                null, null, null, null, "304/309", null));
        orders.save(order);
        em.flush();

        orders.lockNumbering(semester.getId());
        int next = orders.findMaxNumber(semester.getId()) + 1;
        order.markPublished(next, Instant.now(clock), "admin");
        change.setOrderId(order.getId());
        change.setStatus(ChangeStatus.APPLIED);
        em.flush();
        em.clear();

        OrderDocument loaded = orders.findWithItemsById(order.getId()).orElseThrow();
        assertThat(loaded.getNumber()).isEqualTo(1);
        assertThat(loaded.getItems(OrderSection.CHANGES)).singleElement()
                .satisfies(item -> assertThat(item.getChangeId()).isEqualTo(change.getId()));
        assertThat(loaded.getItems(OrderSection.ROOM_CHANGES)).extracting(item -> item.getRoomText())
                .containsExactly("304/309");
        assertThat(changes.findAllByOrderId(order.getId())).singleElement()
                .satisfies(c -> assertThat(c.getStatus()).isEqualTo(ChangeStatus.APPLIED));
    }

    @Test
    void secondMainOrderForTheSameDateIsRejected() {
        orders.save(OrderDocument.mainOrder(semester.getId(), MONDAY, "Розпорядження"));
        em.flush();

        assertThatThrownBy(() -> {
            orders.save(OrderDocument.mainOrder(semester.getId(), MONDAY, "Розпорядження"));
            em.flush();
        }).isInstanceOfSatisfying(ConstraintViolationException.class,
                violation -> assertThat(violation.getConstraintName()).isEqualTo("uq_order_document_main_per_date"));
    }

    @Test
    void processedEventsDeduplicateDeliveries() {
        ProcessedEvents processed = new ProcessedEvents(processedEventRepository, clock);
        UUID eventId = UUID.randomUUID();

        assertThat(processed.markProcessed(eventId)).isTrue();
        assertThat(processed.markProcessed(eventId)).isFalse();
    }

    private Lesson lesson(UUID groupId, int subgroup) {
        Lesson lesson = new Lesson(semester.getId(), MONDAY, 2, groupId, subgroup, discipline.getId(),
                LessonOrigin.TEMPLATE);
        lesson.setTeacherId(teacher.getId());
        lesson.setRoom(room.getId(), room.isExclusive());
        return lesson;
    }

    private Teacher createTeacher(String shortName) {
        return persist(new Teacher(shortName.split(" ")[0], shortName, shortName.split(" ")[0]));
    }

    private StudentGroup createGroup(String code) {
        StudentGroup another = new StudentGroup(code, 4, group.getSpecialtyId());
        return persist(another);
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }
}
