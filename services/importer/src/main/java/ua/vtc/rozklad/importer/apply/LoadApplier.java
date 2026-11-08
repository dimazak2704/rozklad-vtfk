package ua.vtc.rozklad.importer.apply;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.job.ImportHandler.ApplyInput;
import ua.vtc.rozklad.importer.job.JobOptions;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.Resolution;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;

/**
 * Shared apply steps of DISCIPLINE_PROGRAM and CURRICULUM: checks semester and target groups,
 * creates new disciplines ({@code catalog} step), then sends load items ({@code load} step).
 */
public final class LoadApplier {

    public static final String STEP_CATALOG = "catalog";
    public static final String STEP_LOAD = "load";
    /** core {@code discipline.short_name} is varchar(64). */
    public static final int SHORT_NAME_LENGTH = 64;

    private LoadApplier() {
    }

    /** Semester is required and must exist (when core lists semesters). */
    public static UUID requireSemester(ApplyInput input) {
        if (input.semesterId() == null) {
            throw new ApplyException("SEMESTER_REQUIRED", "Оберіть семестр, до якого додати навантаження.");
        }
        CatalogSnapshot catalog = input.catalog();
        if (!catalog.semesters().isEmpty() && !catalog.hasSemester(input.semesterId())) {
            throw new ApplyException("SEMESTER_NOT_FOUND", "Обраного семестру немає на сервері розкладу — оберіть інший семестр.");
        }
        return input.semesterId();
    }

    public static final String OPTIONS_LOCATION = "параметри імпорту";
    static final String GROUPS_REQUIRED = "Вкажіть групи, яким додати навантаження (параметри імпорту).";

    /**
     * Readiness rows every load import shares: groups must be given (whether they exist is a
     * matching row, see {@code ImportHandler#creates}).
     */
    public static List<ReportRow> readiness(JobOptions options) {
        return options.groupCodes().isEmpty()
                ? List.of(ReportRow.of(Severity.ERROR, RowType.GROUP, null, OPTIONS_LOCATION, GROUPS_REQUIRED))
                : List.of();
    }

    /** Target groups (options.groupCodes) must exist in the catalog; returns their catalog codes. */
    public static List<String> requireGroups(ApplyInput input, CatalogKeys keys) {
        List<String> codes = input.options().groupCodes();
        if (codes.isEmpty()) {
            throw new ApplyException("GROUPS_REQUIRED", GROUPS_REQUIRED);
        }
        List<String> result = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            Resolution resolution = keys.resolution(EntityType.GROUP, code).orElse(null);
            if (resolution == null || !resolution.existing()) {
                missing.add(code);
            } else {
                result.add(keys.key(EntityType.GROUP, code, "code"));
            }
        }
        if (!missing.isEmpty()) {
            throw new ApplyException("GROUP_NOT_FOUND", "Груп " + String.join(", ", missing)
                    + " немає в каталозі — спершу імпортуйте розклад або створіть групи.");
        }
        return result;
    }

    /**
     * Creates the disciplines that are new (short name from the admin's override or the full name
     * when it fits) and returns the discipline key for every raw name in order.
     */
    public static void createNewDisciplines(ApplyInput input, CatalogKeys keys, List<String> rawNames,
                                            CoreImportClient core, JsonMapper jsonMapper) {
        if (input.steps().done(STEP_CATALOG) != null) {
            return;
        }
        List<CoreImportPayloads.Discipline> disciplines = new ArrayList<>();
        for (String raw : rawNames.stream().distinct().toList()) {
            if (!keys.isNew(EntityType.DISCIPLINE, raw)) {
                continue;
            }
            String shortName = keys.key(EntityType.DISCIPLINE, raw, "shortName");
            if (shortName.length() > SHORT_NAME_LENGTH) {
                throw new ApplyException("SHORT_NAME_REQUIRED", "Назва «" + raw + "» задовга для короткої назви — "
                        + "зіставте її з наявною дисципліною або задайте коротку назву (createNew.shortName).");
            }
            String name = keys.resolution(EntityType.DISCIPLINE, raw)
                    .flatMap(resolution -> CatalogKeys.override(resolution, "name")).orElse(raw);
            disciplines.add(new CoreImportPayloads.Discipline(shortName, name, true));
        }
        JsonNode result = disciplines.isEmpty()
                ? jsonMapper.createObjectNode().put("skipped", true)
                : core.importCatalog(new CoreImportPayloads.CatalogRequest(List.of(), List.of(), List.of(), disciplines,
                        List.of(), List.of(), List.of()), input.steps().idempotencyKey(STEP_CATALOG));
        input.steps().finished(STEP_CATALOG, result);
    }

    public static void sendLoad(ApplyInput input, UUID semesterId, List<CoreImportPayloads.LoadItem> items,
                                CoreImportClient core) {
        if (input.steps().done(STEP_LOAD) != null) {
            return;
        }
        if (items.isEmpty()) {
            throw new ApplyException("NOTHING_TO_APPLY", "Немає жодного рядка навантаження для застосування.");
        }
        JsonNode result = core.importLoad(new CoreImportPayloads.LoadRequest(semesterId, items),
                input.steps().idempotencyKey(STEP_LOAD));
        input.steps().finished(STEP_LOAD, result);
    }
}
