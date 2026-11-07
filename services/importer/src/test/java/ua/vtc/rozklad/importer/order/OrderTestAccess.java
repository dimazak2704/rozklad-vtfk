package ua.vtc.rozklad.importer.order;

import ua.vtc.rozklad.importer.ai.AiExtractions.OrderExtraction;
import ua.vtc.rozklad.importer.report.Issues;

/** Test access to the package-private mapping of AI order answers. */
public final class OrderTestAccess {

    private OrderTestAccess() {
    }

    public static OrderDraft fromAi(OrderExtraction extraction, Issues issues) {
        return OrderImportHandler.fromAi(extraction, issues);
    }
}
