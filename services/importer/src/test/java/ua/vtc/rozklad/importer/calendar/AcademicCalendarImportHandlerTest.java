package ua.vtc.rozklad.importer.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportException;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot.PeriodKindItem;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.support.SeedFiles;
import ua.vtc.rozklad.importer.support.TestCalendars;

class AcademicCalendarImportHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COUNTS = """
            {"dryRun":true,"academicYear":"2026/2027","canApply":true,"created":5,"updated":1,"unchanged":2,
             "deleted":0,"skippedManual":0,"periods":[],"manual":[],"manualOverlaps":[],"outOfSemester":[],
             "groupSemesters":{"created":1,"updated":0,"unchanged":0,"skipped":[]},"conflicts":[],
             "lessons":{"added":0,"removed":0,"updated":0}}""";

    private final CoreImportClient core = mock(CoreImportClient.class);
    private final AcademicCalendarImportHandler handler = new AcademicCalendarImportHandler(core, JSON);
    private final NormalizationEngine engine = new NormalizationEngine();

    @Test
    void realCalendarBecomesPeriodsWithKindsScopesRoomsAndNotes() {
        when(core.importCalendarPeriods(any(), eq(true), isNull())).thenReturn(json(COUNTS));
        ImportHandler.Review review = review(realDraft(), JobOptions.none(), TestCalendars.college());
        AcademicCalendarDraft draft = JSON.treeToValue(review.draft(), AcademicCalendarDraft.class);

        assertThat(draft.codes()).allSatisfy(code -> assertThat(code.kindCode()).isNotNull());
        assertThat(draft.codes()).filteredOn(code -> code.code().equals("ЗУ")).singleElement()
                .satisfies(code -> assertThat(code.effect()).isEqualTo("SUSPEND"));
        AcademicCalendarDraft.Period ons = period(draft, "НС", LocalDate.of(2026, 11, 16));
        assertThat(ons.sourceKey()).isEqualTo("2026/2027|НС|2026-11-16|2026-11-27|"
                + CalendarPlan.sha8("Основи національного спротиву"));
        assertThat(ons.endsOn()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(ons.scope()).isEqualTo("GROUPS");
        assertThat(ons.groupCodes()).containsExactly("4КІ1");
        assertThat(ons.roomName()).isEqualTo("401");
        assertThat(ons.title()).isEqualTo("Основи національного спротиву");
        assertThat(ons.orderNote()).isEqualTo("4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401");
        assertThat(ons.cells()).isEqualTo("T61:U61");
        assertThat(period(draft, "НС", LocalDate.of(2026, 10, 19)).groupCodes()).containsExactly("4ОП2");
        assertThat(period(draft, "НС", LocalDate.of(2026, 11, 2)).endsOn()).isEqualTo(LocalDate.of(2026, 11, 13));

        AcademicCalendarDraft.Period winter = period(draft, "К", LocalDate.of(2026, 12, 28));
        assertThat(winter.scope()).isEqualTo("ALL");
        assertThat(winter.groupCodes()).isEmpty();
        assertThat(winter.roomName()).isNull();
        assertThat(winter.orderNote()).isEqualTo("Усі групи: канікули з 28.12.26р. по 08.01.27 р.");
        AcademicCalendarDraft.Period summer = period(draft, "К", LocalDate.of(2027, 6, 28));
        assertThat(summer.scope()).isEqualTo("COURSES");
        assertThat(summer.courses()).containsExactly(1, 2, 3);
        assertThat(summer.endsOn()).isEqualTo(LocalDate.of(2027, 8, 27));

        AcademicCalendarDraft.Period radio = period(draft, "НП", LocalDate.of(2026, 9, 21), "2КІ1");
        assertThat(radio.title()).isEqualTo("Навчальна радіомонтажна практика");
        assertThat(radio.orderNote()).isEqualTo("2КІ1 – Навчальна радіомонтажна практика з 21.09.26р. по 02.10.26 р.");
        assertThat(period(draft, "НП", LocalDate.of(2026, 9, 21), "4РТ1").title()).isEqualTo("Навчальна практика");

        assertThat(draft.groupSemesters()).hasSize(112)
                .contains(new CoreImportPayloads.GroupSemester("4КІ1", 1, 20, 4),
                        new CoreImportPayloads.GroupSemester("4КІ1", 2, null, null));
        assertThat(draft.check().path("created").asInt()).isEqualTo(5);
        assertThat(review.rows()).noneMatch(ReportRow::blocking)
                .anySatisfy(row -> assertThat(row.message()).isEqualTo("Буде створено 5, оновлено 1, видалено 0, без змін 2."));

        ArgumentCaptor<CoreImportPayloads.CalendarPeriodsRequest> sent =
                ArgumentCaptor.forClass(CoreImportPayloads.CalendarPeriodsRequest.class);
        verify(core).importCalendarPeriods(sent.capture(), eq(true), isNull());
        assertThat(sent.getValue().academicYear()).isEqualTo("2026/2027");
        assertThat(sent.getValue().periods()).hasSize(draft.periods().size())
                .extracting(CoreImportPayloads.CalendarPeriod::sourceKey).doesNotHaveDuplicates();
        assertThat(sent.getValue().groupSemesters()).hasSize(112);
    }

    @Test
    void sameFileGivesTheSameRequestSoAReImportChangesNothing() {
        when(core.importCalendarPeriods(any(), anyBoolean(), any())).thenReturn(json(COUNTS));
        review(realDraft(), JobOptions.none(), TestCalendars.college());
        review(JSON.valueToTree(AcademicCalendarParserTest.real().draft()), JobOptions.none(), TestCalendars.college());

        ArgumentCaptor<CoreImportPayloads.CalendarPeriodsRequest> sent =
                ArgumentCaptor.forClass(CoreImportPayloads.CalendarPeriodsRequest.class);
        verify(core, org.mockito.Mockito.times(2)).importCalendarPeriods(sent.capture(), eq(true), isNull());
        assertThat(sent.getAllValues().get(1)).isEqualTo(sent.getAllValues().get(0));
    }

    @Test
    void unknownCodeBlocksWithoutAskingCoreUntilItIsSkipped() throws IOException {
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "Х", "НС"}, {"4КІ2", "Х"}});
        ImportHandler.Review review = review(draft, JobOptions.none(), TestCalendars.college());

        assertThat(review.rows()).filteredOn(ReportRow::blocking).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(RowType.PERIOD_KIND);
            assertThat(row.raw()).isEqualTo("Х");
            assertThat(row.location()).isEqualTo("D6:D6");
            assertThat(row.message()).isEqualTo("Невідомий код «Х» у груп 4КІ1, 4КІ2: додайте вид у «Видах періодів» "
                    + "або пропустіть код.");
        });
        assertThat(review.rows()).anyMatch(row -> row.message().startsWith("Сервер розкладу перевірить періоди"));
        verify(core, never()).importCalendarPeriods(any(), anyBoolean(), any());

        when(core.importCalendarPeriods(any(), anyBoolean(), any())).thenReturn(json(COUNTS));
        ImportHandler.Review skipped = review(draft, options(Map.of("skipCodes", List.of("x"))), TestCalendars.college());
        assertThat(skipped.rows()).noneMatch(ReportRow::blocking)
                .anyMatch(row -> row.message().equals("Код «Х» пропущено (параметри імпорту): 2 періоди не імпортуються."));
        AcademicCalendarDraft refreshed = JSON.treeToValue(skipped.draft(), AcademicCalendarDraft.class);
        assertThat(refreshed.periods()).extracting(AcademicCalendarDraft.Period::kindCode).containsExactly("НС");
    }

    @Test
    void singleGroupIsNamedInTheUnknownCodeMessage() throws IOException {
        ImportHandler.Review review = review(synthetic(new String[][] {{"4КІ1", "Ы"}}), JobOptions.none(),
                TestCalendars.college());
        assertThat(review.rows()).anyMatch(row -> row.message().equals("Невідомий код «Ы» у групи 4КІ1: додайте вид у "
                + "«Видах періодів» або пропустіть код."));
    }

    @Test
    void inactiveKindBlocks() throws IOException {
        List<PeriodKindItem> kinds = new ArrayList<>(TestCalendars.kinds());
        kinds.replaceAll(kind -> kind.code().equals("НС") ? new PeriodKindItem(kind.id(), kind.code(), kind.name(),
                kind.effect(), null, List.of(), kind.noteTemplate(), false) : kind);
        ImportHandler.Review review = review(synthetic(new String[][] {{"4КІ1", "НС"}}), JobOptions.none(),
                TestCalendars.college(TestCalendars.GROUPS, kinds));
        assertThat(review.rows()).anyMatch(row -> row.blocking()
                && row.message().equals("Вид «НС» вимкнено: увімкніть його або пропустіть код."));
    }

    @Test
    void onlineKindIsNotImportedFromTheCalendarUntilSkipped() throws IOException {
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "ФН", "НС"}});
        ImportHandler.Review review = review(draft, JobOptions.none(), TestCalendars.college());
        assertThat(review.rows()).filteredOn(ReportRow::blocking).singleElement().satisfies(row -> {
            assertThat(row.type()).isEqualTo(RowType.PERIOD_KIND);
            assertThat(row.raw()).isEqualTo("ФН");
            assertThat(row.message()).isEqualTo("Вид «ФН» з ефектом «онлайн» не імпортується з графіка (у графіку немає "
                    + "режиму навчання): пропустіть код і додайте такий період у календарі вручну.");
        });
        verify(core, never()).importCalendarPeriods(any(), anyBoolean(), any());

        when(core.importCalendarPeriods(any(), anyBoolean(), any())).thenReturn(json(COUNTS));
        ImportHandler.Review skipped = review(draft, options(Map.of("skipCodes", List.of("ФН"))), TestCalendars.college());
        assertThat(skipped.rows()).noneMatch(ReportRow::blocking);
        assertThat(JSON.treeToValue(skipped.draft(), AcademicCalendarDraft.class).periods())
                .extracting(AcademicCalendarDraft.Period::kindCode).containsExactly("НС");
    }

    @Test
    void olderCoreWithoutKindsGetsOneClearError() throws IOException {
        ImportHandler.Review review = review(synthetic(new String[][] {{"4КІ1", "НС", "К"}}), JobOptions.none(),
                TestCalendars.college(TestCalendars.GROUPS, List.of()));
        assertThat(review.rows()).filteredOn(ReportRow::blocking).singleElement()
                .satisfies(row -> assertThat(row.message()).startsWith("Сервер розкладу не передав види періодів"));
    }

    @Test
    void unknownGroupKeepsCoreUnaskedAndSkippingItLetsTheCheckRun() throws IOException {
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "НС"}, {"5ХХ1", "НС"}});
        ImportHandler.Review review = review(draft, JobOptions.none(), TestCalendars.college());
        verify(core, never()).importCalendarPeriods(any(), anyBoolean(), any());
        AcademicCalendarDraft built = JSON.treeToValue(review.draft(), AcademicCalendarDraft.class);
        assertThat(built.periods()).singleElement().satisfies(period -> {
            assertThat(period.groupCodes()).containsExactly("4КІ1", "5ХХ1");
            assertThat(period.orderNote()).isEqualTo("Групи 4КІ1, 5ХХ1 – Основи національного спротиву з 31.08.26р. по "
                    + "04.09.26 р., ауд. 401");
        });

        when(core.importCalendarPeriods(any(), anyBoolean(), any())).thenReturn(json(COUNTS));
        JobOptions skip = options(Map.of("skipGroups", List.of("5ХХ-1")));
        assertThat(handler.entities(draft, skip)).extracting(entity -> entity.raw()).containsExactly("4КІ1", "401");
        ImportHandler.Review skipped = review(draft, skip, TestCalendars.college());
        assertThat(skipped.rows()).noneMatch(ReportRow::blocking);
        verify(core).importCalendarPeriods(any(), eq(true), isNull());
    }

    @Test
    void optionsChangeTitleAndRoomAndRoomIsOnlyForSuspendingKinds() throws IOException {
        when(core.importCalendarPeriods(any(), anyBoolean(), any())).thenReturn(json(COUNTS));
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "НС", "", "К"}});
        ImportHandler.Review review = review(draft, options(Map.of("titles", Map.of("НС", "ОНС"),
                "rooms", Map.of("K", "401"))), TestCalendars.college());
        AcademicCalendarDraft built = JSON.treeToValue(review.draft(), AcademicCalendarDraft.class);

        assertThat(built.periods()).extracting(period -> period.kindCode() + " " + period.title() + " " + period.roomName())
                .containsExactly("НС ОНС null", "К Канікули null");
        assertThat(review.rows()).anyMatch(row -> row.message().equals("Аудиторію для «К» не передано: її вказують лише "
                + "для виду, коли група не на парах."));
    }

    @Test
    void dryRunFindingsBecomeReportRows() throws IOException {
        JsonNode draftJson = synthetic(new String[][] {{"4КІ1", "НС"}});
        when(core.importCalendarPeriods(any(), eq(true), isNull())).thenAnswer(call -> {
            CoreImportPayloads.CalendarPeriodsRequest request = call.getArgument(0);
            String key = request.periods().getFirst().sourceKey();
            return json("""
                    {"dryRun":true,"canApply":false,"created":0,"updated":0,"unchanged":0,"deleted":1,
                     "conflicts":[{"kind":"GROUP","severity":"HARD","message":"Заміна в 4КІ1 зникне","lessonIds":[],
                                   "date":"2026-09-01","pair":2}],
                     "outOfSemester":[{"sourceKey":"%1$s","kindCode":"НС","startsOn":"2026-08-31","endsOn":"2026-09-04",
                                       "groupCodes":["4КІ1"],"kept":[{"storedKey":"%1$s","startsOn":"2026-09-01",
                                       "endsOn":"2026-09-04"}]}],
                     "manual":[{"storedKey":"%1$s|S1","periodId":"%2$s","title":"ОНС","differs":["startsOn","title"]},
                               {"storedKey":"x","periodId":"%2$s","title":"Same","differs":[]}],
                     "manualOverlaps":[{"periodId":"%2$s","kindCode":"НП","title":"Практика 4КІ1","startsOn":"2026-09-01",
                                        "endsOn":"2026-09-04","groupCodes":["4КІ1"]}],
                     "groupSemesters":{"skipped":[{"groupCode":"4КІ1","semesterNumber":2,
                                                   "reason":"Семестру II 2026/2027 немає в системі."}]},
                     "lessons":{"added":0,"removed":8,"updated":0}}
                    """.formatted(key, UUID.randomUUID()));
        });
        ImportHandler.Review review = review(draftJson, JobOptions.none(), TestCalendars.college());

        assertThat(review.rows()).extracting(row -> row.severity() + " " + row.message()).contains(
                "ERROR Заміна в 4КІ1 зникне (01.09.2026, пара 2)",
                "WARN НС 4КІ1 31.08–04.09 поза семестрами системи: обрізано до 01.09–04.09.",
                "WARN Період «ОНС» змінено вручну, імпорт його не чіпає (різниця: початок, назва).",
                "INFO Ручний період «Практика 4КІ1» (НП, 4КІ1, 01.09–04.09) перетинається з періодом графіка: обидва лишаться.",
                "WARN Тижні теорії й іспити групи 4КІ1 за семестр 2 не імпортуються: Семестру II 2026/2027 немає в системі.",
                "INFO Буде створено 0, оновлено 0, видалено 1, без змін 0.",
                "INFO Заняття в розкладі від сьогодні: знято — 8, повернуто — 0, змінено — 0.");
        assertThat(review.rows()).filteredOn(row -> row.message().contains("Same")).isEmpty();
        assertThat(review.rows()).filteredOn(ReportRow::blocking).hasSize(1);
        assertThat(JSON.treeToValue(review.draft(), AcademicCalendarDraft.class).check().path("deleted").asInt()).isOne();
    }

    @Test
    void wholePeriodOutsideSemestersIsNotImported() {
        JsonNode check = json("""
                {"created":0,"outOfSemester":[{"sourceKey":"k","kindCode":"НС","startsOn":"2027-01-11",
                 "endsOn":"2027-01-22","groupCodes":["4ЕА1"],"kept":[]}]}""");
        assertThat(handler.checkRows(check, new CalendarPlan.Built(List.of(), List.of(), List.of(), List.of())))
                .extracting(ReportRow::message)
                .contains("НС 4ЕА1 11.01–22.01 поза семестрами системи: не імпортується.");
    }

    @Test
    void coreFailuresOfTheCheckBlock() throws IOException {
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "НС"}});
        when(core.importCalendarPeriods(any(), anyBoolean(), any()))
                .thenThrow(new CoreImportException(0, "down", null))
                .thenThrow(new CoreImportException(404, "not found", null))
                .thenThrow(new CoreImportException(400, "Сервер розкладу не прийняв періоди графіка: Групу «4КІ1» не "
                        + "знайдено.", null))
                .thenReturn(null);

        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ImportHandler.Review review = review(draft, JobOptions.none(), TestCalendars.college());
            ReportRow row = review.rows().stream().filter(ReportRow::blocking).findFirst().orElseThrow();
            assertThat(row.type()).isEqualTo(RowType.PERIOD);
            messages.add(row.message());
            assertThat(JSON.treeToValue(review.draft(), AcademicCalendarDraft.class).check()).isNull();
        }
        assertThat(messages).containsExactly(
                "Сервер розкладу недоступний — перевірку періодів не виконано. Збережіть параметри імпорту ще раз, коли "
                        + "він буде доступний.",
                "Сервер розкладу ще не вміє імпортувати періоди графіка: оновіть його, потім збережіть параметри імпорту "
                        + "ще раз.",
                "Перевірка на сервері розкладу: Сервер розкладу не прийняв періоди графіка: Групу «4КІ1» не знайдено.",
                "Сервер розкладу недоступний — перевірку періодів не виконано. Збережіть параметри імпорту ще раз, коли "
                        + "він буде доступний.");
    }

    @Test
    void applySendsTheRealRequestOnceWithTheStepKey() {
        when(core.importCalendarPeriods(any(), eq(false), any())).thenReturn(json("{\"dryRun\":false,\"created\":120}"));
        JsonNode draft = realDraft();
        CatalogSnapshot catalog = TestCalendars.college();
        RecordingSteps steps = new RecordingSteps();
        JsonNode result = handler.apply(new ImportHandler.ApplyInput(UUID.randomUUID(), "графік.xlsx", draft,
                JobOptions.none(), null, normalize(draft, JobOptions.none(), catalog), catalog, steps));

        assertThat(result.at("/calendar/created").asInt()).isEqualTo(120);
        ArgumentCaptor<CoreImportPayloads.CalendarPeriodsRequest> sent =
                ArgumentCaptor.forClass(CoreImportPayloads.CalendarPeriodsRequest.class);
        verify(core).importCalendarPeriods(sent.capture(), eq(false), eq("key:calendar"));
        assertThat(sent.getValue().periods()).anyMatch(period -> period.kindCode().equals("НС")
                && period.groupCodes().equals(List.of("4КІ1")) && "401".equals(period.roomName()));

        handler.apply(new ImportHandler.ApplyInput(UUID.randomUUID(), "графік.xlsx", draft, JobOptions.none(), null,
                normalize(draft, JobOptions.none(), catalog), catalog, steps));
        verify(core, org.mockito.Mockito.times(1)).importCalendarPeriods(any(), eq(false), any());
    }

    @Test
    void applyRefusesABlockedPlan() throws IOException {
        JsonNode draft = synthetic(new String[][] {{"4КІ1", "Х"}});
        CatalogSnapshot catalog = TestCalendars.college();
        assertThatThrownBy(() -> handler.apply(new ImportHandler.ApplyInput(UUID.randomUUID(), "f", draft,
                JobOptions.none(), null, normalize(draft, JobOptions.none(), catalog), catalog, new RecordingSteps())))
                .isInstanceOf(ApplyException.class)
                .hasMessageStartingWith("Невідомий код «Х»");
        verify(core, never()).importCalendarPeriods(any(), anyBoolean(), any());
    }

    @Test
    void conflictOfTheRealRunBecomesReportRows() {
        JsonNode problem = json("""
                {"status":409,"conflicts":[{"kind":"TEACHER","severity":"HARD","message":"Викладач уже має заміну",
                  "lessonIds":[],"date":"2026-11-19","pair":3}]}""");
        assertThat(handler.rejectionRows(new CoreImportException(409, "x", problem, null)))
                .singleElement().satisfies(row -> {
                    assertThat(row.severity()).isEqualTo(Severity.ERROR);
                    assertThat(row.type()).isEqualTo(RowType.PERIOD);
                    assertThat(row.message()).isEqualTo("Викладач уже має заміну (19.11.2026, пара 3)");
                });
        assertThat(handler.rejectionRows(new CoreImportException(400, "x", problem, null))).isEmpty();
        assertThat(handler.rejectionRows(new CoreImportException(409, "x", null))).isEmpty();
    }

    @Test
    void onlyXlsxIsAccepted() {
        assertThat(handler.acceptUpload(ua.vtc.rozklad.importer.job.FileType.XLSX, JobOptions.none())).isFalse();
        assertThatThrownBy(() -> handler.acceptUpload(ua.vtc.rozklad.importer.job.FileType.PDF, JobOptions.none()))
                .hasMessageContaining("XLSX");
    }

    // ───────────────────────── helpers ─────────────────────────

    private ImportHandler.Review review(JsonNode draft, JobOptions options, CatalogSnapshot catalog) {
        return handler.review(draft, options, catalog, normalize(draft, options, catalog));
    }

    private NormalizationResult normalize(JsonNode draft, JobOptions options, CatalogSnapshot catalog) {
        return engine.normalize(handler.entities(draft, options), catalog, List.of());
    }

    private static JsonNode realDraft() {
        return JSON.valueToTree(AcademicCalendarParserTest.real().draft());
    }

    private static JsonNode synthetic(String[][] groups) throws IOException {
        return JSON.valueToTree(AcademicCalendarParser.parse(AcademicCalendarParserTest.sheet(groups), JobOptions.none())
                .draft());
    }

    private static JobOptions options(Map<String, Object> values) {
        return AcademicCalendarParserTest.options(values);
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static AcademicCalendarDraft.Period period(AcademicCalendarDraft draft, String kind, LocalDate start) {
        return draft.periods().stream().filter(period -> period.kindCode().equals(kind) && period.startsOn().equals(start))
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind + " from " + start));
    }

    private static AcademicCalendarDraft.Period period(AcademicCalendarDraft draft, String kind, LocalDate start,
                                                       String group) {
        return draft.periods().stream().filter(period -> period.kindCode().equals(kind) && period.startsOn().equals(start)
                        && period.groupCodes().contains(group))
                .findFirst().orElseThrow(() -> new AssertionError("no " + kind + " from " + start + " for " + group));
    }

    /** Apply checkpoints in memory. */
    private static final class RecordingSteps implements ImportHandler.ApplySteps {

        private final ObjectNode results = JSON.createObjectNode();

        @Override
        public JsonNode done(String step) {
            return results.get(step);
        }

        @Override
        public void finished(String step, JsonNode result) {
            results.set(step, result);
        }

        @Override
        public String idempotencyKey(String step) {
            return "key:" + step;
        }

        @Override
        public JsonNode all() {
            return results.deepCopy();
        }
    }
}
