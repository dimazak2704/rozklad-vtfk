package ua.vtc.rozklad.importer.program;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.importer.ai.AiBudgetExceededException;
import ua.vtc.rozklad.importer.ai.AiExtractionException;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiExtractions.ProgramHoursExtraction;
import ua.vtc.rozklad.importer.ai.AiUnavailableException;
import ua.vtc.rozklad.importer.apply.ApplyException;
import ua.vtc.rozklad.importer.apply.CatalogKeys;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.apply.LoadApplier;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.ImportKind;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.program.ProgramDraft.Hours;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * DISCIPLINE_PROGRAM: deterministic table parsing first; the text model is used only when the
 * structure table is missing. Applying adds one load item per target group (options.groupCodes)
 * with the program's classroom hours as planned hours.
 */
@Component
public class ProgramImportHandler implements ImportHandler {

    static final String HOURS_PER_WEEK_REQUIRED = "У програмі не вказано годин на тиждень — задайте «Годин на тиждень» "
            + "у параметрах імпорту.";
    static final String CLASSROOM_REQUIRED = "У програмі не визначено годин на заняттях — застосувати неможливо. "
            + "Завантажте програму з таблицею «Структура навчальної дисципліни».";

    private final ProgramDocumentReader reader;
    private final ProgramStructureExtractor extractor;
    private final AiExtractionService ai;
    private final CoreImportClient core;
    private final JsonMapper jsonMapper;

    public ProgramImportHandler(ProgramDocumentReader reader, ProgramStructureExtractor extractor, AiExtractionService ai,
                                CoreImportClient core, JsonMapper jsonMapper) {
        this.reader = reader;
        this.extractor = extractor;
        this.ai = ai;
        this.core = core;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.DISCIPLINE_PROGRAM;
    }

    @Override
    public boolean acceptUpload(FileType type, JobOptions options) {
        if (type != FileType.DOCX && type != FileType.DOC && type != FileType.PDF) {
            throw ProblemException.unsupportedFile("Програму дисципліни можна завантажити як DOCX, DOC або PDF, а завантажено "
                    + type.label() + ".");
        }
        return false;
    }

    @Override
    public ParseOutcome parse(ParseInput input) {
        Issues issues = new Issues();
        ProgramDocument document = reader.read(input.type(), input.content());
        ProgramStructureExtractor.Extraction extraction = extractor.extract(document, issues);
        Hours hours = extraction.hours();
        ProgramDraft.Source source = extraction.source();
        String name = extraction.disciplineName();
        Integer semester = extraction.semester();
        Double hoursPerWeek = extraction.hoursPerWeek();
        if (hours == null) {
            try {
                ProgramHoursExtraction fallback = ai.extractProgramHours(document.fullText(), input.jobId()).value();
                Integer classroom = fallback.classroomHours();
                int parts = value(fallback.lectures()) + value(fallback.practice()) + value(fallback.labs())
                        + value(fallback.seminars());
                if (classroom == null && parts > 0) {
                    // «на заняттях» — сума видів занять; без неї навантаження не застосувати
                    classroom = parts;
                    issues.warn(RowType.HOURS, String.valueOf(parts), null, "Годин на заняттях у програмі не названо — "
                            + "пораховано як суму лекцій, практичних, лабораторних і семінарів: " + parts + ".");
                }
                hours = new Hours(fallback.totalHours(), classroom, fallback.selfStudyHours(),
                        fallback.lectures(), fallback.practice(), fallback.labs(), fallback.seminars(), null,
                        fallback.exam(), fallback.credit());
                source = ProgramDraft.Source.AI;
                String aiName = TextNormalizer.clean(fallback.disciplineName());
                if (name != null && !aiName.isEmpty() && !TextNormalizer.key(aiName).equals(TextNormalizer.key(name))) {
                    issues.warn(RowType.DISCIPLINE, aiName, "титульна сторінка", "На титульній сторінці дисципліну "
                            + "названо «" + name + "», а ШІ прочитав «" + aiName + "». Перевірте зіставлення дисципліни.");
                }
                name = name != null ? name : aiName.isEmpty() ? null : aiName;
                semester = semester != null ? semester : fallback.semester();
                hoursPerWeek = hoursPerWeek != null ? hoursPerWeek : fallback.hoursPerWeek();
                issues.warn(RowType.HOURS, null, null, "Таблицю «Структура навчальної дисципліни…» не знайдено — години "
                        + "визначено за допомогою ШІ з тексту програми. Звірте їх із документом.");
                fallback.warnings().forEach(warning -> issues.warn(RowType.HOURS, null, null, "ШІ: " + warning));
            } catch (AiUnavailableException | AiExtractionException | AiBudgetExceededException e) {
                issues.error(RowType.HOURS, null, null, "Таблицю «Структура навчальної дисципліни…» не знайдено, а "
                        + "розпізнати години за допомогою ШІ не вдалося: " + e.getMessage());
            }
        }
        String label = name != null ? name : baseName(input.fileName());
        ProgramDraft draft = new ProgramDraft(name, label, extraction.cycleCommission(), hours, semester, hoursPerWeek,
                extraction.credits(), source, extraction.location());
        return new ParseOutcome(draft, issues.rows());
    }

    /** Applying adds load items: only the discipline may be new, groups must exist. */
    @Override
    public boolean creates(EntityType type) {
        return type == EntityType.DISCIPLINE;
    }

    /**
     * Everything apply needs, as blocking rows: groups, hours on classes, hours per week (from the
     * program or the import parameters) and a discipline when the title page names none.
     */
    @Override
    public List<ReportRow> readiness(JsonNode draftJson, JobOptions options, NormalizationResult normalization) {
        ProgramDraft draft = jsonMapper.treeToValue(draftJson, ProgramDraft.class);
        List<ReportRow> rows = new ArrayList<>(LoadApplier.readiness(options));
        Hours hours = draft.hours();
        if (hours != null && hours.classroom() == null) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.HOURS, null, draft.location(), CLASSROOM_REQUIRED));
        }
        if (hoursPerWeek(draft, options) == null) {
            rows.add(ReportRow.of(Severity.ERROR, RowType.HOURS, null, LoadApplier.OPTIONS_LOCATION,
                    HOURS_PER_WEEK_REQUIRED));
        }
        if (draft.disciplineName() == null) {
            normalization.find(EntityType.DISCIPLINE, draft.disciplineRaw())
                    .filter(resolution -> resolution.createsNew() && resolution.decision() == null)
                    .ifPresent(resolution -> rows.add(ReportRow.of(Severity.ERROR, RowType.DISCIPLINE,
                            draft.disciplineRaw(), "титульна сторінка", "Назви дисципліни в програмі не знайдено — "
                                    + "«" + draft.disciplineRaw() + "» узято з назви файлу. Оберіть дисципліну з "
                                    + "каталогу на вкладці «Рішення».")));
        }
        return rows;
    }

    @Override
    public List<EntityCandidate> entities(JsonNode draftJson, JobOptions options) {
        ProgramDraft draft = jsonMapper.treeToValue(draftJson, ProgramDraft.class);
        List<EntityCandidate> entities = new ArrayList<>();
        entities.add(new EntityCandidate(EntityType.DISCIPLINE, draft.disciplineRaw(), List.of(), draft.disciplineName(),
                "титульна сторінка"));
        options.groupCodes().forEach(code -> entities.add(EntityCandidate.of(EntityType.GROUP, code, "параметри імпорту")));
        return entities;
    }

    @Override
    public JsonNode apply(ApplyInput input) {
        ProgramDraft draft = jsonMapper.treeToValue(input.draft(), ProgramDraft.class);
        UUID semesterId = LoadApplier.requireSemester(input);
        CatalogKeys keys = new CatalogKeys(input.normalization(), input.catalog());
        List<String> groups = LoadApplier.requireGroups(input, keys);
        Hours hours = draft.hours();
        if (hours == null || hours.classroom() == null) {
            throw new ApplyException("HOURS_REQUIRED", CLASSROOM_REQUIRED);
        }
        BigDecimal hoursPerWeek = hoursPerWeek(draft, input.options());
        if (hoursPerWeek == null) {
            throw new ApplyException("HOURS_PER_WEEK_REQUIRED", HOURS_PER_WEEK_REQUIRED);
        }
        LoadApplier.createNewDisciplines(input, keys, List.of(draft.disciplineRaw()), core, jsonMapper);
        String discipline = keys.key(EntityType.DISCIPLINE, draft.disciplineRaw(), "shortName");
        String controlForm = hours.exam() ? "EXAM" : hours.credit() ? "CREDIT" : "NONE";
        List<CoreImportPayloads.LoadItem> items = groups.stream()
                .map(group -> new CoreImportPayloads.LoadItem(group, discipline, LessonKind.MIXED, hoursPerWeek,
                        hours.classroom(), controlForm, "PROGRAM", "Програма: " + input.fileName()))
                .toList();
        LoadApplier.sendLoad(input, semesterId, items, core);
        return input.steps().all();
    }

    /** Weekly hours of the import parameters, else of the program; {@code null} when neither is positive. */
    private static BigDecimal hoursPerWeek(ProgramDraft draft, JobOptions options) {
        BigDecimal hours = options.hoursPerWeek() != null ? options.hoursPerWeek()
                : draft.hoursPerWeek() == null ? null : BigDecimal.valueOf(draft.hoursPerWeek());
        return hours == null || hours.signum() <= 0 ? null : hours;
    }

    private static int value(Integer hours) {
        return hours == null ? 0 : hours;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
