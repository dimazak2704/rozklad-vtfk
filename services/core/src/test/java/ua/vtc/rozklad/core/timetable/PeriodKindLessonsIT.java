package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;

/**
 * Period kinds end to end over published lessons (step 1 DoD): a kind the dispatcher adds with effect SUSPEND
 * removes its group's pairs and shows as «ХХ: …» with the order note; changing a kind's effect re-checks its
 * periods like a period edit (dry run first, 409 for live changes); the academic calendar import reports the
 * same 409 in its dry run before anything is written.
 */
@CoreIntegrationTest
@RecordApplicationEvents
class PeriodKindLessonsIT {

    private static final String KINDS = "/api/admin/period-kinds";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private TimetableImportService imports;

    @Autowired
    private PublicationService publication;

    @Autowired
    private ChangeCommands changeCommands;

    private MockMvc mvc;
    private TimetableItData data;

    @BeforeEach
    void publishedTimetable() {
        mvc = ApiTestSupport.mockMvc(context);
        data = new TimetableItData(LocalDate.now(clock)).create(transaction, em);
        publication.publish(imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "Розклад",
                List.of(new ImportTimetableCommand.Lesson(1, 1, Parity.BOTH, "4КІ1", 0, "КМ", null, "304", false),
                        new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4КІ1", 0, "СТП", "Кренцін М.Д.", "6",
                                false),
                        new ImportTimetableCommand.Lesson(1, 3, Parity.BOTH, "4КІ2", 0, "НДЕКСМ", "Непийвода М.В.",
                                "304", false))))
                .versionId(), data.semester.getStartsOn(), "admin");
    }

    @AfterEach
    void cleanUp() {
        TimetableItData.cleanUp(jdbc);
    }

    /** DoD: a new kind «ХХ» with effect SUSPEND, no code changed: pairs go, the block and the note appear. */
    @Test
    void newSuspendKindRemovesTheGroupsPairsAndShowsItsBlockAndNote() throws Exception {
        LocalDate monday = data.monday(4);
        UUID group = data.group4ki1.getId();
        assertThat(lessonsOf(group, monday)).isEqualTo(2);
        UUID kind = id(send(post(KINDS), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND"}""").andExpect(status().isCreated()).andReturn());
        String note = "4КІ1 – Новий блок з %s, ауд. 304".formatted(monday);

        send(post("/api/admin/calendar-periods"), """
                {"semesterId":"%s","kindId":"%s","title":"Новий блок","startsOn":"%s","endsOn":"%s",
                 "scope":"GROUPS","groupIds":["%s"],"roomId":"%s","orderNote":"%s"}"""
                .formatted(data.semester.getId(), kind, monday, monday.plusDays(4), group, data.room304.getId(), note))
                .andExpect(status().isCreated());

        assertThat(lessonsOf(group, monday)).as("the group is not at its pairs").isZero();
        assertThat(lessonsOf(data.group4ki2.getId(), monday)).as("other groups keep theirs").isOne();
        assertThat(lessonsOf(group, data.monday(5))).isEqualTo(2);
        mvc.perform(get("/api/public/schedule/group/{id}", group).param("from", monday.toString())
                        .param("to", monday.toString()).with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].studyDay").value(false))
                .andExpect(jsonPath("$.days[0].lessons", hasSize(0)))
                .andExpect(jsonPath("$.days[0].note").value("Новий блок"))
                .andExpect(jsonPath("$.days[0].practices[0].title").value("Новий блок"))
                .andExpect(jsonPath("$.days[0].practices[0].kind.code").value("ХХ"))
                .andExpect(jsonPath("$.days[0].practices[0].kind.name").value("Новий вид"))
                .andExpect(jsonPath("$.days[0].practices[0].room.name").value("304"));
        mvc.perform(get("/api/admin/orders/draft").param("date", monday.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", hasItem(note)));
    }

    /** An effect change is a calendar edit of every period of the kind: tried first, then applied. */
    @Test
    void effectChangeIsPreviewedThenAppliedToEveryPeriodOfTheKind() throws Exception {
        LocalDate monday = data.monday(4);
        UUID group = data.group4ki1.getId();
        UUID kind = id(send(post(KINDS), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND"}""").andReturn());
        period(kind, monday, group);
        assertThat(lessonsOf(group, monday)).isZero();
        int outbox = outbox();

        send(put(KINDS + "/" + kind).param("dryRun", "true"), """
                {"code":"ХХ","name":"Новий вид","effect":"INFO","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind.effect").value("INFO"))
                .andExpect(jsonPath("$.effectChanged").value(true))
                .andExpect(jsonPath("$.periods").value(1))
                .andExpect(jsonPath("$.dates", hasSize(1)))
                .andExpect(jsonPath("$.groups[0].code").value("4КІ1"))
                .andExpect(jsonPath("$.lessons.added").value(2))
                .andExpect(jsonPath("$.conflicts", hasSize(0)))
                .andExpect(jsonPath("$.canApply").value(true));
        assertThat(lessonsOf(group, monday)).as("dry run saves nothing").isZero();
        assertThat(outbox()).as("dry run: nothing leaves").isEqualTo(outbox);
        long announced = events.stream(ScheduleChanged.class).count();

        send(put(KINDS + "/" + kind), """
                {"code":"ХХ","name":"Новий вид","effect":"INFO","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("INFO"))
                .andExpect(jsonPath("$.usage").value(1));
        assertThat(lessonsOf(group, monday)).as("the pairs are back").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT kind FROM calendar_period WHERE kind_id = ?", String.class, kind))
                .as("legacy column follows the effect").isEqualTo("EXAM_SESSION");
        assertThat(events.stream(ScheduleChanged.class).count()).isEqualTo(announced + 1);

        send(put(KINDS + "/" + kind), """
                {"code":"ХХ","name":"Перейменований вид","effect":"INFO","version":1}""")
                .andExpect(status().isOk());
        assertThat(events.stream(ScheduleChanged.class).count()).as("texts of site and bot change")
                .isEqualTo(announced + 2);
        assertThat(lessonsOf(group, monday)).isEqualTo(2);
    }

    /** A live decision on a day the new effect would close: the effect change is refused with conflicts. */
    @Test
    void effectChangeThatWouldStrandALiveChangeIsRefused() throws Exception {
        LocalDate monday = data.monday(4);
        UUID group = data.group4ki1.getId();
        UUID kind = id(send(post(KINDS), """
                {"code":"ХХ","name":"Новий вид","effect":"INFO"}""").andReturn());
        period(kind, monday, group);
        Lesson stp = lessonAt(group, monday, 2);
        changeCommands.create(replaceTeacher(stp), "admin");

        send(put(KINDS + "/" + kind).param("dryRun", "true"), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].lessonIds[0]").value(stp.getId().toString()))
                // числа лишаються й тоді, коли правку буде відхилено: пара КМ зникла б
                .andExpect(jsonPath("$.lessons.removed").value(1))
                .andExpect(jsonPath("$.canApply").value(false));
        send(put(KINDS + "/" + kind), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND","version":0}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:schedule-conflict"))
                .andExpect(jsonPath("$.conflicts", hasSize(1)));
        assertThat(jdbc.queryForObject("SELECT effect FROM period_kind WHERE id = ?", String.class, kind))
                .isEqualTo("INFO");
        assertThat(lessonsOf(group, monday)).isEqualTo(2);
    }

    /** DoD: the import's dry run reports the 409 of a live change before anything is written. */
    @Test
    void importDryRunReportsLiveChangeConflictsBeforeWriting() throws Exception {
        LocalDate monday = data.monday(4);
        UUID group = data.group4ki1.getId();
        Lesson stp = lessonAt(group, monday, 2);
        changeCommands.create(replaceTeacher(stp), "admin");
        String calendar = """
                {"academicYear":"2026/2027","periods":[
                  {"sourceKey":"2026/2027|НС|%s|%s|3f9a1c2e","kindCode":"НС","title":"Основи національного спротиву",
                   "startsOn":"%s","endsOn":"%s","scope":"GROUPS","groupCodes":["4КІ1"],"roomName":"304"}]}"""
                .formatted(monday, monday.plusDays(4), monday, monday.plusDays(4));

        send(post("/api/admin/import/calendar-periods").param("dryRun", "true"), calendar)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.canApply").value(false))
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].lessonIds[0]").value(stp.getId().toString()))
                .andExpect(jsonPath("$.lessons.removed").value(1));
        send(post("/api/admin/import/calendar-periods"), calendar)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts", hasSize(1)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_period", Integer.class)).isZero();
        assertThat(lessonsOf(group, monday)).isEqualTo(2);
    }

    private void period(UUID kind, LocalDate monday, UUID group) throws Exception {
        send(post("/api/admin/calendar-periods"), """
                {"semesterId":"%s","kindId":"%s","title":"Блок","startsOn":"%s","endsOn":"%s",
                 "scope":"GROUPS","groupIds":["%s"]}"""
                .formatted(data.semester.getId(), kind, monday, monday, group))
                .andExpect(status().isCreated());
    }

    private CreateChangeRequest replaceTeacher(Lesson lesson) {
        return new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, lesson.getDate(), lesson.getPairNumber(), null,
                lesson.getGroupId(), null, lesson.getId(), null, data.nepyivoda.getId(), null, null, ReasonCode.ABSENT,
                null, null);
    }

    private Lesson lessonAt(UUID groupId, LocalDate date, int pair) {
        return transaction.execute(status -> em.createQuery("""
                        select l from Lesson l where l.groupId = :g and l.date = :d and l.pairNumber = :p
                        """, Lesson.class)
                .setParameter("g", groupId).setParameter("d", date).setParameter("p", pair).getSingleResult());
    }

    private int lessonsOf(UUID groupId, LocalDate date) {
        return jdbc.queryForObject("SELECT count(*) FROM lesson WHERE group_id = ? AND date = ? AND status = 'SCHEDULED'",
                Integer.class, groupId, date);
    }

    private int outbox() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mvc.perform(request.with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
