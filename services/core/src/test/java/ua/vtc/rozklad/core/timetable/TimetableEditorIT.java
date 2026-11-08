package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;
import ua.vtc.rozklad.core.timetable.internal.importing.UnresolvedImportReferencesException;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;
import ua.vtc.rozklad.core.timetable.internal.version.LessonReferenceValidator;
import ua.vtc.rozklad.core.timetable.internal.version.MoveCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonMutationResponse;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionDto;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionService;
import ua.vtc.rozklad.core.timetable.internal.version.VersionDiffDto;

/** The timetable editor on a real database: draft edits, DnD rules, references, versions, diff, import errors. */
@CoreIntegrationTest
class TimetableEditorIT {

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private TimetableImportService imports;

    @Autowired
    private TimetableVersionService versions;

    @Autowired
    private TemplateLessonService lessons;

    @Autowired
    private PublicationService publication;

    private TimetableItData data;
    private UUID draftId;

    @BeforeEach
    void draft() {
        data = new TimetableItData(LocalDate.now(clock)).create(transaction, em);
        draftId = imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "Чернетка", List.of(
                new ImportTimetableCommand.Lesson(1, 1, Parity.BOTH, "4КІ1", 0, "СТП", "Кренцін М.Д.", "6", false),
                new ImportTimetableCommand.Lesson(1, 3, Parity.BOTH, "4КІ1", 0, "КМ", "Непийвода М.В.", "304", false),
                new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4КІ2", 0, "КМ", "Непийвода М.В.", "304",
                        false)))).versionId();
    }

    @AfterEach
    void cleanUp() {
        TimetableItData.cleanUp(jdbc);
    }

    @Test
    void newLessonAnswersWithTheConflictsOfItsSlot() {
        TemplateLessonMutationResponse created = lessons.create(draftId, lesson(data.group4ki2.getId(), 1, 1,
                data.krentsin.getId(), data.room304.getId()));

        assertThat(created.lesson().group().code()).isEqualTo("4КІ2");
        assertThat(created.lesson().teacher().shortName()).isEqualTo("Кренцін М.Д.");
        assertThat(created.conflicts()).extracting(ConflictDto::kind).contains(ConflictKind.TEACHER);
        assertThat(created.conflicts()).allSatisfy(conflict -> assertThat(conflict.weekday()).isEqualTo(1));
        assertThat(versions.conflicts(draftId).hard()).extracting(ConflictDto::kind)
                .containsExactly(ConflictKind.TEACHER);
    }

    @Test
    void dragAndDropPreviewSavesNothingAndAHardConflictIsRefused() {
        UUID stp = lessonId(data.group4ki1.getId(), 1);

        TemplateLessonMutationResponse preview = lessons.previewMove(draftId, stp,
                new MoveCommand(1, 2, Parity.BOTH, null));
        assertThat(preview.lesson().pair()).isEqualTo(2);
        assertThat(pairOf(stp)).isEqualTo(1);

        UUID km = lessonId(data.group4ki1.getId(), 3);
        assertThatThrownBy(() -> lessons.move(draftId, km, new MoveCommand(1, 2, Parity.BOTH, null), null))
                .isInstanceOfSatisfying(ConflictException.class, refused -> assertThat(refused.getConflicts())
                        .extracting(ConflictDto::kind)
                        .containsExactlyInAnyOrder(ConflictKind.TEACHER, ConflictKind.ROOM));
        assertThat(pairOf(km)).isEqualTo(3);

        TemplateLessonMutationResponse moved = lessons.move(draftId, km, new MoveCommand(2, 1, Parity.NUMERATOR,
                data.lab6.getId()), null);
        assertThat(moved.lesson().parity()).isEqualTo(Parity.NUMERATOR);
        assertThat(moved.lesson().room().name()).isEqualTo("6");
    }

    @Test
    void lessonIsEditedAndMovedInsideItsOwnSlot() {
        UUID km = lessonId(data.group4ki1.getId(), 3);

        // заміна аудиторії / онлайн без переміщення: той самий слот до і після (раніше — 500 з Set.of)
        TemplateLessonCommand online = new TemplateLessonCommand(1, 3, Parity.BOTH, data.group4ki1.getId(), 0,
                data.km.getId(), data.nepyivoda.getId(), null, LessonKind.MIXED, null, false, true, null);
        long loaded = lessons.list(draftId, data.group4ki1.getId(), null, null).stream()
                .filter(lesson -> lesson.id().equals(km)).findFirst().orElseThrow().version();
        TemplateLessonMutationResponse edited = lessons.update(draftId, km, online, loaded);
        assertThat(edited.lesson().room()).isNull();
        assertThat(edited.lesson().online()).isTrue();
        assertThat(edited.lesson().version()).as("the answer carries the new version").isEqualTo(loaded + 1);
        // друга правка з тієї самої (вже застарілої) версії не перезаписує першу мовчки
        assertThatThrownBy(() -> lessons.update(draftId, km, online, loaded))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThatThrownBy(() -> lessons.move(draftId, km, new MoveCommand(1, 3, Parity.NUMERATOR, null), loaded))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        TemplateLessonMutationResponse preview = lessons.previewMove(draftId, km,
                new MoveCommand(1, 3, Parity.NUMERATOR, null));
        assertThat(preview.lesson().parity()).isEqualTo(Parity.NUMERATOR);
        TemplateLessonMutationResponse moved = lessons.move(draftId, km, new MoveCommand(1, 3, Parity.DENOMINATOR,
                data.lab6.getId()), edited.lesson().version());
        assertThat(moved.lesson().parity()).isEqualTo(Parity.DENOMINATOR);
        assertThat(moved.lesson().room().name()).isEqualTo("6");
    }

    @Test
    void referencesAreCheckedBeforeSaving() {
        UUID loadItemOf4ki2 = transaction.execute(status -> {
            LoadItem item = new LoadItem(data.semester.getId(), data.group4ki2.getId(), data.stp.getId(),
                    BigDecimal.valueOf(2), 44);
            em.persist(item);
            return item.getId();
        });

        assertThatThrownBy(() -> lessons.create(draftId, lesson(UUID.randomUUID(), 2, 1, null, null)))
                .isInstanceOfSatisfying(BusinessRuleException.class, error -> assertThat(error.getCode())
                        .isEqualTo(LessonReferenceValidator.REFERENCE_NOT_FOUND));
        TemplateLessonCommand wrongLoad = new TemplateLessonCommand(2, 1, Parity.BOTH, data.group4ki1.getId(), 0,
                data.stp.getId(), null, null, LessonKind.LECTURE, loadItemOf4ki2, false, false, null);
        assertThatThrownBy(() -> lessons.create(draftId, wrongLoad))
                .isInstanceOfSatisfying(BusinessRuleException.class, error -> assertThat(error.getCode())
                        .isEqualTo(LessonReferenceValidator.LOAD_ITEM_MISMATCH));
    }

    @Test
    void publishedVersionIsReadOnlyAndCannotBeDeleted() {
        publication.publish(draftId, data.semester.getStartsOn(), "admin");

        assertThatThrownBy(() -> lessons.create(draftId, lesson(data.group4ki2.getId(), 2, 1, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("уже опублікована");
        assertThatThrownBy(() -> versions.delete(draftId)).isInstanceOf(ConflictException.class);
    }

    @Test
    void versionsAreListedCopiedComparedAndDraftsDeleted() {
        TimetableVersionDto copy = versions.create(data.semester.getId(), "Копія", draftId);
        UUID km4ki1 = transaction.execute(status -> em.createQuery("""
                        select t.id from TemplateLesson t where t.versionId = :v and t.groupId = :g and t.pairNumber = 3
                        """, UUID.class)
                .setParameter("v", copy.id()).setParameter("g", data.group4ki1.getId()).getSingleResult());
        lessons.move(copy.id(), km4ki1, new MoveCommand(2, 1, Parity.BOTH, null), null);
        lessons.create(copy.id(), lesson(data.group4ki2.getId(), 3, 2, null, null));

        assertThat(versions.list(data.semester.getId())).extracting(TimetableVersionDto::name,
                        TimetableVersionDto::lessonCount)
                .containsExactly(tuple("Копія", 4),
                        tuple("Чернетка", 3));
        VersionDiffDto diff = versions.diff(draftId, copy.id());
        assertThat(diff.moved()).singleElement().satisfies(moved -> {
            assertThat(moved.from().weekday()).isEqualTo(1);
            assertThat(moved.to().weekday()).isEqualTo(2);
        });
        assertThat(diff.added()).singleElement().satisfies(added -> assertThat(added.group().code()).isEqualTo("4КІ2"));
        assertThat(diff.removed()).isEmpty();
        assertThat(versions.metrics(draftId).groupWindows()).as("4КІ1: window at pair 2, both weeks").isEqualTo(2);

        versions.delete(copy.id());
        assertThat(versions.list(data.semester.getId())).extracting(TimetableVersionDto::id).containsExactly(draftId);
    }

    @Test
    void unknownNamesAreReportedAndNothingIsSaved() {
        assertThatThrownBy(() -> imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "Помилка",
                List.of(new ImportTimetableCommand.Lesson(2, 1, Parity.BOTH, "4КІ9", 0, "СТП", "Іваненко І.І.", "6",
                                false),
                        new ImportTimetableCommand.Lesson(2, 2, Parity.BOTH, "4КІ9", 0, "СТП", null, null, false)))))
                .isInstanceOfSatisfying(UnresolvedImportReferencesException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    @SuppressWarnings("unchecked")
                    List<FieldErrorDto> errors = (List<FieldErrorDto>) error.getBody().getProperties().get("errors");
                    assertThat(errors).containsExactly(
                            new FieldErrorDto("lessons[0].groupCode", "Групу «4КІ9» не знайдено в каталозі (рядків: 2)"),
                            new FieldErrorDto("lessons[0].teacherShortName",
                                    "Викладача «Іваненко І.І.» не знайдено в каталозі"));
                });
        assertThat(versions.list(data.semester.getId())).hasSize(1);
    }

    private TemplateLessonCommand lesson(UUID groupId, int weekday, int pair, UUID teacherId, UUID roomId) {
        return new TemplateLessonCommand(weekday, pair, Parity.BOTH, groupId, 0, data.ndeksm.getId(), teacherId, roomId,
                LessonKind.LAB, null, false, false, null);
    }

    private UUID lessonId(UUID groupId, int pair) {
        return transaction.execute(status -> em.createQuery("""
                        select t.id from TemplateLesson t where t.versionId = :v and t.groupId = :g and t.pairNumber = :p
                        """, UUID.class)
                .setParameter("v", draftId).setParameter("g", groupId).setParameter("p", pair).getSingleResult());
    }

    private int pairOf(UUID lessonId) {
        Integer pair = jdbc.queryForObject("SELECT pair_number FROM template_lesson WHERE id = ?", Integer.class,
                lessonId);
        return pair == null ? 0 : pair;
    }
}
