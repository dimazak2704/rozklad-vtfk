package ua.vtc.rozklad.importer.order;

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
    private static final List<String> NOT_A_DISCIPLINE = List.of("додатково", "пари не буде", "за розкладом");

    private final OrderDocxParser docxParser;
    private final AiExtractionService ai;
    private final CoreImportClient core;
    private final JsonMapper jsonMapper;

    public OrderImportHandler(OrderDocxParser docxParser, AiExtractionService ai, CoreImportClient core,
                              JsonMapper jsonMapper) {
        this.docxParser = docxParser;
        this.ai = ai;
        this.core = core;
        this.jsonMapper = jsonMapper;
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
            issues.info(RowType.ORDER, null, null, "Розпорядження розпізнано за допомогою ШІ з фото/скану — звірте рядки "
                    + "з оригіналом перед застосуванням.");
        }
        if (draft.rows().isEmpty()) {
            issues.warn(RowType.ORDER, null, null, "У розпорядженні не знайдено жодного рядка змін.");
        }
        return new ParseOutcome(draft, issues.rows());
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
            return OrderRowType.valueOf(row.rowType());
        } catch (IllegalArgumentException | NullPointerException e) {
            OrderRowType derived = OrderTexts.rowType(row.pairLabel(), row.fromDiscipline(), row.fromTeacher(),
                    row.reasonText(), row.toDiscipline(), row.toTeacher());
            issues.warn(RowType.ORDER, row.rowType(), location, "Невідомий тип рядка «" + row.rowType()
                    + "» — визначено за текстом як " + derived + ".");
            return derived;
        }
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
                    draft.signatoryPosition(), draft.signatoryName()), input.steps().idempotencyKey(STEP_CHANGES));
            input.steps().finished(STEP_CHANGES, result);
        }
        return input.steps().all();
    }
}
