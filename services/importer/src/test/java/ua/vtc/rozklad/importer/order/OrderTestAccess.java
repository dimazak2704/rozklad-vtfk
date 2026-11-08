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

    public static void checkReadable(OrderDraft draft, Issues issues) {
        OrderImportHandler.checkReadable(draft, issues);
    }

    public static OrderImportHandler handler(java.time.Clock clock) {
        return new OrderImportHandler(null, null, null, tools.jackson.databind.json.JsonMapper.builder().build(), clock);
    }
}
