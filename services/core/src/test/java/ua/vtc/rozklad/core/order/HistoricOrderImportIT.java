package ua.vtc.rozklad.core.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.order.internal.numbering.OrderNumberingService;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * {@code POST /api/admin/import/changes}: a paper order (№35 of 19.10.2026) becomes a published
 * order of record that the public changes page shows and the numbering continues after; repeating
 * the import is harmless, a taken number, a future date and an unreadable row are rejected.
 */
@CoreIntegrationTest
class HistoricOrderImportIT {

    private static final String ORDER_35 = """
            {"date":"2026-10-19","number":35,"kind":"ORDER","weekdayLabel":"ПОНЕДІЛОК","parityLabel":"ЗНАМЕННИК",
             "items":[
               {"section":"CHANGES","groupCode":"1КІ2","pairLabel":"2","rowType":"REPLACEMENT",
                "fromDiscipline":"Ін. мова","fromTeacher":"Крисак Л.","reasonText":"Відп.",
                "toDiscipline":"Ін.мова","toTeacher":"Прокопчук","roomText":"110"},
               {"section":"ROOM_CHANGES","groupCode":"1ОП2","pairLabel":"4","rowType":"ROOM_CHANGE","roomText":"403"}],
             "notes":["Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."],
             "signatoryPosition":"Заступник директора з НР","signatoryName":"Микола ПОЛІЩУК"}
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderNumberingService numbering;

    @Autowired
    private Clock clock;

    private MockMvc mvc;
    private ScheduleFixture fixture;
    private Semester semester;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void paperOrderBecomesAPublishedOrderOfRecord() throws Exception {
        importOrder(ORDER_35)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(35))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.created").value(true));

        mvc.perform(get("/api/public/changes").param("date", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.number").value(35))
                .andExpect(jsonPath("$.order.imported").value(true))
                .andExpect(jsonPath("$.groups[0].group.code").value("1КІ2"))
                .andExpect(jsonPath("$.groups[0].group.id").doesNotExist())
                .andExpect(jsonPath("$.groups[0].items[0].to.teacher").value("Прокопчук"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change", Integer.class)).as("nothing is applied").isZero();
        assertThat(jdbc.queryForObject("SELECT title FROM order_document WHERE number = 35", String.class))
                .isEqualTo("РОЗПОРЯДЖЕННЯ №35");
        assertThat(numbering.nextNumber(semester.getId())).as("numbering continues after the paper order")
                .isEqualTo(36);

        importOrder(ORDER_35).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_document", Integer.class)).isOne();
    }

    /** A misreading fixed after apply: reviewed rows replace the earlier import of the same paper order. */
    @Test
    void reviewedRowsReplaceAnEarlierImportOfTheSameOrder() throws Exception {
        importOrder(ORDER_35).andExpect(status().isOk());
        String corrected = ORDER_35.replace("\"toTeacher\":\"Прокопчук\"", "\"toTeacher\":\"Прокопчук О.\"")
                .replace("\"signatoryName\":\"Микола ПОЛІЩУК\"}", "\"signatoryName\":\"Микола ПОЛІЩУК\",\"replace\":true}");

        importOrder(corrected)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.replaced").value(true))
                .andExpect(jsonPath("$.itemCount").value(2));

        mvc.perform(get("/api/public/changes").param("date", "2026-10-19"))
                .andExpect(jsonPath("$.groups[0].items[0].to.teacher").value("Прокопчук О."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_document", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_item", Integer.class)).isEqualTo(2);
    }

    /** Opening the orders page for the date leaves an empty draft: it does not block the paper order. */
    @Test
    void untouchedEmptyDraftGivesWayToThePaperOrder() throws Exception {
        mvc.perform(get("/api/admin/orders/draft").param("date", "2026-10-19").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items").isEmpty());

        importOrder(ORDER_35).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));

        assertThat(jdbc.queryForList("SELECT status FROM order_document", String.class)).containsExactly("PUBLISHED");
    }

    /** A draft the dispatcher worked on blocks the import until it is deleted; published orders stay. */
    @Test
    void editedDraftBlocksTheImportUntilItIsDeleted() throws Exception {
        String draftId = JsonPath.read(mvc.perform(get("/api/admin/orders/draft").param("date", "2026-10-19")
                .with(TestJwts.admin())).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(put("/api/admin/orders/{id}/notes", draftId).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":[\"Примітка диспетчера\"]}"))
                .andExpect(status().isOk());

        importOrder(ORDER_35)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("На 19.10.2026 уже є чернетка розпорядження: опублікуйте її "
                        + "або видаліть, щоб імпортувати паперове."));
        mvc.perform(delete("/api/admin/orders/{id}", draftId).with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/orders/{id}", draftId).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
        importOrder(ORDER_35).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));

        String publishedId = jdbc.queryForObject("SELECT id::text FROM order_document", String.class);
        mvc.perform(delete("/api/admin/orders/{id}", publishedId).with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PUBLISHED"));
        mvc.perform(delete("/api/admin/orders/{id}", draftId).with(TestJwts.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void takenNumberFutureDateAndUnreadableRowAreRejected() throws Exception {
        importOrder(ORDER_35).andExpect(status().isOk());

        importOrder(ORDER_35.replace("2026-10-19", "2026-10-20"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Розпорядження №35 уже є в семестрі «%s» (на 19.10.2026)."
                        .formatted(semester.getName())));
        importOrder(ORDER_35.replace("\"toTeacher\":\"Прокопчук\"", "\"toTeacher\":\"Прокопчук О.\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("На 19.10.2026 уже є розпорядження №35, і його рядки "
                        + "відрізняються від імпортованих. Звірте розпорядження в системі з паперовим."));
        importOrder(ORDER_35.replace("\"number\":35", "\"number\":36"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("На 19.10.2026 уже є розпорядження №35."));
        String future = LocalDate.now(clock).plusDays(3).toString();
        importOrder(ORDER_35.replace("2026-10-19", future).replace("\"number\":35", "\"number\":40"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IMPORT_FUTURE_DATE"));
        importOrder(ORDER_35.replace("\"groupCode\":\"1КІ2\"", "\"groupCode\":null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[0].groupCode"));
    }

    private ResultActions importOrder(String body) throws Exception {
        return mvc.perform(post("/api/admin/import/changes").with(TestJwts.service())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
