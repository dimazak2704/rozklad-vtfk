package ua.vtc.rozklad.importer.apply;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The JSON the importer sends must match core's import DTOs; core's rejections must reach the admin. */
class CoreImportClientTest {

    private MockRestServiceServer core;
    private CoreImportClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core");
        core = MockRestServiceServer.bindTo(builder).build();
        client = new CoreImportClient(builder.build());
    }

    @Test
    void aliasesUseTheFieldNamesOfCoresCatalogImport() {
        // core: CatalogImportDtos.AliasItem(type, alias, target) — «targetShortName» was rejected with 400
        core.expect(requestTo("http://core/api/admin/import/catalog"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(CoreImportClient.IDEMPOTENCY_HEADER, "import-job:1:catalog"))
                .andExpect(jsonPath("$.aliases[0].type").value("TEACHER"))
                .andExpect(jsonPath("$.aliases[0].alias").value("Граболінська"))
                .andExpect(jsonPath("$.aliases[0].target").value("Гарболінська"))
                .andExpect(jsonPath("$.aliases[0].targetShortName").doesNotExist())
                .andRespond(withSuccess("{\"created\":0}", MediaType.APPLICATION_JSON));

        client.importCatalog(new CoreImportPayloads.CatalogRequest(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(new CoreImportPayloads.Alias("TEACHER", "Граболінська", "Гарболінська"))),
                "import-job:1:catalog");

        core.verify();
    }

    @Test
    void rejectionCarriesTheFieldErrorsOfTheProblemDetail() {
        core.expect(requestTo("http://core/api/admin/import/catalog"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"about:blank","title":"Bad Request","status":400,
                                 "detail":"Перевірте виділені поля.",
                                 "errors":[{"field":"aliases[0].target","message":"не може бути порожнім"}]}
                                """));

        assertThatThrownBy(() -> client.importCatalog(new CoreImportPayloads.CatalogRequest(List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()), "import-job:1:catalog"))
                .isInstanceOf(CoreImportException.class)
                // r3-ui-real-32: без шляху, коду HTTP і назв полів JSON
                .hasMessage("Сервер розкладу не прийняв довідники (групи, викладачів, дисципліни, аудиторії): "
                        + "Перевірте виділені поля. (синонім 1: не може бути порожнім)");
    }

    @Test
    void rejectionWithoutProblemBodyKeepsTheStatusOnly() {
        core.expect(requestTo("http://core/api/admin/import/timetable"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>bad gateway</html>"));

        assertThatThrownBy(() -> client.importTimetable(new CoreImportPayloads.TimetableRequest(null, "v", List.of()),
                "import-job:1:timetable"))
                .isInstanceOf(CoreImportException.class)
                .hasMessage("Сервер розкладу не зміг зберегти розклад через внутрішню помилку. Спробуйте пізніше.");
    }
}
