package ua.vtc.rozklad.importer.order;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.ai.AiDocument;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiExtractions.OrderExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.OrderRow;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.ImportKind;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.order.OrderDraft.Row;
import ua.vtc.rozklad.importer.order.OrderDraft.Section;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.text.GroupCode;
import ua.vtc.rozklad.importer.text.PersonName;
import ua.vtc.rozklad.importer.text.RoomNames;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * ORDER_DOCUMENT: DOCX through POI, photos/scans/PDF through the vision model. The draft keeps the
 * texts exactly as printed; matching its groups, teachers, disciplines and rooms is informative
 * (applying sends text snapshots to {@code POST /api/admin/import/changes}), so ambiguities do not
 * block applying.
 */
@Component
public class OrderImportHandler implements ImportHandler {

    static final String STEP_CHANGES = "changes";
    /** U+FFFD REPLACEMENT CHARACTER: what a reader puts where it could not decode a character. */
    static final char UNREADABLE = '\uFFFD';
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Map<OrderRowType, String> ROW_TYPE_NAMES = Map.of(
            OrderRowType.REPLACEMENT, "«заміна»", OrderRowType.CANCELLATION, "«пари не буде»",
            OrderRowType.ADDITIONAL, "«додатково»", OrderRowType.BLOCK, "«блок пар»",
            OrderRowType.FINISHED, "«вичитано»", OrderRowType.AS_SCHEDULED, "«за розкладом»",
            OrderRowType.ROOM_CHANGE, "«заміна аудиторії»");
    static final String HEADER = "заголовок";
    static final String REVIEW_REQUIRED = "Рядки розпорядження прочитав ШІ з фото/скану: звірте кожен рядок з оригіналом, "
            + "виправте помилки й збережіть рядки — лише після цього розпорядження можна застосувати.";
    private static final List<String> NOT_A_DISCIPLINE = List.of("додатково", "пари не буде", "за розкладом");

    private final OrderDocxParser docxParser;
    private final AiExtractionService ai;
    private final CoreImportClient core;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public OrderImportHandler(OrderDocxParser docxParser, AiExtractionService ai, CoreImportClient core,
                              JsonMapper jsonMapper, Clock clock) {
        this.docxParser = docxParser;
        this.ai = ai;
        this.core = core;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.ORDER_DOCUMENT;
    }

    @Override
    public boolean acceptUpload(FileType type, JobOptions options) {
        if (type == FileType.DOCX) {
            return false;
        }
        if (type == FileType.PDF || type.isImage()) {
            return true;
        }
        throw ProblemException.unsupportedFile("Розпорядження можна завантажити як DOCX, PDF або фото (JPEG, PNG, WEBP), "
                + "а завантажено " + type.label() + ".");
    }

    /** Ambiguous names in an order do not stop the text import. */
    @Override
    public boolean entitiesBlockApply() {
        return false;
    }

    /** The order is stored as text: no group, teacher, discipline or room is created. */
    @Override
    public boolean creates(EntityType type) {
        return false;
    }

    @Override
    public ParseOutcome parse(ParseInput input) {
        Issues issues = new Issues();
        OrderDraft draft;
        if (input.type() == FileType.DOCX) {
            draft = docxParser.parse(input.content(), issues);
        } else {
            OrderExtraction extraction = ai.extractOrder(List.of(new AiDocument(input.type().mediaType(), input.content())),
                    input.jobId()).value();
            draft = fromAi(extraction, issues);
            // перевірка людиною обов'язкова: блокує застосування, доки рядки не збережено (revise)
            issues.error(RowType.ORDER, null, null, REVIEW_REQUIRED);
        }
        if (draft.rows().isEmpty()) {
            issues.warn(RowType.ORDER, null, null, "У розпорядженні не знайдено жодного рядка змін.");
        }
        checkHeader(draft, issues);
        checkReadable(draft, issues);
        return new ParseOutcome(draft, issues.rows());
    }

    /**
     * The header core needs to store the order of record (CONTRACTS §2 import/changes): a number, a
     * date in 2000–2100 that has already come, and the parent number of an amendment. Anything
     * missing is a blocking error, so the job waits for review instead of failing at apply.
     */
    void checkHeader(OrderDraft draft, Issues issues) {
        if (draft.number() == null) {
            issues.error(RowType.ORDER, null, HEADER, "Вкажіть номер розпорядження: його не розпізнано.");
        }
        if (draft.date() == null) {
            issues.error(RowType.ORDER, null, HEADER, "Вкажіть дату розпорядження: її не розпізнано.");
        } else if (draft.date().getYear() < 2000 || draft.date().getYear() > 2100) {
            issues.error(RowType.ORDER, draft.date().toString(), HEADER, "Дата має бути в межах 2000–2100 років.");
        } else if (draft.date().isAfter(LocalDate.now(clock))) {
            issues.error(RowType.ORDER, draft.date().toString(), HEADER, "Дата розпорядження ще не настала: "
                    + "імпортують лише видані розпорядження. Перевірте дату.");
        }
        if (draft.kind() == OrderKind.AMENDMENT && draft.parentNumber() == null) {
            issues.error(RowType.ORDER, null, HEADER, "Вкажіть номер розпорядження, яке доповнюють.");
        }
        // надрукований день тижня — головна ознака неправильно прочитаної дати (парність звіряє core за календарем)
        if (draft.date() != null && draft.weekdayLabel() != null) {
            Optional<DayOfWeek> printed = OrderTexts.weekdayOf(draft.weekdayLabel());
            if (printed.isEmpty()) {
                issues.error(RowType.ORDER, draft.weekdayLabel(), HEADER, "«" + draft.weekdayLabel() + "» — не день "
                        + "тижня. Виправте день тижня за оригіналом або залиште поле порожнім.");
            } else if (printed.get() != draft.date().getDayOfWeek()) {
                issues.error(RowType.ORDER, draft.weekdayLabel(), HEADER, "У розпорядженні надруковано «"
                        + draft.weekdayLabel() + "», а " + DATE.format(draft.date()) + " — "
                        + OrderTexts.weekdayName(draft.date()) + ". Перевірте дату або день тижня.");
            }
        }
    }

    /**
     * Text with U+FFFD (a character the reader could not decode) would go into the order of record
     * and the public changes page as «�»: every such field is a blocking error until corrected.
     */
    static void checkReadable(OrderDraft draft, Issues issues) {
        List<String> header = new ArrayList<>(List.of(nullToEmpty(draft.weekdayLabel()), nullToEmpty(draft.parityLabel()),
                nullToEmpty(draft.signatoryPosition()), nullToEmpty(draft.signatoryName())));
        header.addAll(draft.notes());
        header.stream().filter(OrderImportHandler::unreadable).findFirst().ifPresent(text -> issues.error(
                RowType.ORDER, text, HEADER, "У заголовку, примітках або підписі є нерозпізнані символи «"
                        + UNREADABLE + "» — виправте текст за оригіналом."));
        for (Row row : draft.rows()) {
            String texts = String.join(" ", nullToEmpty(row.groupCode()), nullToEmpty(row.pairLabel()),
                    nullToEmpty(row.fromDiscipline()), nullToEmpty(row.fromTeacher()), nullToEmpty(row.reasonText()),
                    nullToEmpty(row.toDiscipline()), nullToEmpty(row.toTeacher()), nullToEmpty(row.roomText()));
            if (unreadable(texts)) {
                issues.error(RowType.ORDER, TextNormalizer.clean(texts), row.location(), "У рядку є нерозпізнані символи «"
                        + UNREADABLE + "» — виправте текст за оригіналом.");
            }
        }
    }

    private static boolean unreadable(String text) {
        return text.indexOf(UNREADABLE) >= 0;
    }

    /** Validates the model's answer: unknown enum values and dates become warnings, codes are canonical. */
    static OrderDraft fromAi(OrderExtraction extraction, Issues issues) {
        OrderKind kind = "AMENDMENT".equals(extraction.kind()) ? OrderKind.AMENDMENT : OrderKind.ORDER;
        var date = OrderTexts.date(extraction.date());
        if (extraction.date() != null && date.isEmpty()) {
            issues.warn(RowType.ORDER, extraction.date(), "заголовок", "Дату розпорядження «" + extraction.date()
                    + "» не вдалося розібрати.");
        }
        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (OrderRow row : extraction.rows()) {
            index++;
            String location = "рядок " + index;
            Section section = "ROOM_CHANGES".equals(row.section()) ? Section.ROOM_CHANGES : Section.CHANGES;
            OrderRowType type = rowType(row, section, issues, location);
            rows.add(new Row(section, groupCode(row.groupCode(), issues, location), clean(row.pairLabel()), type,
                    clean(row.fromDiscipline()), clean(row.fromTeacher()), clean(row.reasonText()), clean(row.toDiscipline()),
                    clean(row.toTeacher()), clean(row.roomText()), location));
        }
        extraction.warnings().forEach(warning -> issues.warn(RowType.ORDER, null, null, "ШІ: " + warning));
        return new OrderDraft(extraction.number(), kind, extraction.parentNumber(), date.orElse(null),
                clean(extraction.weekdayLabel()), clean(extraction.parityLabel()), rows,
                extraction.notes().stream().map(TextNormalizer::clean).filter(note -> !note.isEmpty()).toList(),
                clean(extraction.signatoryPosition()), clean(extraction.signatoryName()), OrderDraft.Source.AI);
    }

    private static OrderRowType rowType(OrderRow row, Section section, Issues issues, String location) {
        if (section == Section.ROOM_CHANGES) {
            return OrderRowType.ROOM_CHANGE;
        }
        try {
            OrderRowType answered = OrderRowType.valueOf(row.rowType());
            return consistent(answered, row.pairLabel(), row.fromDiscipline(), row.fromTeacher(), row.reasonText(),
                    row.toDiscipline(), row.toTeacher(), issues, location, "ШІ позначив рядок");
        } catch (IllegalArgumentException | NullPointerException e) {
            OrderRowType derived = OrderTexts.rowType(row.pairLabel(), row.fromDiscipline(), row.fromTeacher(),
                    row.reasonText(), row.toDiscipline(), row.toTeacher());
            issues.warn(RowType.ORDER, row.rowType(), location, "Невідомий тип рядка «" + row.rowType()
                    + "» — визначено за текстом як " + derived + ".");
            return derived;
        }
    }

    /**
     * «Вичит.» без заміни — завжди FINISHED, і FINISHED — лише так (правило DOCX-розбору, DOMAIN §8): a
     * given type that contradicts it is replaced by the type of the text, with a WARN naming both.
     */
    static OrderRowType consistent(OrderRowType given, String pairLabel, String fromDiscipline, String fromTeacher,
                                   String reasonText, String toDiscipline, String toTeacher, Issues issues,
                                   String location, String who) {
        boolean finishedByText = OrderTexts.finished(reasonText, toDiscipline, toTeacher);
        if (finishedByText == (given == OrderRowType.FINISHED)) {
            return given;
        }
        OrderRowType derived = OrderTexts.rowType(pairLabel, fromDiscipline, fromTeacher, reasonText, toDiscipline,
                toTeacher);
        issues.warn(RowType.ORDER, given.name(), location, who + " як " + ROW_TYPE_NAMES.get(given)
                + ", але за текстом це " + ROW_TYPE_NAMES.get(derived) + " — взято " + ROW_TYPE_NAMES.get(derived) + ".");
        return derived;
    }

    private static String groupCode(String raw, Issues issues, String location) {
        String clean = clean(raw);
        if (clean == null) {
            issues.warn(RowType.ORDER, null, location, "У рядку не розпізнано групу.");
            return null;
        }
        Optional<GroupCode> code = GroupCode.parse(clean);
        if (code.isEmpty()) {
            issues.warn(RowType.GROUP, clean, location, "«" + clean + "» не схоже на код групи.");
            return clean;
        }
        return code.get().code();
    }

    private static String clean(String text) {
        String clean = TextNormalizer.clean(text);
        return clean.isEmpty() ? null : clean;
    }

    @Override
    public List<EntityCandidate> entities(JsonNode draftJson, JobOptions options) {
        OrderDraft draft = jsonMapper.treeToValue(draftJson, OrderDraft.class);
        Map<String, EntityCandidate> entities = new LinkedHashMap<>();
        for (Row row : draft.rows()) {
            add(entities, EntityType.GROUP, row.groupCode(), row.location(), Function.identity());
            for (String teacher : List.of(nullToEmpty(row.fromTeacher()), nullToEmpty(row.toTeacher()))) {
                for (String part : teacher.split("/")) {
                    add(entities, EntityType.TEACHER, part, row.location(), text -> {
                        PersonName name = PersonName.parse(text);
                        return name == null ? null : name.shortForm();
                    });
                }
            }
            for (String discipline : List.of(nullToEmpty(row.fromDiscipline()), nullToEmpty(row.toDiscipline()))) {
                add(entities, EntityType.DISCIPLINE, discipline, row.location(),
                        text -> NOT_A_DISCIPLINE.contains(TextNormalizer.key(text)) ? null : text);
            }
            for (String room : nullToEmpty(row.roomText()).split("/")) {
                add(entities, EntityType.ROOM, room, row.location(), text -> {
                    String canonical = RoomNames.canonical(text);
                    return canonical.isEmpty() ? null : canonical;
                });
            }
        }
        return List.copyOf(entities.values());
    }

    private static void add(Map<String, EntityCandidate> entities, EntityType type, String raw, String location,
                            Function<String, String> canonical) {
        String clean = TextNormalizer.clean(raw);
        if (clean.isEmpty()) {
            return;
        }
        String value = canonical.apply(clean);
        if (value == null) {
            return;
        }
        entities.putIfAbsent(EntityCandidate.key(type, value), EntityCandidate.of(type, value, location));
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * The admin's corrected rows ({@code PUT /api/import/jobs/{id}/draft}): texts are cleaned, group
     * codes canonicalized, a missing row type is derived from the texts; the result is reviewed.
     */
    public ParseOutcome revise(JsonNode current, OrderDraftEdit edit) {
        OrderDraft before = jsonMapper.treeToValue(current, OrderDraft.class);
        Issues issues = new Issues();
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < edit.rows().size(); index++) {
            OrderDraftEdit.Row row = edit.rows().get(index);
            String location = "рядок " + (index + 1);
            Section section = row.section() == null ? Section.CHANGES : row.section();
            OrderRowType type = section == Section.ROOM_CHANGES ? OrderRowType.ROOM_CHANGE
                    : row.rowType() != null
                    ? consistent(row.rowType(), row.pairLabel(), row.fromDiscipline(), row.fromTeacher(),
                    row.reasonText(), row.toDiscipline(), row.toTeacher(), issues, location, "Рядок позначено")
                    : OrderTexts.rowType(row.pairLabel(), row.fromDiscipline(), row.fromTeacher(), row.reasonText(),
                    row.toDiscipline(), row.toTeacher());
            rows.add(new Row(section, groupCode(row.groupCode(), issues, location), clean(row.pairLabel()), type,
                    clean(row.fromDiscipline()), clean(row.fromTeacher()), clean(row.reasonText()),
                    clean(row.toDiscipline()), clean(row.toTeacher()), clean(row.roomText()), location));
        }
        if (rows.isEmpty()) {
            issues.warn(RowType.ORDER, null, null, "У розпорядженні не залишилося жодного рядка змін.");
        }
        issues.info(RowType.ORDER, null, null, "Рядки звірено з оригіналом і збережено вручну.");
        OrderDraft revised = new OrderDraft(edit.number(), edit.kind() == null ? OrderKind.ORDER : edit.kind(),
                edit.parentNumber(), edit.date(), clean(edit.weekdayLabel()), clean(edit.parityLabel()), rows,
                edit.notes() == null ? List.of()
                        : edit.notes().stream().map(TextNormalizer::clean).filter(note -> !note.isEmpty()).toList(),
                clean(edit.signatoryPosition()), clean(edit.signatoryName()), before.source(), true);
        checkHeader(revised, issues);
        checkReadable(revised, issues);
        return new ParseOutcome(revised, issues.rows());
    }

    @Override
    public JsonNode apply(ApplyInput input) {
        OrderDraft draft = jsonMapper.treeToValue(input.draft(), OrderDraft.class);
        if (draft.date() == null) {
            throw new ApplyException("ORDER_DATE_REQUIRED", "У розпорядженні не розпізнано дату — імпорт змін неможливий.");
        }
        if (input.steps().done(STEP_CHANGES) == null) {
            List<CoreImportPayloads.ChangeItem> items = draft.rows().stream()
                    .map(row -> new CoreImportPayloads.ChangeItem(row.section().name(), row.groupCode(), row.pairLabel(),
                            row.rowType(), row.fromDiscipline(), row.fromTeacher(), row.reasonText(), row.toDiscipline(),
                            row.toTeacher(), row.roomText()))
                    .toList();
            JsonNode result = core.importChanges(new CoreImportPayloads.ChangesRequest(draft.date(), draft.number(),
                    draft.kind(), draft.parentNumber(), draft.weekdayLabel(), draft.parityLabel(), items, draft.notes(),
                    draft.signatoryPosition(), draft.signatoryName(), draft.isReviewed() ? Boolean.TRUE : null),
                    input.steps().idempotencyKey(STEP_CHANGES));
            input.steps().finished(STEP_CHANGES, result);
        }
        return input.steps().all();
    }
}
