package ua.vtc.rozklad.importer.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.ai.AiExtractionPort;
import ua.vtc.rozklad.importer.ai.AiPurpose;
import ua.vtc.rozklad.importer.ai.AiUsageRecord;
import ua.vtc.rozklad.importer.ai.AiUsageRepository;
import ua.vtc.rozklad.importer.ai.FakeAiAdapter;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportException;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.catalog.CatalogPort;
import ua.vtc.rozklad.importer.catalog.CatalogUnavailableException;
import ua.vtc.rozklad.importer.config.Ids;
import ua.vtc.rozklad.importer.support.SeedFiles;
import ua.vtc.rozklad.importer.support.TestCatalogs;
import ua.vtc.rozklad.importer.support.TestTokens;
import ua.vtc.rozklad.importer.support.TestcontainersConfiguration;

/**
 * The whole job lifecycle on real PostgreSQL: upload → parse (synchronous executor) → review →
 * mappings → apply through a stubbed core, with idempotent re-apply and resumption after a failed
 * step; AI jobs with the fake adapter, usage accounting and the daily budget.
 */
@SpringBootTest(properties = {"app.ai.provider=fake", "app.ai.daily-budget-usd=1.00", "app.import.max-file-size=5MB"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImportJobFlowIT {

    @TestBean(name = ImportExecutorConfig.EXECUTOR)
    TaskExecutor importJobExecutor;

    static TaskExecutor importJobExecutor() {
        return new SyncTaskExecutor();
    }

    @MockitoBean
    CatalogPort catalog;

    @MockitoBean
    CoreImportClient core;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    AiExtractionPort aiPort;

    @Autowired
    AiUsageRepository aiUsage;

    @BeforeEach
    void setUp() {
        reset(catalog, core);
        aiUsage.deleteAll();
        ((FakeAiAdapter) aiPort).reset();
        when(catalog.snapshot()).thenReturn(TestCatalogs.college());
    }

    @Test
    void timetableIsReviewedMappedAndAppliedOnce() throws Exception {
        JsonNode uploaded = upload("schedule-2025-26.xlsx", "TIMETABLE_XLSX", TestCatalogs.SEMESTER, null);
        assertThat(uploaded.get("status").asString()).isEqualTo("UPLOADED");
        UUID id = UUID.fromString(uploaded.get("id").asString());

        JsonNode parsed = job(id);
        assertThat(parsed.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(parsed.at("/summary/conflicts").asInt()).isEqualTo(1);
        assertThat(parsed.at("/summary/recognized").asInt()).isGreaterThanOrEqualTo(10);
        JsonNode firstRow = parsed.at("/report/0");
        assertThat(firstRow.get("raw").asString()).isEqualTo("Стець");
        assertThat(firstRow.at("/suggestions/0/id").asString()).isEqualTo(TestCatalogs.STETSIO.toString());
        assertThat(parsed.at("/draft/lessons").size()).isGreaterThan(1000);
        assertThat(parsed.get("canApply").asBoolean()).isFalse();

        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMPORT_NEEDS_REVIEW"));

        String mapping = """
                {"mappings":[{"type":"TEACHER","raw":"Стець","createNew":{},"addAlias":false},
                             {"type":"DISCIPLINE","raw":"НДЕКС","targetId":"%s","addAlias":true}]}
                """.formatted(TestCatalogs.NDEKSM);
        mockMvc.perform(put("/api/import/jobs/{id}/mappings", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(mapping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.summary.conflicts").value(0))
                .andExpect(jsonPath("$.mappings.length()").value(2));

        when(core.importCatalog(any(), anyString())).thenReturn(jsonMapper.readTree("{\"created\":120,\"updated\":0}"));
        when(core.importTimetable(any(), anyString()))
                .thenReturn(jsonMapper.readTree("{\"versionId\":\"00000000-0000-4000-8000-00000000aaaa\"}"));
        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPLYING"));

        JsonNode applied = job(id);
        assertThat(applied.get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.at("/result/timetable/versionId").asString()).isEqualTo("00000000-0000-4000-8000-00000000aaaa");

        ArgumentCaptor<CoreImportPayloads.CatalogRequest> catalogRequest =
                ArgumentCaptor.forClass(CoreImportPayloads.CatalogRequest.class);
        verify(core).importCatalog(catalogRequest.capture(), eq("import-job:" + id + ":catalog"));
        assertThat(catalogRequest.getValue().teachers()).extracting(CoreImportPayloads.Teacher::shortName)
                .contains("Стець", "Гарболінська", "Вихристюк Ю.А.")
                .doesNotContain("Кренцін", "Кренцін М.Д.", "Харченко О.А.", "Непийвода");
        assertThat(catalogRequest.getValue().groups()).extracting(CoreImportPayloads.Group::code)
                .contains("1КІ1", "1ТК1").doesNotContain("4КІ1", "4КІ2").hasSize(54);
        assertThat(catalogRequest.getValue().rooms()).extracting(CoreImportPayloads.Room::name)
                .doesNotContain("308", "с.з.").contains("с.п.");
        assertThat(catalogRequest.getValue().aliases()).extracting(CoreImportPayloads.Alias::alias)
                .contains("НДЕКС", "Граболінська", "Герасменко");

        ArgumentCaptor<CoreImportPayloads.TimetableRequest> timetable =
                ArgumentCaptor.forClass(CoreImportPayloads.TimetableRequest.class);
        verify(core).importTimetable(timetable.capture(), eq("import-job:" + id + ":timetable"));
        assertThat(timetable.getValue().semesterId()).isEqualTo(TestCatalogs.SEMESTER);
        assertThat(timetable.getValue().lessons()).hasSize(applied.at("/draft/lessons").size());
        assertThat(timetable.getValue().lessons())
                .filteredOn(lesson -> lesson.groupCode().equals("4КІ1") && "СТП".equals(lesson.disciplineShortName()))
                .isNotEmpty()
                .allMatch(lesson -> "Кренцін М.Д.".equals(lesson.teacherShortName()));

        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        verify(core, times(1)).importCatalog(any(), anyString());
        verify(core, times(1)).importTimetable(any(), anyString());
    }

    @Test
    void failedApplyStepIsResumedWithoutRepeatingDoneSteps() throws Exception {
        when(catalog.snapshot()).thenReturn(ua.vtc.rozklad.importer.catalog.CatalogSnapshot.empty());
        UUID id = UUID.fromString(upload("schedule-2025-26.xlsx", "TIMETABLE_XLSX", null, null).get("id").asString());
        assertThat(job(id).get("status").asString()).isEqualTo("READY");

        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SEMESTER_REQUIRED"));

        when(core.importCatalog(any(), anyString())).thenReturn(jsonMapper.readTree("{\"created\":300}"));
        when(core.importTimetable(any(), anyString()))
                .thenThrow(new CoreImportException(503, "Core недоступний: не вдалося виконати імпорт.", null))
                .thenReturn(jsonMapper.readTree("{\"versionId\":\"00000000-0000-4000-8000-00000000bbbb\"}"));
        String body = "{\"semesterId\":\"" + TestCatalogs.SEMESTER + "\"}";
        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        JsonNode failed = job(id);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("Core недоступний");
        assertThat(failed.at("/result/catalog/created").asInt()).isEqualTo(300);
        assertThat(failed.get("canApply").asBoolean()).isTrue();

        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isAccepted());
        assertThat(job(id).get("status").asString()).isEqualTo("APPLIED");
        verify(core, times(1)).importCatalog(any(), anyString());
        verify(core, times(2)).importTimetable(any(), anyString());
    }

    @Test
    void catalogOutageBlocksReviewUntilMatchingIsRepeated() throws Exception {
        when(catalog.snapshot()).thenThrow(new CatalogUnavailableException("Не вдалося отримати каталог з core.", null));
        UUID id = UUID.fromString(upload("schedule-2025-26.xlsx", "TIMETABLE_XLSX", TestCatalogs.SEMESTER, null)
                .get("id").asString());
        JsonNode parsed = job(id);
        assertThat(parsed.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(parsed.at("/report/0/type").asString()).isEqualTo("CATALOG");

        mockMvc.perform(put("/api/import/jobs/{id}/mappings", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mappings\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:service-unavailable"));

        reset(catalog);
        when(catalog.snapshot()).thenReturn(TestCatalogs.college());
        mockMvc.perform(put("/api/import/jobs/{id}/mappings", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mappings\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.conflicts").value(1));
    }

    @Test
    void orderPhotoIsRecognizedByAiWithUsageAccounting() throws Exception {
        ((FakeAiAdapter) aiPort).reply(AiPurpose.ORDER_DOCUMENT, """
                {"number":35,"kind":"ORDER","parentNumber":null,"date":"2026-10-19","weekdayLabel":"ПОНЕДІЛОК",
                 "parityLabel":"ЗНАМЕННИК","rows":[
                   {"section":"CHANGES","groupCode":"4КІ1","pairLabel":"2","rowType":"REPLACEMENT","fromDiscipline":"СТП",
                    "fromTeacher":"Кренцін","reasonText":"Зв.зам.","toDiscipline":"НДКС","toTeacher":"Непийвода","roomText":"6"},
                   {"section":"CHANGES","groupCode":"1ТК1","pairLabel":"1","rowType":"CANCELLATION","fromDiscipline":"Ін. мова",
                    "fromTeacher":"Крисак Л. (п.)","reasonText":"Відп.","toDiscipline":null,"toTeacher":null,"roomText":null}],
                 "notes":["Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."],
                 "signatoryPosition":"Заступник директора з НР","signatoryName":"Микола ПОЛІЩУК","warnings":[]}
                """, 2400, 900);
        UUID id = UUID.fromString(upload("orders/order-035-2026-10-19.jpg", "ORDER_DOCUMENT", null, null)
                .get("id").asString());

        JsonNode parsed = job(id);
        // рядки, прочитані ШІ, без звірки людиною застосувати не можна
        assertThat(parsed.get("status").asString()).isEqualTo("NEEDS_REVIEW");
        assertThat(parsed.get("canApply").asBoolean()).isFalse();
        assertThat(parsed.at("/draft/number").asInt()).isEqualTo(35);
        assertThat(parsed.at("/draft/date").asString()).isEqualTo("2026-10-19");
        assertThat(parsed.at("/draft/rows/1/rowType").asString()).isEqualTo("CANCELLATION");
        assertThat(parsed.at("/ai/inputTokens").asLong()).isEqualTo(2400);
        assertThat(parsed.at("/ai/costUsd").decimalValue()).isPositive();
        assertThat(((FakeAiAdapter) aiPort).requests()).singleElement()
                .satisfies(request -> assertThat(request.documents()).singleElement()
                        .satisfies(document -> assertThat(document.mediaType()).isEqualTo("image/jpeg")));

        mockMvc.perform(get("/api/import/ai/usage").with(TestTokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.calls").value(1))
                .andExpect(jsonPath("$.today.inputTokens").value(2400))
                .andExpect(jsonPath("$.dailyBudgetUsd").value(1.0))
                .andExpect(jsonPath("$.model.vision").value("claude-sonnet-5-5"));

        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IMPORT_NEEDS_REVIEW"));

        // адміністратор виправляє другий рядок (кабінет «С.з.» ШІ не прочитав) і зберігає звірені рядки
        String reviewed = """
                {"version":%d,"number":35,"kind":"ORDER","date":"2026-10-19","weekdayLabel":"ПОНЕДІЛОК",
                 "parityLabel":"ЗНАМЕННИК","rows":[
                   {"section":"CHANGES","groupCode":"4КІ1","pairLabel":"2","rowType":"REPLACEMENT","fromDiscipline":"СТП",
                    "fromTeacher":"Кренцін","reasonText":"Зв.зам.","toDiscipline":"НДКС","toTeacher":"Непийвода","roomText":"6"},
                   {"section":"CHANGES","groupCode":"1ТК1","pairLabel":"1","fromDiscipline":"Ін. мова",
                    "fromTeacher":"Крисак Л. (п.)","reasonText":"Відп.","toDiscipline":"Пари не буде","roomText":null},
                   {"section":"CHANGES","groupCode":"3КІ1","pairLabel":"1-3","fromDiscipline":"ФК","fromTeacher":"Бурбела",
                    "reasonText":"Вичит."}],
                 "notes":["Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."],
                 "signatoryPosition":"Заступник директора з НР","signatoryName":"Микола ПОЛІЩУК"}
                """.formatted(parsed.get("version").asLong());
        mockMvc.perform(put("/api/import/jobs/{id}/draft", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(reviewed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.draft.reviewed").value(true))
                .andExpect(jsonPath("$.draft.rows[1].rowType").value("CANCELLATION"))
                .andExpect(jsonPath("$.draft.rows[2].rowType").value("FINISHED"));
        mockMvc.perform(put("/api/import/jobs/{id}/draft", id).with(TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(reviewed))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:stale-data"));

        when(core.importChanges(any(), anyString())).thenReturn(jsonMapper.readTree("{\"items\":3}"));
        mockMvc.perform(post("/api/import/jobs/{id}/apply", id).with(TestTokens.admin())).andExpect(status().isAccepted());
        assertThat(job(id).get("status").asString()).isEqualTo("APPLIED");
        ArgumentCaptor<CoreImportPayloads.ChangesRequest> changes = ArgumentCaptor.forClass(CoreImportPayloads.ChangesRequest.class);
        verify(core).importChanges(changes.capture(), anyString());
        assertThat(changes.getValue().date()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(changes.getValue().items()).hasSize(3);
        assertThat(changes.getValue().replace()).isTrue();
    }

    @Test
    void exhaustedDailyBudgetRejectsAiUploadsWith429() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Kyiv"));
        aiUsage.save(new AiUsageRecord(Ids.newId(), Instant.now(), today, AiPurpose.ORDER_DOCUMENT, "claude-sonnet-5-5",
                100_000, 50_000, new BigDecimal("1.200000"), AiUsageRecord.Outcome.SUCCESS, null));

        mockMvc.perform(multipart("/api/import/jobs")
                        .file(file("orders/order-096-2026-05-22.jpg"))
                        .param("kind", "ORDER_DOCUMENT")
                        .with(TestTokens.admin()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:ai-budget-exceeded"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("бюджет")));
        assertThat(((FakeAiAdapter) aiPort).requests()).isEmpty();
    }

    @Test
    void uploadValidation() throws Exception {
        mockMvc.perform(multipart("/api/import/jobs")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", "просто текст".getBytes()))
                        .param("kind", "TIMETABLE_XLSX").with(TestTokens.admin()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.title").value("Непідтримуваний файл"));
        mockMvc.perform(multipart("/api/import/jobs")
                        .file(file("programs/program-business-communications-fragment.docx"))
                        .param("kind", "TIMETABLE_XLSX").with(TestTokens.admin()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("XLSX")));
        mockMvc.perform(multipart("/api/import/jobs")
                        .file(file("schedule-2025-26.xlsx"))
                        .param("kind", "TIMETABLE_XLSX").param("options", "{\"sheetName\":\"x\"}").with(TestTokens.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("options.sheetName"));
        mockMvc.perform(multipart("/api/import/jobs")
                        .file(file("orders/order-035-2026-10-19.jpg"))
                        .param("kind", "CURRICULUM").with(TestTokens.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("options.semesterNumber"));
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-1.4".getBytes(), 0, tooLarge, 0, 8);
        mockMvc.perform(multipart("/api/import/jobs")
                        .file(new MockMultipartFile("file", "big.pdf", "application/pdf", tooLarge))
                        .param("kind", "DISCIPLINE_PROGRAM").with(TestTokens.admin()))
                .andExpect(status().isContentTooLarge());
        mockMvc.perform(multipart("/api/import/jobs").file(file("schedule-2025-26.xlsx"))
                        .param("kind", "TIMETABLE_XLSX").with(TestTokens.teacher()))
                .andExpect(status().isForbidden());
        verify(core, never()).importCatalog(any(), anyString());
    }

    @Test
    void listsJobsNewestFirstWithoutDrafts() throws Exception {
        UUID first = UUID.fromString(upload("programs/program-economics-it-projects.docx", "DISCIPLINE_PROGRAM",
                TestCatalogs.SEMESTER, "{\"groupCodes\":[\"4КІ1\"]}").get("id").asString());
        JsonNode program = job(first);
        assertThat(program.get("status").asString()).isEqualTo("READY");
        assertThat(program.at("/draft/hours/total").asInt()).isEqualTo(150);

        String page = mockMvc.perform(get("/api/import/jobs").param("kind", "DISCIPLINE_PROGRAM").with(TestTokens.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = jsonMapper.readTree(page);
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(first.toString());
        assertThat(list.get("items").get(0).has("draft")).isFalse();
        assertThat(list.get("items").get(0).get("report").size()).isZero();
        assertThat(list.get("total").asLong()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/import/jobs/{id}", UUID.randomUUID()).with(TestTokens.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Завдання імпорту не знайдено."));
    }

    private JsonNode upload(String seedFile, String kind, UUID semesterId, String options) throws Exception {
        var request = multipart("/api/import/jobs").file(file(seedFile)).param("kind", kind).with(TestTokens.admin());
        if (semesterId != null) {
            request.param("semesterId", semesterId.toString());
        }
        if (options != null) {
            request.param("options", options);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/import/jobs/")))
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private JsonNode job(UUID id) throws Exception {
        String body = mockMvc.perform(get("/api/import/jobs/{id}", id).with(TestTokens.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body);
    }

    private static MockMultipartFile file(String seedFile) {
        String name = seedFile.substring(seedFile.lastIndexOf('/') + 1);
        return new MockMultipartFile("file", name, "application/octet-stream", SeedFiles.read(seedFile));
    }
}
