package ua.vtc.rozklad.importer.job;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import ua.vtc.rozklad.importer.apply.CatalogKeys;
import ua.vtc.rozklad.importer.apply.LoadApplier;
import ua.vtc.rozklad.importer.catalog.CatalogSnapshot;
import ua.vtc.rozklad.importer.normalize.EntityCandidate;
import ua.vtc.rozklad.importer.normalize.EntityType;
import ua.vtc.rozklad.importer.normalize.MappingDecision;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine;
import ua.vtc.rozklad.importer.normalize.NormalizationEngine.NormalizationResult;
import ua.vtc.rozklad.importer.normalize.Resolution;
import ua.vtc.rozklad.importer.report.ReportRow;
import ua.vtc.rozklad.importer.report.RowType;
import ua.vtc.rozklad.importer.report.Severity;

/** Matches a job's draft entities against a catalog snapshot and turns the result into report rows. */
@Component
class JobNormalizer {

    /** Normalization result plus the report rows derived from it. */
    record Normalized(NormalizationResult result, List<ReportRow> rows) {

        boolean blocking() {
            return rows.stream().anyMatch(ReportRow::blocking);
        }
    }

    private final ImportHandlers handlers;
    private final NormalizationEngine engine;

    JobNormalizer(ImportHandlers handlers, NormalizationEngine engine) {
        this.handlers = handlers;
        this.engine = engine;
    }

    List<EntityCandidate> entities(ImportKind kind, JsonNode draft, JobOptions options) {
        return draft == null ? List.of() : handlers.get(kind).entities(draft, options);
    }

    Normalized normalize(ImportKind kind, JsonNode draft, JobOptions options, List<MappingDecision> mappings,
                         CatalogSnapshot catalog) {
        ImportHandler handler = handlers.get(kind);
        NormalizationResult result = engine.normalize(entities(kind, draft, options), catalog, mappings);
        List<ReportRow> rows = new ArrayList<>();
        for (Resolution resolution : result.resolutions()) {
            ReportRow row = NormalizationEngine.row(resolution);
            EntityType type = resolution.entity().type();
            if (row.needsDecision() && !handler.entitiesBlockApply()) {
                row = new ReportRow(Severity.INFO, row.type(), row.raw(), null, row.suggestions(), row.location(),
                        "У каталозі є схожі записи (для інформації: розпорядження імпортується як текст).");
            } else if (resolution.createsNew() && !handler.creates(type)) {
                row = handler.entitiesBlockApply()
                        ? new ReportRow(Severity.ERROR, row.type(), row.raw(), null, row.suggestions(), row.location(),
                                "Немає в каталозі, а цей імпорт не створює нових " + type.genitivePlural() + ". "
                                        + missingHint(type))
                        : new ReportRow(Severity.INFO, row.type(), row.raw(), null, row.suggestions(), row.location(),
                                "Немає в каталозі (для інформації: розпорядження зберігається як текст, каталог не "
                                        + "змінюється).");
            } else if (needsShortName(resolution)) {
                row = new ReportRow(Severity.ERROR, row.type(), row.raw(), null, row.suggestions(), row.location(),
                        "Назва довша за " + LoadApplier.SHORT_NAME_LENGTH + " символи: зіставте з наявною дисципліною або "
                                + "задайте коротку назву (createNew.shortName).");
            }
            rows.add(row);
        }
        if (draft != null) {
            rows.addAll(handler.readiness(draft, options, result));
        }
        return new Normalized(result, rows);
    }

    /** The blocking row used when core's catalog cannot be fetched: matching has to be repeated. */
    static ReportRow catalogUnavailable(String reason) {
        return ReportRow.of(Severity.ERROR, RowType.CATALOG, null, null, reason
                + " Зіставлення з каталогом не виконано — повторіть його (збережіть зіставлення), коли сервер розкладу буде доступний.");
    }

    private static String missingHint(EntityType type) {
        return type == EntityType.GROUP
                ? "Навантаження додається лише наявним групам: виправте код групи в параметрах імпорту або спершу "
                        + "імпортуйте розклад."
                : "Зіставте значення з наявним записом каталогу.";
    }

    private static boolean needsShortName(Resolution resolution) {
        return resolution.entity().type() == EntityType.DISCIPLINE && resolution.createsNew()
                && CatalogKeys.override(resolution, "shortName").isEmpty()
                && resolution.entity().raw().length() > LoadApplier.SHORT_NAME_LENGTH;
    }
}
