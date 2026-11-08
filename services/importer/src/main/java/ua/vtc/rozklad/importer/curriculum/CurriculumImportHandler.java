package ua.vtc.rozklad.importer.curriculum;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.importer.ai.AiDocument;
import ua.vtc.rozklad.importer.ai.AiExtractionService;
import ua.vtc.rozklad.importer.ai.AiExtractions.CurriculumExtraction;
import ua.vtc.rozklad.importer.ai.AiExtractions.CurriculumRow;
import ua.vtc.rozklad.importer.apply.CatalogKeys;
import ua.vtc.rozklad.importer.apply.CoreImportClient;
import ua.vtc.rozklad.importer.apply.CoreImportPayloads;
import ua.vtc.rozklad.importer.apply.LoadApplier;
import ua.vtc.rozklad.importer.curriculum.CurriculumDraft.ControlForm;
import ua.vtc.rozklad.importer.job.ExcelWorkbooks;
import ua.vtc.rozklad.importer.job.FileType;
import ua.vtc.rozklad.importer.job.ImportHandler;
import ua.vtc.rozklad.importer.job.ImportKind;
import ua.vtc.rozklad.importer.job.ImportParseException;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.normalize.Resolution;
import ua.vtc.rozklad.importer.report.Issues;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;
import ua.vtc.rozklad.importer.text.TextNormalizer;
import ua.vtc.rozklad.importer.web.InvalidRequestException;
import ua.vtc.rozklad.importer.web.ProblemException;

/**
 * CURRICULUM: a scanned plan (PDF/photo) goes to the vision model, an XLSX plan as tab-separated
 * text to the text model; only one semester ({@code options.semesterNumber}) is extracted. The
 * answer is checked (hours per week × weeks, lecture/practice/lab sums, the weekly total) and
 * applied as load items for {@code options.groupCodes}.
 */
@Component
public class CurriculumImportHandler implements ImportHandler {

    private static final int MAX_TABLE_TEXT = 300_000;

    /**
     * A plan row that holds a place for an elective, not a discipline: «Вибірковий освітній
     * компонент 1», «Вибіркова дисципліна 2», «ВК 3», «Дисципліна за вибором (вільного вибору)».
     */
    private static final Pattern ELECTIVE_PLACEHOLDER = Pattern.compile(
            "^(?:вибірков|вк\\s*\\d)|за вибором|вільного вибору");
    static final String CONFIRM_NEW_DISCIPLINE = "Немає в каталозі. Навчальний план пише назви повністю, а розклад — "
            + "скорочено: зіставте з дисципліною каталогу або підтвердьте створення нової дисципліни.";

    private final AiExtractionService ai;
    private final CoreImportClient core;
    private final JsonMapper jsonMapper;

    public CurriculumImportHandler(AiExtractionService ai, CoreImportClient core, JsonMapper jsonMapper) {
        this.ai = ai;
        this.core = core;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.CURRICULUM;
    }

    @Override
    public boolean acceptUpload(FileType type, JobOptions options) {
        if (type != FileType.PDF && type != FileType.XLSX && !type.isImage()) {
            throw ProblemException.unsupportedFile("Навчальний план можна завантажити як PDF (скан), фото або XLSX, а завантажено "
                    + type.label() + ".");
        }
        if (options.semesterNumber() == null) {
            throw InvalidRequestException.field("options.semesterNumber",
                    "Вкажіть номер семестру навчального плану (1–8), який треба розпізнати.");
        }
        return true;
    }

    @Override
    public ParseOutcome parse(ParseInput input) {
        int semester = input.options().semesterNumber();
        CurriculumExtraction extraction;
        CurriculumDraft.Source source;
        if (input.type() == FileType.XLSX) {
            extraction = ai.extractCurriculum(List.of(), tableText(input.content()), semester, input.jobId()).value();
            source = CurriculumDraft.Source.AI_TABLE;
        } else {
            extraction = ai.extractCurriculum(List.of(new AiDocument(input.type().mediaType(), input.content())), null,
                    semester, input.jobId()).value();
            source = CurriculumDraft.Source.AI_VISION;
        }
        Issues issues = new Issues();
        CurriculumDraft draft = toDraft(extraction, semester, source, issues);
        issues.info(RowType.CURRICULUM, null, null, "Навчальний план розпізнано за допомогою ШІ — звірте години з документом "
                + "перед застосуванням.");
        return new ParseOutcome(draft, issues.rows());
    }

    /** Validates the model answer; inconsistencies are warnings, values stay as recognized. */
    static CurriculumDraft toDraft(CurriculumExtraction extraction, int semester, CurriculumDraft.Source source,
                                   Issues issues) {
        if (extraction.semester() != null && extraction.semester() != semester) {
            issues.warn(RowType.CURRICULUM, String.valueOf(extraction.semester()), null, "Модель повернула семестр "
                    + extraction.semester() + " замість " + semester + ".");
        }
        Integer weeks = extraction.weeks();
        List<CurriculumDraft.Row> rows = new ArrayList<>();
        double weeklySum = 0;
        int index = 0;
        for (CurriculumRow row : extraction.rows()) {
            index++;
            String location = "рядок " + index;
            String discipline = TextNormalizer.clean(row.discipline());
            if (discipline.isEmpty()) {
                issues.warn(RowType.CURRICULUM, null, location, "Рядок без назви дисципліни пропущено.");
                continue;
            }
            if (row.hoursPerWeek() == null || row.hoursPerWeek() <= 0) {
                issues.warn(RowType.CURRICULUM, discipline, location, "Не вказано годин на тиждень — рядок пропущено.");
                continue;
            }
            weeklySum += row.hoursPerWeek();
            if (weeks != null && row.hoursPerSemester() != null
                    && Math.abs(row.hoursPerWeek() * weeks - row.hoursPerSemester()) > 1.0) {
                issues.warn(RowType.CURRICULUM, discipline, location, "«" + discipline + "»: " + format(row.hoursPerWeek())
                        + " год/тиж × " + weeks + " тиж ≠ " + row.hoursPerSemester() + " год за семестр.");
            }
            int parts = value(row.lectures()) + value(row.practice()) + value(row.labs());
            if (row.classroomHours() != null && parts > 0 && parts != row.classroomHours()) {
                issues.warn(RowType.CURRICULUM, discipline, location, "«" + discipline + "»: лекції, практичні й лабораторні "
                        + "разом " + parts + " год, а аудиторних — " + row.classroomHours() + ".");
            }
            rows.add(new CurriculumDraft.Row(discipline, row.hoursPerWeek(), row.hoursPerSemester(), row.totalHours(),
                    row.classroomHours(), row.lectures(), row.practice(), row.labs(), controlForm(row.controlForm())));
        }
        if (extraction.totalHoursPerWeek() != null && Math.abs(extraction.totalHoursPerWeek() - weeklySum) > 0.01) {
            issues.warn(RowType.CURRICULUM, format(weeklySum), null, "Сума годин на тиждень за рядками " + format(weeklySum)
                    + ", а в рядку «Всього» — " + format(extraction.totalHoursPerWeek()) + ".");
        }
        if (rows.isEmpty()) {
            issues.error(RowType.CURRICULUM, null, null, "Для семестру " + semester + " не розпізнано жодної дисципліни.");
        }
        extraction.warnings().forEach(warning -> issues.warn(RowType.CURRICULUM, null, null, "ШІ: " + warning));
        return new CurriculumDraft(semester, weeks, rows, extraction.totalHoursPerWeek(), source);
    }

    private static ControlForm controlForm(String value) {
        try {
            return value == null ? ControlForm.UNKNOWN : ControlForm.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ControlForm.UNKNOWN;
        }
    }

    /** Applying adds load items: only disciplines may be new, groups must exist. */
    @Override
    public boolean creates(EntityType type) {
        return type == EntityType.DISCIPLINE;
    }

    /**
     * r3-ai-imports-01: the plan's disciplines usually exist under the short names of the timetable, so
     * a discipline that is not in the catalog is created only when the admin confirms it; an
     * elective placeholder is never a discipline by itself (the group studies a chosen one).
     */
    @Override
    public Optional<String> createNeedsConfirmation(EntityCandidate entity) {
        if (entity.type() != EntityType.DISCIPLINE) {
            return Optional.empty();
        }
        if (electivePlaceholder(entity.raw())) {
            return Optional.of("«" + entity.raw() + "» — місце для вибіркової дисципліни, а не її назва. Зіставте його з "
                    + "дисципліною, яку група вивчає за вибором; нову дисципліну створюйте, лише якщо вибору ще немає.");
        }
        return Optional.of(CONFIRM_NEW_DISCIPLINE);
    }

    static boolean electivePlaceholder(String name) {
        return ELECTIVE_PLACEHOLDER.matcher(TextNormalizer.key(name)).find();
    }

    /** Groups must be given; a discipline name with characters the model could not read is not created. */
    @Override
    public List<ReportRow> readiness(JsonNode draftJson, JobOptions options, NormalizationResult normalization) {
        CurriculumDraft draft = jsonMapper.treeToValue(draftJson, CurriculumDraft.class);
        List<ReportRow> rows = new ArrayList<>(LoadApplier.readiness(options));
        int index = 0;
        for (CurriculumDraft.Row row : draft.rows()) {
            index++;
            boolean mapped = normalization.find(EntityType.DISCIPLINE, row.discipline()).map(Resolution::existing)
                    .orElse(false);
            if (row.discipline().indexOf('\uFFFD') >= 0 && !mapped) {
                rows.add(ReportRow.of(Severity.ERROR, RowType.DISCIPLINE, row.discipline(), "рядок " + index,
                        "У назві є нерозпізнані символи «\uFFFD» — зіставте дисципліну з каталогом на вкладці «Рішення» "
                                + "або завантажте чіткіший скан."));
            }
        }
        return rows;
    }

    @Override
    public List<EntityCandidate> entities(JsonNode draftJson, JobOptions options) {
        CurriculumDraft draft = jsonMapper.treeToValue(draftJson, CurriculumDraft.class);
        Map<String, EntityCandidate> entities = new LinkedHashMap<>();
        int index = 0;
        for (CurriculumDraft.Row row : draft.rows()) {
            index++;
            entities.putIfAbsent(EntityCandidate.key(EntityType.DISCIPLINE, row.discipline()),
                    new EntityCandidate(EntityType.DISCIPLINE, row.discipline(), List.of(), row.discipline(), "рядок " + index));
        }
        options.groupCodes().forEach(code -> entities.putIfAbsent(EntityCandidate.key(EntityType.GROUP, code),
                EntityCandidate.of(EntityType.GROUP, code, "параметри імпорту")));
        return List.copyOf(entities.values());
    }

    @Override
    public JsonNode apply(ApplyInput input) {
        CurriculumDraft draft = jsonMapper.treeToValue(input.draft(), CurriculumDraft.class);
        UUID semesterId = LoadApplier.requireSemester(input);
        CatalogKeys keys = new CatalogKeys(input.normalization(), input.catalog());
        List<String> groups = LoadApplier.requireGroups(input, keys);
        LoadApplier.createNewDisciplines(input, keys, draft.rows().stream().map(CurriculumDraft.Row::discipline).toList(),
                core, jsonMapper);
        List<CoreImportPayloads.LoadItem> items = new ArrayList<>();
        for (CurriculumDraft.Row row : draft.rows()) {
            String discipline = keys.key(EntityType.DISCIPLINE, row.discipline(), "shortName");
            int planned = row.hoursPerSemester() != null ? row.hoursPerSemester()
                    : draft.weeks() == null ? 0 : (int) Math.round(row.hoursPerWeek() * draft.weeks());
            String control = switch (row.controlForm()) {
                case EXAM -> "EXAM";
                case CREDIT -> "CREDIT";
                default -> "NONE";
            };
            for (String group : groups) {
                items.add(new CoreImportPayloads.LoadItem(group, discipline, LessonKind.MIXED,
                        BigDecimal.valueOf(row.hoursPerWeek()).setScale(1, RoundingMode.HALF_UP), planned, control,
                        "CURRICULUM", "Навчальний план, семестр " + draft.semester()));
            }
        }
        LoadApplier.sendLoad(input, semesterId, items, core);
        return input.steps().all();
    }

    /** XLSX → tab-separated text of every sheet (empty rows skipped) for the text model. */
    static String tableText(byte[] content) {
        DataFormatter formatter = new DataFormatter();
        StringBuilder text = new StringBuilder();
        try (Workbook workbook = ExcelWorkbooks.open(content, "Не вдалося прочитати файл Excel навчального плану: він "
                + "пошкоджений або має інший формат. Збережіть його в Excel ще раз як .xlsx.")) {
            for (Sheet sheet : workbook) {
                text.append("# ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    boolean any = false;
                    for (int column = 0; column < row.getLastCellNum(); column++) {
                        Cell cell = row.getCell(column);
                        String value = cell == null ? "" : TextNormalizer.clean(formatter.formatCellValue(cell));
                        any |= !value.isEmpty();
                        cells.add(value);
                    }
                    if (any) {
                        text.append(String.join("\t", cells)).append('\n');
                    }
                }
                if (text.length() > MAX_TABLE_TEXT) {
                    throw new ImportParseException("Таблиця навчального плану завелика для розпізнавання — залиште в файлі "
                            + "лише аркуш із планом освітнього процесу.");
                }
            }
        } catch (IOException e) {
            throw new ImportParseException("Не вдалося прочитати файл Excel навчального плану.", e);
        }
        return text.toString();
    }

    private static int value(Integer hours) {
        return hours == null ? 0 : hours;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
